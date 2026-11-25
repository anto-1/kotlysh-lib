package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*

@Timeout(60)
class StructuredReleaseValidationTest {
    private enum class Verb { Hold, Store, StoreJson, Copy }

    @Test
    fun `a malformed release cannot turn a successful reference body into success`(): Unit = runBlocking {
        forEachRuntimeAndVerb(malformedRelease = true) { scope, verb, trace ->
            val process = ProcessHandle.of(checkNotNull(scope.runtime.processId)).orElseThrow()
            lateinit var retained: RuntimeRef
            var bodyCompleted = false
            val failure = assertFailsWith<IllegalStateException> {
                scope.withReference(verb) {
                    retained = it
                    assertEquals(42, scope.eval<Int>(scope.bodyExpression()))
                    bodyCompleted = true
                    84
                }
            }
            assertTrue(bodyCompleted)
            assertTrue(failure.message.orEmpty().contains("Expected unit response"), failure.message)
            assertStopped(scope, retained, process)
            assertReleaseFailed(trace)
            assertEquals(listOf("body"), trace.events.filterIsInstance<RuntimeDispatchOutput>()
                .filter { it.stream == RuntimeStream.Stdout }.map { it.text })
            assertEquals(listOf("diagnostic"), trace.events.filterIsInstance<RuntimeDispatchOutput>()
                .filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        }
    }

    @Test
    fun `malformed releases also propagate without tracing`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            runtime(controlled(spec)) {
                val scope = StructuredRuntimeScope(coroutineContext, runtimeContext)
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                lateinit var reference: RuntimeRef
                val failure = assertFailsWith<IllegalStateException> {
                    scope.store(listOf(7)) { reference = it; 84 }
                }
                assertTrue(failure.message.orEmpty().contains("Expected unit response"), failure.message)
                assertStopped(scope, reference, process)
            }
        }
    }

    @Test
    fun `a malformed release is suppressed on the original reference body failure`(): Unit = runBlocking {
        forEachRuntimeAndVerb(malformedRelease = true) { scope, verb, trace ->
            val original = BodyFailure("body")
            val process = ProcessHandle.of(checkNotNull(scope.runtime.processId)).orElseThrow()
            lateinit var reference: RuntimeRef
            val failure = assertFailsWith<BodyFailure> {
                scope.withReference(verb) { reference = it; throw original }
            }
            assertSame(original, failure)
            val cleanup = assertIs<IllegalStateException>(failure.suppressed.single())
            assertTrue(cleanup.message.orEmpty().contains("Expected unit response"), cleanup.message)
            assertStopped(scope, reference, process)
            assertReleaseFailed(trace)
        }
    }

    @Test
    fun `cleanup skips references whose owner was already closed by the body`(): Unit = runBlocking {
        forEachRuntimeAndVerb(malformedRelease = true) { scope, verb, trace ->
            val process = ProcessHandle.of(checkNotNull(scope.runtime.processId)).orElseThrow()
            lateinit var reference: RuntimeRef
            assertEquals(84, scope.withReference(verb) {
                reference = it
                scope.runtime.close()
                84
            })
            assertStopped(scope, reference, process)
            assertTrue(trace.events.filterIsInstance<RuntimeDispatchStarted>().none { it.operation == "release" })
        }
    }

    @Test
    fun `healthy releases preserve return values and reusable sessions`(): Unit = runBlocking {
        forEachRuntimeAndVerb(malformedRelease = false) { scope, verb, trace ->
            lateinit var reference: RuntimeRef
            assertEquals(84, scope.withReference(verb) {
                reference = it
                assertEquals(listOf(7), scope.read<List<Int>>(it))
                84
            })
            assertTrue(reference.isReleased)
            assertTrue(scope.runtime.isOpen)
            assertEquals(42, scope.eval<Int>("42"))
            assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        }
    }

    private fun controlled(spec: RuntimeSpec): RuntimeSpec {
        val wrapper = checkNotNull(spec.requestWrapper)
        val code = Base64.getEncoder().encodeToString("42".toByteArray())
        return spec.copy(requestWrapper = { request, marker ->
            val actual = if (request["op"]?.jsonPrimitive?.content == "release") {
                buildJsonObject { put("op", "eval"); put("code", code) }
            } else request
            wrapper(actual, marker)
        })
    }

    private suspend fun forEachRuntimeAndVerb(
        malformedRelease: Boolean,
        block: suspend (StructuredRuntimeScope, Verb, RuntimeTraceBuffer) -> Unit,
    ) {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            for (verb in Verb.entries) {
                val trace = RuntimeTraceBuffer()
                withRuntimeTrace(trace) {
                    runtime(if (malformedRelease) controlled(spec) else spec) {
                        block(StructuredRuntimeScope(coroutineContext, runtimeContext), verb, trace)
                    }
                }
            }
        }
    }

    private suspend fun StructuredRuntimeScope.withReference(verb: Verb, block: suspend (RuntimeRef) -> Int): Int =
        when (verb) {
            Verb.Hold -> hold("[7]", block = block)
            Verb.Store -> store(listOf(7), block = block)
            Verb.StoreJson -> storeJson(kotlinx.serialization.json.Json.parseToJsonElement("[7]"), block = block)
            Verb.Copy -> store(listOf(7)) { source -> copy(source, block = block) }
        }

    private fun StructuredRuntimeScope.bodyExpression(): String =
        if (runtime.name == "py") "(print('body'), print('diagnostic', file=__import__('sys').stderr), 42)[2]"
        else "(console.log('body'), console.error('diagnostic'), 42)"

    private fun assertStopped(scope: StructuredRuntimeScope, reference: RuntimeRef, process: ProcessHandle) {
        assertFalse(process.isAlive, "Owned interpreter cleanup must precede propagation")
        assertFalse(scope.runtime.isOpen)
        assertTrue(reference.isReleased)
    }

    private fun assertReleaseFailed(trace: RuntimeTraceBuffer) {
        val release = trace.events.filterIsInstance<RuntimeDispatchStarted>().single { it.operation == "release" }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().none { it.dispatchId == release.dispatchId })
        val failure = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
        assertEquals(release.dispatchId, failure.dispatchId)
        assertEquals("java.lang.IllegalStateException", failure.errorType)
        assertTrue(failure.message.orEmpty().contains("Expected unit response"))
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    // An additional field prevents coroutine stack recovery from replacing the original.
    private class BodyFailure(val phase: String) : IllegalArgumentException(phase)
}
