package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeLineEndingBoundaryTest {
    @Test
    fun `split CRLF at the exact log budget keeps Shell Python and Node reusable`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            for (stream in RuntimeStream.entries) {
                val trace = RuntimeTraceBuffer()
                withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5)) {
                    runtime(spec) {
                        repeat(2) {
                            val result = script(spec, stream)(timeout = 5.seconds)
                            assertTrue(result.ok, "${spec.name}/$stream: $result")
                            assertFalse(result.outputLimited)
                            assertEquals(if (stream == RuntimeStream.Stdout) listOf("ready") else emptyList(), result.stdout)
                            assertEquals(if (stream == RuntimeStream.Stderr) listOf("ready") else emptyList(), result.stderr)
                            assertTrue(runtime.isOpen)
                        }
                    }
                }
                val output = trace.events.filterIsInstance<RuntimeDispatchOutput>()
                assertEquals(listOf("ready", "ready"), output.map { it.text })
                assertTrue(output.all { it.stream == stream })
                assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
            }
        }
    }

    @Test
    fun `empty CRLF line is allowed after the character budget is exhausted`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5, maxOutputLinesPerDispatch = 2)) {
            sh {
                val result = "printf 'ready\\n\\r'; sleep 0.1; printf '\\n'"()
                assertTrue(result.ok)
                assertFalse(result.outputLimited)
                assertEquals(listOf("ready", ""), result.stdout)
                assertTrue(runtime.isOpen)
            }
        }
        assertEquals(listOf("ready", ""), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
    }

    @Test
    fun `EOF resolves a delayed trailing CR without exceeding the normalized budget`(): Unit = runBlocking {
        withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5)) {
            sh {
                val result = "printf 'ready\\r'; sleep 0.1; exit 9"()
                assertEquals(listOf("ready"), result.stdout)
                assertEquals(9, result.exitCode)
                assertTrue(result.processExited)
                assertFalse(result.outputLimited)
                assertFalse(runtime.isOpen)
            }
        }
    }

    @Test
    fun `CR followed by data cannot bypass the character budget`(): Unit = runBlocking {
        for (suffix in listOf("x", "\\r")) {
            withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5)) {
                sh {
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val result = "printf 'ready\\r'; sleep 0.1; printf '$suffix'; sleep 30"()
                    assertTrue(result.outputLimited)
                    assertFalse(result.ok)
                    assertEquals(listOf("ready"), result.stdout)
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive)
                    assertFailsWith<IllegalStateException> { "next"() }
                }
            }
        }
    }

    @Test
    fun `a trailing CR still counts as data when a deadline interrupts its line`(): Unit = runBlocking {
        withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5)) {
            sh {
                val result = "printf 'ready\\r'; sleep 30"(timeout = 300.milliseconds, idleTimeout = 10.seconds)
                assertTrue(result.timedOut)
                assertTrue(result.outputLimited)
                assertEquals(listOf("ready"), result.stdout)
                assertFalse(runtime.isOpen)
            }
        }
    }

    private fun script(spec: RuntimeSpec, stream: RuntimeStream): String {
        val name = stream.name.lowercase()
        return when (spec) {
            RuntimeSpecs.Bash -> {
                val redirect = if (stream == RuntimeStream.Stderr) " >&2" else ""
                "printf 'ready\\r'$redirect; sleep 0.1; printf '\\n'$redirect"
            }
            RuntimeSpecs.Python -> "import sys,time; sys.$name.write('ready\\r'); sys.$name.flush(); time.sleep(0.1); sys.$name.write('\\n'); sys.$name.flush()"
            RuntimeSpecs.Node -> "(async () => { process.$name.write('ready\\r'); await new Promise(resolve => setTimeout(resolve, 100)); process.$name.write('\\n'); })()"
            else -> error("Unknown fixture runtime")
        }
    }
}
