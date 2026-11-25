package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(60)
class StructuredFailureIdentityTest {
    private enum class Verb { Hold, Store, StoreJson, Copy }
    private enum class Kind {
        Ordinary, Cancellation, Fatal;

        fun failure(): Throwable = when (this) {
            Ordinary -> BodyFailure("body")
            Cancellation -> BodyCancellation("body")
            Fatal -> BodyError("body")
        }
    }

    @Test
    fun `reference bodies preserve an identical release startup failure without self suppression`(): Unit = runBlocking {
        checkFailures(completeFirst = false, sameFailure = true)
    }

    @Test
    fun `reference bodies preserve an identical release completion observer failure`(): Unit = runBlocking {
        checkFailures(completeFirst = true, sameFailure = true)
    }

    @Test
    fun `distinct release observer failures stay suppressed on ordinary cancellation and fatal bodies`(): Unit = runBlocking {
        checkFailures(completeFirst = false, sameFailure = false)
    }

    @Test
    fun `release observer failures after successful bodies remain directly observable`(): Unit = runBlocking {
        for (language in listOf("py", "node")) {
            for (verb in Verb.entries) {
                for (kind in listOf(Kind.Fatal)) {
                    val cleanup = kind.failure()
                    var releaseCalls = 0
                    val sink = RuntimeTraceSink { event ->
                        if (event is RuntimeDispatchStarted && event.operation == "release") {
                            releaseCalls++
                            throw cleanup
                        }
                    }
                    withRuntimeTrace(sink) {
                        inRuntime(language) {
                            val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                            lateinit var reference: RuntimeRef
                            val actual = runCatching {
                                withReference(verb) { reference = it; 84 }
                            }.exceptionOrNull()
                            assertSame(cleanup, actual, "$language/$verb/$kind")
                            assertTrue(cleanup.suppressed.isEmpty())
                            assertEquals(1, releaseCalls)
                            assertStopped(reference, process)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `failed explicit copies preserve body identity and their independent source session`(): Unit = runBlocking {
        for ((sourceLanguage, targetLanguage) in listOf("py" to "node", "node" to "py")) {
            for (kind in listOf(Kind.Fatal)) {
                val original = kind.failure()
                val trace = RuntimeTraceBuffer()
                val sink = RuntimeTraceSink { event ->
                    trace.onEvent(event)
                    if (event is RuntimeDispatchStarted && event.runtime == targetLanguage && event.operation == "release") {
                        throw original
                    }
                }
                withRuntimeTrace(sink) {
                    inRuntime(sourceLanguage) {
                        val source = this
                        val sourceProcess = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        store(listOf(7)) { reference ->
                            inRuntime(targetLanguage) {
                                val targetProcess = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                                lateinit var copied: RuntimeRef
                                val actual = runCatching {
                                    copy(reference) { copied = it; throw original }
                                }.exceptionOrNull()
                                assertSame(original, actual)
                                assertTrue(original.suppressed.isEmpty())
                                assertStopped(copied, targetProcess)
                            }
                            assertTrue(sourceProcess.isAlive)
                            assertFalse(reference.isReleased)
                            assertEquals(listOf(7), source.read<List<Int>>(reference))
                            assertEquals(42, source.eval<Int>("42"))
                        }
                        assertTrue(runtime.isOpen)
                        assertEquals(42, eval<Int>("42"))
                    }
                }
                val sourceSession = trace.events.filterIsInstance<RuntimeSessionStarted>().single { it.runtime == sourceLanguage }
                val copied = trace.events.filterIsInstance<RuntimeDispatchStarted>().single {
                    it.runtime == targetLanguage && it.operation == "store"
                }
                assertEquals(listOf(sourceSession.sessionId), copied.inputSessionIds)
                assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single { it.runtime == sourceLanguage }.poisoned)
                assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single { it.runtime == targetLanguage }.poisoned)
            }
        }
    }

    @Test
    fun `ordinary cancellation and fatal request wrapper failures cannot self suppress the reference body failure`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            for (verb in Verb.entries) {
                for (kind in Kind.entries) {
                    val original = kind.failure()
                    val wrapped = spec.copy(requestWrapper = { request, marker ->
                        if (request["op"]?.jsonPrimitive?.content == "release") throw original
                        checkNotNull(spec.requestWrapper)(request, marker)
                    })
                    runtime(wrapped) {
                        val scope = StructuredRuntimeScope(coroutineContext, runtimeContext)
                        val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        lateinit var reference: RuntimeRef
                        val actual = runCatching {
                            scope.withReference(verb) { reference = it; throw original }
                        }.exceptionOrNull()
                        assertSame(original, actual)
                        assertTrue(original.suppressed.isEmpty())
                        scope.assertStopped(reference, process)
                    }
                }
            }
        }
    }

    @Test
    fun `ordinary and cancellation release trace observer failures remain isolated and leave sessions usable`(): Unit = runBlocking {
        for (language in listOf("py", "node")) {
            for (verb in Verb.entries) {
                for (kind in listOf(Kind.Ordinary, Kind.Cancellation)) {
                    val original = kind.failure()
                    val releases = mutableSetOf<Long>()
                    var calls = 0
                    val sink = RuntimeTraceSink { event ->
                        if (event is RuntimeDispatchStarted && event.operation == "release") {
                            releases += event.dispatchId
                            calls++
                            throw original
                        }
                        if (event is RuntimeDispatchCompleted && event.dispatchId in releases) {
                            calls++
                            throw original
                        }
                    }
                    withRuntimeTrace(sink) {
                        inRuntime(language) {
                            lateinit var reference: RuntimeRef
                            val actual = runCatching {
                                withReference(verb) { reference = it; throw original }
                            }.exceptionOrNull()
                            assertSame(original, actual)
                            assertTrue(original.suppressed.isEmpty())
                            assertEquals(if (verb == Verb.Copy) 4 else 2, calls)
                            assertTrue(reference.isReleased)
                            assertTrue(runtime.isOpen)
                            assertEquals(42, eval<Int>("42"))
                        }
                    }
                }
            }
        }
    }

    private suspend fun checkFailures(completeFirst: Boolean, sameFailure: Boolean) {
        for (language in listOf("py", "node")) {
            for (verb in Verb.entries) {
                for (kind in Kind.entries.filter { !sameFailure || it == Kind.Fatal }) {
                    val original = kind.failure()
                    val cleanup = if (sameFailure) original else BodyError("cleanup")
                    val trace = RuntimeTraceBuffer()
                    var releaseId: Long? = null
                    var releaseCalls = 0
                    val sink = RuntimeTraceSink { event ->
                        trace.onEvent(event)
                        if (event is RuntimeDispatchStarted && event.operation == "release") {
                            releaseId = event.dispatchId
                            if (!completeFirst) { releaseCalls++; throw cleanup }
                        }
                        if (completeFirst && event is RuntimeDispatchCompleted && event.dispatchId == releaseId) {
                            releaseCalls++
                            throw cleanup
                        }
                    }
                    withRuntimeTrace(sink) {
                        inRuntime(language) {
                            val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                            lateinit var reference: RuntimeRef
                            val actual = runCatching {
                                withReference(verb) { reference = it; throw original }
                            }.exceptionOrNull()
                            assertSame(original, actual, "$language/$verb/$kind, completion=$completeFirst")
                            assertEquals(if (sameFailure) emptyList() else listOf(cleanup), original.suppressed.toList())
                            assertEquals(1, releaseCalls)
                            assertStopped(reference, process)
                        }
                    }
                    val release = trace.events.filterIsInstance<RuntimeDispatchStarted>().single { it.operation == "release" }
                    val terminals = trace.events.filter {
                        it is RuntimeDispatchCompleted && it.dispatchId == release.dispatchId ||
                            it is RuntimeDispatchFailed && it.dispatchId == release.dispatchId
                    }
                    assertEquals(1, terminals.size, "Cleanup cannot rewrite the first terminal fact")
                    if (completeFirst) assertIs<RuntimeDispatchCompleted>(terminals.single())
                    else assertIs<RuntimeDispatchFailed>(terminals.single())
                    assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
                }
            }
        }
    }

    private suspend fun StructuredRuntimeScope.withReference(verb: Verb, block: suspend (RuntimeRef) -> Int): Int =
        when (verb) {
            Verb.Hold -> hold("[7]", block = block)
            Verb.Store -> store(listOf(7), block = block)
            Verb.StoreJson -> storeJson(Json.parseToJsonElement("[7]"), block = block)
            Verb.Copy -> store(listOf(7)) { source -> copy(source, block = block) }
        }

    private suspend fun inRuntime(language: String, block: suspend StructuredRuntimeScope.() -> Unit) {
        if (language == "py") py { block() } else node { block() }
    }

    private fun StructuredRuntimeScope.assertStopped(reference: RuntimeRef, process: ProcessHandle) {
        assertFalse(process.isAlive, "Owned interpreter cleanup must precede propagation")
        assertFalse(runtime.isOpen)
        assertTrue(reference.isReleased)
    }

    // Extra state keeps coroutine stack recovery from copying these fixtures.
    private class BodyFailure(val phase: String) : IllegalArgumentException(phase)
    private class BodyCancellation(val phase: String) : CancellationException(phase)
    private class BodyError(val phase: String) : Error(phase)
}
