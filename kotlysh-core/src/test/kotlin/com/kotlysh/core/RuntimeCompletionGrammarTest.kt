package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeCompletionGrammarTest {
    @Test
    fun `payload delimiters without status digits remain logs before a valid completion`(): Unit = runBlocking {
        for (status in listOf("", "+", "-")) {
            var marker = ""
            runtime(RuntimeSpec("status-grammar", RuntimeSpecs.Bash.command, wrap = { _, token ->
                marker = token
                frames(token, status, "7")
            })) {
                repeat(2) {
                    val result = "ignored"(timeout = 2.seconds)
                    assertEquals(7, result.exitCode, status)
                    assertFalse(result.ok)
                    assertEquals(listOf("$marker:$status:ordinary"), result.stdout)
                    assertEquals(listOf("$marker:$status:ordinary"), result.stderr)
                    assertTrue(runtime.isOpen)
                }
            }
        }
    }

    @Test
    fun `malformed status payload text at interpreter exit is retained in both streams`(): Unit = runBlocking {
        for (status in listOf("", "+", "-")) {
            var marker = ""
            runtime(RuntimeSpec("malformed-status", RuntimeSpecs.Bash.command, wrap = { _, token ->
                marker = token
                "printf '%s' '$token:$status:ordinary'; printf '%s' '$token:$status:diagnostic' >&2; exit 7\n"
            })) {
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val result = "ignored"(timeout = 2.seconds)
                assertTrue(result.processExited)
                assertEquals(listOf("$marker:$status:ordinary"), result.stdout)
                assertEquals(listOf("$marker:$status:diagnostic"), result.stderr)
                assertFalse(process.isAlive)
                assertFalse(runtime.isOpen)
            }
        }
    }

    @Test
    fun `unfinished payload delimiters without status digits count toward the output limit`(): Unit = runBlocking {
        for (status in listOf("", "+", "-")) {
            for (stream in RuntimeStream.entries) {
                val redirection = if (stream == RuntimeStream.Stderr) " >&2" else ""
                val spec = RuntimeSpec("status-grammar", RuntimeSpecs.Bash.command, wrap = { _, marker ->
                    "printf '%s' '$marker:$status:${"x".repeat(2_000)}'$redirection; exec sleep 30\n"
                })
                val trace = RuntimeTraceBuffer()
                withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 64)) {
                    runtime(spec) {
                        val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        val result = "ignored"(timeout = 1.seconds)
                        assertTrue(result.outputLimited, "$stream: $result")
                        assertFalse(result.timedOut)
                        assertFalse(process.isAlive)
                        assertFalse(runtime.isOpen)
                        val output = result.stdout + result.stderr
                        assertTrue(output.isNotEmpty())
                        assertTrue(output.sumOf { it.length } <= 64)
                    }
                }
                assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().sumOf { it.text.length } <= 64)
                assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().single().outputLimited)
                assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
            }
        }
    }

    @Test
    fun `signs leading zeros Unicode decimal digits and the full integer range remain valid`(): Unit = runBlocking {
        for ((status, expected) in listOf("+0000" to 0, "-0001" to -1, "2147483647" to Int.MAX_VALUE, "-2147483648" to Int.MIN_VALUE,
            "٠" to 0, "١٢" to 12, "１２" to 12, "+०" to 0, "-١" to -1, "0٠" to 0)) {
            runtime(RuntimeSpec("status-grammar", RuntimeSpecs.Bash.command, wrap = { _, marker ->
                "printf '%s\\n' 'visible'; printf '%s\\n' '$marker:$status'; printf '%s\\n' '$marker:$status' >&2\n"
            })) {
                repeat(2) {
                    val result = "ignored"(timeout = 2.seconds)
                    assertEquals(expected, result.exitCode)
                    assertEquals(expected == 0, result.ok)
                    assertEquals(listOf("visible"), result.stdout)
                    assertTrue(result.stderr.isEmpty())
                    assertTrue(runtime.isOpen)
                }
            }
        }
    }

    private fun frames(marker: String, lookalike: String, status: String) =
        "printf '%s\\n' '$marker:$lookalike:ordinary'; printf '%s\\n' '$marker:$lookalike:ordinary' >&2; " +
            "printf '%s\\n' '$marker:$status'; printf '%s\\n' '$marker:$status' >&2\n"
}
