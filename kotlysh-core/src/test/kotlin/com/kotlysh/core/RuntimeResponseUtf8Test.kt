package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.nio.charset.MalformedInputException
import java.util.Base64
import kotlin.test.*

@Timeout(15)
class RuntimeResponseUtf8Test {
    @Test
    fun `malformed UTF8 inside a JSON response fails and poisons the interpreter`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(responseSpec(byteArrayOf(0xff.toByte()))) {
                val structured = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                assertFailsWith<MalformedInputException> { structured.evaluateJson("ignored") }
                assertFalse(runtime.isOpen)
                assertFailsWith<IllegalStateException> { "must-not-run"() }
            }
        }
        assertEquals(listOf("visible\uFFFD"), trace.events.filterIsInstance<RuntimeDispatchOutput>()
            .filter { it.stream == RuntimeStream.Stdout }.map { it.text })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `valid replacement character remains data while malformed user logs use replacement`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(responseSpec("\uFFFD".toByteArray(Charsets.UTF_8))) {
                val structured = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                repeat(2) {
                    val result = structured.evaluate<String>("ignored")
                    assertEquals("\uFFFD", result.value)
                    assertTrue(result.execution.ok)
                    assertEquals(listOf("visible\uFFFD"), result.execution.stdout)
                    assertEquals(listOf("diagnostic\uFFFD"), result.execution.stderr)
                    assertTrue(runtime.isOpen)
                }
            }
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private fun responseSpec(valueBytes: ByteArray): RuntimeSpec {
        val response = "{\"version\":1,\"kind\":\"value\",\"value\":\"".toByteArray(Charsets.UTF_8) +
            valueBytes + "\"}".toByteArray(Charsets.UTF_8)
        val payload = Base64.getEncoder().encodeToString(response)
        fun frame(marker: String): String =
            "printf 'visible\\377'; printf '%s\\n' '${marker}:0:$payload'; " +
                "printf 'diagnostic\\377' >&2; printf '%s\\n' '${marker}:0' >&2\n"
        return RuntimeSpec(
            "response-utf8-test",
            RuntimeSpecs.Bash.command,
            wrap = { _, marker -> frame(marker) },
            requestWrapper = { _, marker -> frame(marker) },
        )
    }
}
