package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RubyRuntimeTest {
    @Test
    fun `Ruby retains local variables and methods without interpreter echo`(): Unit = runBlocking {
        ruby {
            assertTrue("values = [2,3,5]; def double(value); value * 2; end"().ok)
            val result = "STDOUT.write(\"answer=#{double(values.sum)}\"); STDERR.write('diagnostic')"()
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf("answer=20"), result.stdout)
            assertEquals(listOf("diagnostic"), result.stderr)
        }
    }

    @Test
    fun `Ruby errors have failed statuses exact diagnostics and a reusable session`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            ruby {
                for ((source, type) in listOf(
                    "raise RuntimeError, 'café 😀'" to "RuntimeError",
                    "def broken(" to "SyntaxError",
                    "exit(7)" to "SystemExit",
                    "raise Interrupt, 'interrupted'" to "Interrupt",
                )) {
                    val result = source(timeout = 5.seconds)
                    assertFalse(result.ok, "Ruby failure became success: $source")
                    assertEquals(1, result.exitCode)
                    assertFalse(result.processExited)
                    assertTrue(runtime.isOpen)
                    assertTrue(result.stderr.any { it.contains(type) }, result.stderrText)
                    assertEquals(listOf("42"), "puts 42"().stdout)
                }
            }
        }
        val failures = trace.events.filterIsInstance<RuntimeDispatchCompleted>().filter { it.exitCode == 1 }
        assertEquals(listOf("RuntimeError", "SyntaxError", "SystemExit", "Interrupt"), failures.map { it.errorType })
        assertEquals("café 😀", failures.first().message)
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `Ruby user locals cannot overwrite driver envelope variables`(): Unit = runBlocking {
        ruby {
            assertTrue("marker='user'; message='mine'; status=9; source=42; response={'text'=>'data'}"().ok)
            assertEquals(listOf("user|mine|9|42|data"), "puts [marker,message,status,source,response['text']].join('|')"().stdout)
        }
    }

    @Test
    fun `Ruby preserves Unicode and prompt-like output on separate streams`(): Unit = runBlocking {
        ruby {
            val result = "STDOUT.write(\"café 😀\\nirb(main):001:0>\"); STDERR.write('日本語 diagnostic')"()
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf("café 😀", "irb(main):001:0>"), result.stdout)
            assertEquals(listOf("日本語 diagnostic"), result.stderr)
        }
    }

    @Test
    fun `Ruby failing diagnostic formatters still return failure and permit reuse`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            ruby {
                val result = """
                    class BrokenMessage < StandardError
                      def message; raise 'formatting failed'; end
                      def full_message(**options); raise 'trace formatting failed'; end
                    end
                    raise BrokenMessage
                """()
                assertFalse(result.ok)
                assertEquals(1, result.exitCode)
                assertTrue(result.stderrText.contains("<unprintable error>"))
                assertEquals(listOf("ready"), "puts 'ready'"().stdout)
            }
        }
        val failed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.exitCode == 1 }
        assertEquals("BrokenMessage", failed.errorType)
        assertEquals("<unprintable error>", failed.message)
    }

    @Test
    fun `Ruby partial output is unbuffered before completion`(): Unit = runBlocking {
        ruby {
            val result = "10.times { STDOUT.write('beat'); sleep 0.1 }"(timeout = 3.seconds, idleTimeout = 500.milliseconds)
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf("beat".repeat(10)), result.stdout)
        }
    }

    @Test
    fun `Ruby hard exit cannot become success and closes its owned interpreter`(): Unit = runBlocking {
        var owned: ProcessHandle? = null
        ruby {
            owned = ProcessHandle.of(runtime.processId!!).orElseThrow()
            val result = "exit!(9)"(timeout = 5.seconds)
            assertFalse(result.ok)
            assertTrue(result.processExited)
            assertEquals(9, result.exitCode)
            assertFalse(runtime.isOpen)
        }
        assertFalse(owned!!.isAlive)
    }

    @Test
    fun `Ruby non UTF-8 error messages cannot break completion framing`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            ruby {
                val result = """raise "\xFF".b"""()
                assertFalse(result.ok)
                assertEquals(1, result.exitCode)
                assertFalse(result.processExited)
                assertEquals(listOf("café 😀"), "puts 'café 😀'"().stdout)
            }
        }
        val failed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.exitCode == 1 }
        assertEquals("RuntimeError", failed.errorType)
        assertEquals("\uFFFD", failed.message)
    }

    @Test
    fun `Ruby deadline terminates its interpreter and observed child`(): Unit = runBlocking {
        val owned = mutableListOf<ProcessHandle>()
        ruby {
            owned += ProcessHandle.of(runtime.processId!!).orElseThrow()
            val result = "child = spawn('/bin/sleep', '30'); puts child; Process.wait(child)"(
                timeout = 1.seconds,
                output = { line -> owned += ProcessHandle.of(line.text.toLong()).orElseThrow() },
            )
            assertTrue(result.timedOut)
            assertFalse(result.ok)
            assertFalse(runtime.isOpen)
        }
        assertEquals(2, owned.size)
        assertTrue(owned.none { it.isAlive }, "An owned Ruby process survived its deadline")
    }

    @Test
    fun `Ruby cancellation terminates its interpreter and observed child`(): Unit = runBlocking {
        val ready = CompletableDeferred<List<ProcessHandle>>()
        val work = async {
            ruby {
                val interpreter = ProcessHandle.of(runtime.processId!!).orElseThrow()
                "child = spawn('/bin/sleep', '30'); puts child; Process.wait(child)"(
                    output = { line -> ready.complete(listOf(interpreter, ProcessHandle.of(line.text.toLong()).orElseThrow())) },
                )
            }
        }
        try {
            val owned = withTimeout(5.seconds) { ready.await() }
            work.cancelAndJoin()
            assertTrue(owned.none { it.isAlive }, "An owned Ruby process survived cancellation")
        } finally {
            work.cancelAndJoin()
        }
    }
}
