package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

@Timeout(20)
class ExecOutputLimitsTest {
    @Test
    fun `exact budgets accept CR LF CRLF empty lines and final partial output`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 3, maxCharactersPerDispatch = 4)) {
            for (text in listOf("ab\\r\\n\\r\\ncd\\r", "ab\\n\\ncd", "ab\\r\\rcd")) {
                val result = exec(shell("printf '$text'"), timeout = null)
                assertTrue(result.ok, "$text: $result")
                assertFalse(result.outputLimited)
                assertEquals(listOf("ab", "", "cd"), result.stdout)
            }
        }
    }

    @Test
    fun `character cap preserves Unicode prefixes in unfinished stdout and stderr`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 2)) {
            for (stream in RuntimeStream.entries) {
                val buffer = RuntimeOutputBuffer()
                val redirect = if (stream == RuntimeStream.Stderr) " >&2" else ""
                val result = exec(shell("printf 'a😀z'$redirect; sleep 30"), timeout = 3.seconds, output = buffer)
                assertTrue(result.outputLimited)
                assertFalse(result.timedOut)
                assertFalse(result.ok)
                assertNull(result.exitCode)
                assertEquals(listOf("a"), if (stream == RuntimeStream.Stdout) result.stdout else result.stderr)
                assertEquals(listOf(RuntimeOutputLine("exec", stream, "a")), buffer.lines)
            }
        }
    }

    @Test
    fun `concurrent streams share a line budget and publish only retained output`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 10)) {
            val buffer = RuntimeOutputBuffer()
            val result = exec(shell("for ((i=0;i<100;i++)); do printf 'o\\n'; printf 'e\\n' >&2; done"), output = buffer)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertEquals(10, result.stdout.size + result.stderr.size)
            assertEquals(result.stdout, buffer.stdout)
            assertEquals(result.stderr, buffer.stderr)
        }
    }

    @Test
    fun `concurrent unfinished streams share the character budget`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 12)) {
            val result = exec(listOf("python3", "-u", "-c",
                "import sys, time; sys.stdout.write('o' * 10000); sys.stderr.write('e' * 10000); time.sleep(30)"),
                timeout = 3.seconds)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertEquals(12, result.stdout.sumOf { it.length } + result.stderr.sumOf { it.length })
        }
    }

    @Test
    fun `two partial streams exceed their shared character budget even when each fits`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 3)) {
            val result = exec(shell("printf ab; sleep 0.1; printf cd >&2; sleep 30"), timeout = 3.seconds)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertEquals(3, result.stdout.sumOf { it.length } + result.stderr.sumOf { it.length })
        }
    }

    @Test
    fun `two partial streams exceed a shared line budget before either newline`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1)) {
            val result = exec(shell("printf a; sleep 0.1; printf b >&2; sleep 30"), timeout = 3.seconds)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertEquals(listOf("a"), result.stdout)
            assertTrue(result.stderr.isEmpty())
        }
    }

    @Test
    fun `completed output enforces the remaining budget of a quiet partial stream`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 3)) {
            val result = exec(shell("printf a; sleep 0.1; printf 'bcd\\n' >&2; sleep 30"), timeout = 3.seconds)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertTrue(result.stdout.isEmpty())
            assertEquals(listOf("bcd"), result.stderr)
        }
    }

    @Test
    fun `an unfinished line stops promptly after an exhausted line budget without a deadline`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1)) {
            val result = exec(shell("printf 'accepted\\nexcess'; sleep 30"), timeout = null)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertEquals(listOf("accepted"), result.stdout)
        }
    }

    @Test
    fun `excess discovered after a deadline preserves both facts`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1)) {
            val result = exec(shell("printf 'accepted\\nexcess\\n'; sleep 30"), timeout = 100.milliseconds, output = {
                // Keep the reader in its first callback until cleanup stops the
                // process. The already-decoded next line is collected afterward.
                Thread.sleep(300)
            })
            assertTrue(result.timedOut)
            assertTrue(result.outputLimited)
            assertFalse(result.ok)
            assertNull(result.exitCode)
            assertEquals(listOf("accepted"), result.stdout)
        }
    }

    private fun shell(source: String) = listOf("/bin/bash", "--noprofile", "--norc", "-c", source)
}
