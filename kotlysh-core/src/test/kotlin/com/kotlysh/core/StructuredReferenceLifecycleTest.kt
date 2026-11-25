package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*

@Timeout(20)
class StructuredReferenceLifecycleTest {
    @Test
    fun `an ordinary foreign release failure leaves a live handle usable until successful release`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            lateinit var reference: RuntimeRef
            var rejectRelease = true
            val operations = mutableMapOf<Long, String>()
            val observed = mutableListOf<Boolean>()
            val wrapper = checkNotNull(spec.requestWrapper)
            val controlled = spec.copy(requestWrapper = { request, marker ->
                val actual = if (request["op"]?.jsonPrimitive?.content == "release" && rejectRelease) {
                    rejectRelease = false
                    val code = if (spec.name == "py") "1 / 0" else "(() => { throw new Error('release rejected'); })()"
                    buildJsonObject {
                        put("op", "eval")
                        put("code", Base64.getEncoder().encodeToString(code.toByteArray()))
                    }
                } else request
                wrapper(actual, marker)
            })
            withRuntimeTrace(RuntimeTraceSink { event ->
                if (event is RuntimeDispatchStarted) operations[event.dispatchId] = event.operation
                if (event is RuntimeDispatchCompleted && operations[event.dispatchId] == "release") {
                    observed += reference.isReleased
                }
            }) {
                runtime(controlled) {
                    val structured = StructuredRuntimeScope(coroutineContext, runtimeContext)
                    with(structured) {
                        val failure = assertFailsWith<RuntimeCallException> { store(listOf(7)) { reference = it } }
                        assertEquals(1, failure.execution.exitCode)
                        assertFalse(reference.isReleased)
                        assertEquals(listOf(7), read<List<Int>>(reference))
                        reference.releaseInternal()
                        assertTrue(reference.isReleased)
                        assertEquals(listOf(false, true), observed)
                        assertEquals(42, eval<Int>("42"))
                    }
                }
            }
        }
    }

    @Test
    fun `a completed release invalidates its handle before observers see completion`(): Unit = runBlocking {
        lateinit var reference: RuntimeRef
        val operations = mutableMapOf<Pair<String, Long>, String>()
        val observed = mutableMapOf<String, Boolean>()
        withRuntimeTrace(RuntimeTraceSink { event ->
            if (event is RuntimeDispatchStarted) operations[event.sessionId to event.dispatchId] = event.operation
            if (event is RuntimeDispatchCompleted && operations[event.sessionId to event.dispatchId] == "release") {
                observed[event.sessionId] = reference.isReleased
            }
        }) {
            eachRuntime {
                store(listOf(7)) { reference = it }
                assertEquals(true, observed[runtime.sessionId])
                assertTrue(reference.isReleased)
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `reference use queued by a release observer fails locally without a foreign request`(): Unit = runBlocking {
        lateinit var reference: RuntimeRef
        lateinit var scope: StructuredRuntimeScope
        lateinit var queued: Deferred<Throwable?>
        val operations = mutableMapOf<Pair<String, Long>, String>()
        val gets = mutableListOf<Long>()
        withRuntimeTrace(RuntimeTraceSink { event ->
            if (event is RuntimeDispatchStarted) {
                operations[event.sessionId to event.dispatchId] = event.operation
                if (event.operation == "get") gets += event.dispatchId
            }
            if (event is RuntimeDispatchCompleted && operations[event.sessionId to event.dispatchId] == "release") {
                queued = scope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { scope.readJson(reference) }.exceptionOrNull()
                }
            }
        }) {
            eachRuntime {
                scope = this
                store(listOf(7)) { reference = it }
                val failure = queued.await()
                assertIs<IllegalStateException>(failure)
                assertFalse(failure is RuntimeCallException, "Released use must fail before foreign dispatch")
                assertTrue(gets.isEmpty(), "The interpreter must not receive a get after release")
                assertTrue(reference.isReleased)
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `explicit child scopes finish reference work before each reference verb releases`(): Unit = runBlocking {
        eachRuntime {
            for (verb in ReferenceVerb.entries) {
                lateinit var reference: RuntimeRef
                val value = CompletableDeferred<List<Int>>()
                withReference(verb) { retained ->
                    reference = retained
                    coroutineScope {
                        val admitted = CompletableDeferred<Unit>()
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            admitted.await()
                            value.complete(read<List<Int>>(retained))
                        }
                        admitted.complete(Unit)
                    }
                    assertEquals(listOf(7), value.await())
                    assertFalse(retained.isReleased)
                }
                assertTrue(reference.isReleased)
                assertFailsWith<IllegalStateException> { readJson(reference) }
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `an outer runtime child cannot use a reference after its lexical block returns`(): Unit = runBlocking {
        eachRuntime {
            val released = CompletableDeferred<Unit>()
            val observation = CompletableDeferred<Throwable?>()
            hold("[7]") { reference ->
                launch {
                    released.await()
                    observation.complete(runCatching { readJson(reference) }.exceptionOrNull())
                }
            }
            released.complete(Unit)
            assertIs<IllegalStateException>(observation.await())
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `a reference request already queued completes before scoped release`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val admitted = CompletableDeferred<Unit>()
                lateinit var reference: RuntimeRef
                val queued = store(listOf(7)) { retained ->
                    reference = retained
                    async {
                        evaluateJson(
                            pauseExpression(150),
                            output = { if (it.text == "admitted") admitted.complete(Unit) },
                        )
                    }
                    admitted.await()
                    async(start = CoroutineStart.UNDISPATCHED) { read<List<Int>>(retained) }
                }
                assertEquals(listOf(7), queued.await())
                assertTrue(reference.isReleased)
                val operations = trace.events.filterIsInstance<RuntimeDispatchStarted>()
                    .filter { it.sessionId == runtime.sessionId }.map { it.operation }
                assertEquals(listOf("store", "eval", "get", "release"), operations)
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `cancelling an active reference operation invalidates each scoped reference`(): Unit = runBlocking {
        for (verb in ReferenceVerb.entries) {
            eachRuntime {
                val admitted = CompletableDeferred<Unit>()
                lateinit var reference: RuntimeRef
                val active = launch {
                    withReference(verb) { retained ->
                        reference = retained
                        evaluateJson(
                            pauseExpression(30_000),
                            output = { if (it.text == "admitted") admitted.complete(Unit) },
                        )
                    }
                }
                admitted.await()
                active.cancelAndJoin()
                assertTrue(reference.isReleased)
                assertFailsWith<IllegalStateException> { readJson(reference) }
                assertFailsWith<IllegalStateException> { evaluateJson("items", "items" to reference) }
            }
        }
    }

    @Test
    fun `reference cleanup preserves the original block failure for each verb`(): Unit = runBlocking {
        eachRuntime {
            for (verb in ReferenceVerb.entries) {
                lateinit var reference: RuntimeRef
                val original = IllegalArgumentException("body failure")
                val thrown = assertFailsWith<IllegalArgumentException> {
                    withReference(verb) { retained ->
                        reference = retained
                        throw original
                    }
                }
                assertSame(original, thrown)
                assertTrue(reference.isReleased)
                assertFailsWith<IllegalStateException> { readJson(reference) }
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    private enum class ReferenceVerb { Hold, Store, StoreJson, Copy }

    @Test
    fun `the same body and release failure is never self suppressed`(): Unit = runBlocking {
        assertReleaseFailure(sameFailure = true, failingBody = true)
    }

    @Test
    fun `a distinct release failure is suppressed on the original body failure`(): Unit = runBlocking {
        assertReleaseFailure(sameFailure = false, failingBody = true)
    }

    @Test
    fun `a release failure without a body failure remains observable`(): Unit = runBlocking {
        assertReleaseFailure(sameFailure = false, failingBody = false)
    }

    private suspend fun assertReleaseFailure(sameFailure: Boolean, failingBody: Boolean) {
        for (verb in ReferenceVerb.entries) {
            // Each release failure poisons its session; use fresh real interpreters.
            var releaseFailure: ReleaseFailure? = null
            var releases = 0
            withRuntimeTrace(RuntimeTraceSink { event ->
                if (event is RuntimeDispatchStarted && event.operation == "release") {
                    releases++
                    throw checkNotNull(releaseFailure)
                }
            }) {
                eachRuntime {
                    val original = ReleaseFailure("body")
                    val cleanup = if (sameFailure) original else ReleaseFailure("release")
                    releaseFailure = cleanup
                    releases = 0
                    lateinit var reference: RuntimeRef
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val failure = runCatching {
                        withReference(verb) { retained ->
                            reference = retained
                            if (failingBody) throw original
                        }
                    }.exceptionOrNull()
                    assertSame(if (failingBody) original else cleanup, failure)
                    assertEquals(1, releases)
                    assertEquals(
                        if (failingBody && !sameFailure) listOf(cleanup) else emptyList(),
                        failure!!.suppressed.toList(),
                    )
                    assertTrue(reference.isReleased)
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive, "Release failure must clean up before the outer runtime scope closes")
                    assertFailsWith<IllegalStateException> { readJson(reference) }
                }
            }
        }
    }

    // An extra field prevents coroutine stack recovery from copying the failure.
    private class ReleaseFailure(val phase: String) : Error(phase)

    private suspend fun StructuredRuntimeScope.withReference(verb: ReferenceVerb, block: suspend (RuntimeRef) -> Unit) {
        when (verb) {
            ReferenceVerb.Hold -> hold("[7]", block = block)
            ReferenceVerb.Store -> store(listOf(7), block = block)
            ReferenceVerb.StoreJson -> storeJson(buildJsonArray { add(7) }, block = block)
            ReferenceVerb.Copy -> store(listOf(7)) { source -> copy(source, block = block) }
        }
    }

    private fun StructuredRuntimeScope.pauseExpression(milliseconds: Int): String =
        if (runtime.name == "py") {
            "(print('admitted', flush=True), __import__('time').sleep(${milliseconds / 1000.0}))[1]"
        } else {
            "(console.log('admitted'), await new Promise(resolve => setTimeout(resolve, $milliseconds)), null)"
        }

    private suspend fun eachRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }
}
