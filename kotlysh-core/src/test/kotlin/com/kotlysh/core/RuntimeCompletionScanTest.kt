package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeCompletionScanTest {
    @Test
    fun `incremental status parsing preserves integer grammar and incomplete log exclusions`() {
        val marker = "__KOTLYSH_test__:"
        for (status in listOf("", "+", "-", "0", "+0", "-0", "00042", "2147483647", "-2147483648",
            "2147483648", "-2147483649", "999999999999", " 0", "0 ", "--1", "+-1", "0x1", "1_0",
            "١٢", "１２", "\r", "bad${marker}0", "0${marker}-1", "${marker}+")) {
            for (suffix in listOf("", "\r", ":payload", ":payload\r")) {
                val line = "visible$marker$status$suffix"
                for (logs in listOf(false, true)) {
                    val scan = RuntimeCompletionFrameScanner(marker, allowIncompleteStatus = logs)
                    val text = StringBuilder()
                    for (character in line) {
                        text.append(character)
                        val candidate = text.toString().removeSuffix("\r")
                        var start = candidate.indexOf(marker)
                        var expected: Int? = null
                        while (start >= 0) {
                            val from = start + marker.length
                            val delimiter = candidate.indexOf(':', from)
                            val value = candidate.substring(from, if (delimiter < 0) candidate.length else delimiter)
                            val eligible = if (logs) value.toIntOrNull() != null || delimiter < 0 && value in listOf("", "+", "-")
                                else delimiter >= 0 && value.toIntOrNull() != null
                            if (eligible) { expected = start; break }
                            start = candidate.indexOf(marker, from)
                        }
                        assertEquals(expected, scan.scan(text), "logs=$logs, status=$status, text=$candidate")
                    }
                    scan.reset()
                    assertEquals(if (logs) 0 else null, scan.scan("${marker}0"))
                    assertEquals(0, scan.scan("${marker}0:payload"))
                    assertEquals(marker.length + 2, scan.payloadStart)
                }
            }
        }
    }

    @Test
    fun `unfinished zero filled and invalid statuses require linear character reads`() {
        val marker = "__KOTLYSH_test__:"
        for (status in listOf("0".repeat(200_000), "invalid" + "0".repeat(200_000))) {
            for (logs in listOf(false, true)) {
                val scan = RuntimeCompletionFrameScanner(marker, allowIncompleteStatus = logs)
                val text = CountingText()
                val line = "visible$marker$status"
                for (chunk in line.chunked(17)) {
                    text.content.append(chunk)
                    scan.scan(text)
                }
                text.content.append("${marker}0:payload")
                assertEquals("visible".length + marker.length + status.length, scan.scan(text))
                assertTrue(text.reads < line.length * 8L, "Examined ${text.reads} characters of ${line.length}")
            }
        }
    }

    @Test
    fun `long leading zero statuses complete without consuming the log budget`(): Unit = runBlocking {
        verifyLongStatus(valid = true)
    }

    @Test
    fun `long invalid statuses remain logs and a following marker still completes`(): Unit = runBlocking {
        verifyLongStatus(valid = false)
    }

    @Test
    fun `untraced long statuses preserve complete structured responses and reusable sessions`(): Unit = runBlocking {
        runtime(structuredAdapter()) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            repeat(2) {
                val result = scope.evaluateJson("ignored")
                assertEquals(JsonPrimitive(42), result.value)
                assertEquals(listOf("visible"), result.execution.stdout)
                assertEquals(listOf("diagnostic"), result.execution.stderr)
                assertTrue(runtime.isOpen)
            }
        }
    }

    @Test
    fun `long statuses cannot bypass the independent payload byte budget`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = envelope.toByteArray().size - 1)) {
            withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 17)) {
                runtime(structuredAdapter()) {
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                    val failure = assertFailsWith<IllegalStateException> { scope.evaluateJson("ignored") }
                    assertTrue(failure.message.orEmpty().contains("payload byte limit"), failure.message)
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive)
                    assertFailsWith<IllegalStateException> { scope.evaluateJson("must not run") }
                }
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().all { it.text in setOf("visible", "diagnostic") })
    }

    private val envelope = """{"version":1,"kind":"value","value":42}"""

    private fun structuredAdapter(): RuntimeSpec {
        val payload = Base64.getEncoder().encodeToString(envelope.toByteArray())
        val frame: (String) -> String = { marker ->
            "python3 -c 'import sys; sys.stdout.write(\"visible$marker:\" + \"0\" * 7000000 + \":$payload\\n\"); " +
                "sys.stderr.write(\"diagnostic$marker:0\\n\")'\n"
        }
        return RuntimeSpec("long-structured-status", RuntimeSpecs.Bash.command,
            wrap = { _, marker -> frame(marker) }, requestWrapper = { _, marker -> frame(marker) })
    }

    private suspend fun verifyLongStatus(valid: Boolean) {
        val size = 7_000_000
        val trace = RuntimeTraceBuffer()
        val spec = RuntimeSpec("long-status", RuntimeSpecs.Bash.command) { _, marker ->
            val status = if (valid) "\"0\" * $size" else "\"invalid\" + \"0\" * $size"
            val following = if (valid) "" else "$marker:0"
            "python3 -c 'import sys; " +
                "sys.stdout.write(\"visible$marker:\" + ($status) + \"$following\\n\"); " +
                "sys.stderr.write(\"diagnostic$marker:0\\n\")'\n"
        }
        withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = if (valid) 17 else size + 1_000)) {
            runtime(spec) {
                repeat(2) {
                    val result = "ignored"(timeout = 15.seconds, idleTimeout = 10.seconds)
                    assertTrue(result.ok, "exit=${result.exitCode}, timeout=${result.timedOut}, limited=${result.outputLimited}")
                    assertFalse(result.outputLimited)
                    assertEquals(listOf("diagnostic"), result.stderr)
                    val output = result.stdout.single()
                    if (valid) assertEquals("visible", output)
                    else {
                        assertTrue(output.startsWith("visible__KOTLYSH_"))
                        assertTrue(output.endsWith(":invalid" + "0".repeat(size)))
                    }
                    assertTrue(runtime.isOpen)
                }
            }
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private class CountingText : CharSequence {
        val content = StringBuilder()
        var reads = 0L
        override val length: Int get() = content.length
        override fun get(index: Int): Char { reads++; return content[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("The scanner must not copy a status")
    }
}
