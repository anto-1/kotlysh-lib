package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(30)
class RuntimeCallDiagnosticBoundaryTest {
    @Test
    fun `large Python call diagnostics preserve the full execution and reusable foreign session`(): Unit = runBlocking {
        verifyNativeError(RuntimeSpecs.Python)
    }

    @Test
    fun `large Node call diagnostics preserve the full execution and reusable foreign session`(): Unit = runBlocking {
        verifyNativeError(RuntimeSpecs.Node)
    }

    private suspend fun verifyNativeError(spec: RuntimeSpec) {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 16_000_000)) {
            runtime(spec) {
                val scope = StructuredRuntimeScope(coroutineContext, runtimeContext)
                val expression = if (spec == RuntimeSpecs.Python) {
                    "(_ for _ in ()).throw(ValueError('x' * 7000000))"
                } else {
                    "Promise.reject(new Error('x'.repeat(7000000)))"
                }
                val failure = assertFailsWith<RuntimeCallException> { scope.evaluateJson(expression) }
                val diagnostic = assertNotNull(failure.message)
                assertTrue(diagnostic.length <= 4_096, "Diagnostic length: ${diagnostic.length}")
                assertTrue(diagnostic.endsWith("…"))
                val response = assertNotNull(failure.execution.response)
                val detail = response.getValue("message").jsonPrimitive.content
                assertEquals(7_000_000, detail.length)
                assertEquals('x', detail.first())
                assertEquals('x', detail.last())
                assertSame(detail, failure.errorMessage)
                assertEquals(if (spec == RuntimeSpecs.Python) "ValueError" else "Error", failure.errorType)
                assertEquals(1, failure.execution.exitCode)
                assertFalse(failure.execution.outputLimited)
                assertTrue(failure.execution.stderr.any { it.length >= 7_000_000 })
                assertEquals("42", scope.evaluateJson("42").value.toString())
                assertTrue(runtime.isOpen)
            }
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    @Test
    fun `the complete call wrapper is bounded even when the runtime name fills the diagnostic`() {
        val name = "runtime:" + "x".repeat(8_100_000)
        val execution = RuntimeResult(name, emptyList(), emptyList(), 1, response = error("type", "detail"))
        val failure = RuntimeCallException(execution)
        assertTrue(failure.message!!.length <= 4_096)
        assertEquals(name.take(4_095) + "…", failure.message)
        assertSame(execution, failure.execution)
        assertSame(name, execution.runtime)
    }

    @Test
    fun `call diagnostic clipping preserves Unicode pairs lone surrogates and complete short text`() {
        val prefix = "py request failed: ValueError: "
        for ((message, expected) in listOf(
            "x".repeat(4_094 - prefix.length) + "😀tail" to prefix + "x".repeat(4_094 - prefix.length) + "…",
            "x".repeat(4_093 - prefix.length) + "😀tail" to prefix + "x".repeat(4_093 - prefix.length) + "😀…",
            "x".repeat(4_094 - prefix.length) + "😀" to prefix + "x".repeat(4_094 - prefix.length) + "😀",
            "x".repeat(4_094 - prefix.length) + "\uD800suffix" to prefix + "x".repeat(4_094 - prefix.length) + "\uD800…",
            "<html>literal</html>\r\n\u0000 lone:\uD800 / \uDC00" to prefix + "<html>literal</html>\r\n\u0000 lone:\uD800 / \uDC00",
        )) {
            val execution = RuntimeResult("py", emptyList(), emptyList(), 1, response = error("ValueError", message))
            val failure = RuntimeCallException(execution)
            assertTrue(failure.message!!.length <= 4_096)
            assertEquals(expected, failure.message)
            assertSame(execution, failure.execution)
            assertEquals(message, execution.response!!["message"]!!.jsonPrimitive.content)
            assertSame(message, failure.errorMessage)
            assertEquals("ValueError", failure.errorType)
        }
    }

    @Test
    fun `stderr fallback reads only the requested prefix and preserves pair boundaries`() {
        val data = "x".repeat(999) + "😀tail"
        var reads = 0
        val stderr = object : AbstractList<String>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): String {
                reads++
                check(index == 0) { "Unneeded stderr line was read" }
                return data
            }
        }
        val execution = RuntimeResult("py", emptyList(), stderr, 1)
        val failure = RuntimeCallException(execution)
        assertEquals("py request failed: " + "x".repeat(999), failure.message)
        assertEquals(1, reads)
        assertSame(stderr, failure.execution.stderr)
        assertNull(failure.errorType)
        assertNull(failure.errorMessage)
        assertEquals("py request failed: first\nsecond", RuntimeCallException(
            RuntimeResult("py", emptyList(), listOf("first", "second"), 1),
        ).message)
    }

    @Test
    fun `short call diagnostics retain status order and an explicit empty foreign message`() {
        val execution = RuntimeResult("py", listOf("out"), listOf("fallback"), 1,
            timedOut = true, idleTimedOut = true, outputLimited = true, processExited = true,
            response = error("Error", "detail"))
        assertEquals("py request failed after timeout after idle timeout after exceeding the output limit because the interpreter exited: Error: detail", RuntimeCallException(execution).message)
        assertEquals("py request failed: Error: ", RuntimeCallException(execution.copy(
            timedOut = false, idleTimedOut = false, outputLimited = false, processExited = false,
            response = error("Error", ""),
        )).message)
    }

    private fun error(type: String, message: String) = buildJsonObject {
        put("version", 1)
        put("kind", "error")
        put("type", type)
        put("message", message)
    }
}
