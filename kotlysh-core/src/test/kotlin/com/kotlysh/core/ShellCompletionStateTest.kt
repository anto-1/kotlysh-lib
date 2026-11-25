package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class ShellCompletionStateTest {
    @Test
    fun `Shell printf function persists without intercepting completion records`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val first = """printf() { builtin printf 'user-format:%s\n' "${'$'}*"; }; printf payload"""(timeout = 2.seconds)
                assertTrue(first.ok, first.stderrText)
                assertEquals(listOf("user-format:payload"), first.stdout)
                assertEquals(emptyList(), first.stderr)
                val failed = "printf again; printf diagnostic >&2; (exit 7)"(timeout = 2.seconds)
                assertEquals(7, failed.exitCode)
                assertFalse(failed.timedOut)
                assertFalse(failed.processExited)
                assertEquals(listOf("user-format:again"), failed.stdout)
                assertEquals(listOf("user-format:diagnostic"), failed.stderr)
                assertEquals(listOf("user-format:ready"), "printf ready"(timeout = 2.seconds).stdout)
            }
        }
        assertEquals(listOf(0, 7, 0), trace.events.filterIsInstance<RuntimeDispatchCompleted>().map { it.exitCode })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `Shell printf alias stays available while completion uses the builtin`(): Unit = runBlocking {
        sh {
            val first = "shopt -s expand_aliases\nalias printf='echo user-format'\nprintf payload"(timeout = 2.seconds)
            assertTrue(first.ok, first.stderrText)
            assertEquals(listOf("user-format payload"), first.stdout)
            val failed = "printf again; printf diagnostic >&2; false"(timeout = 2.seconds)
            assertEquals(1, failed.exitCode)
            assertFalse(failed.timedOut)
            assertEquals(listOf("user-format again"), failed.stdout)
            assertEquals(listOf("user-format diagnostic"), failed.stderr)
            assertTrue("shopt -u expand_aliases"(timeout = 2.seconds).ok)
            assertEquals(listOf("builtin"), "printf '%s' builtin"(timeout = 2.seconds).stdout)
        }
    }

    @Test
    fun `Shell readonly status-shaped user variable cannot falsify success`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val failed = "readonly __kotlysh_status=0; set -- 'user argument'; false"(timeout = 2.seconds)
                assertFalse(failed.ok)
                assertEquals(1, failed.exitCode)
                assertEquals(emptyList(), failed.stderr)
                val reused = """printf '%s|%s' "${'$'}__kotlysh_status" "${'$'}1";"""(timeout = 2.seconds)
                assertTrue(reused.ok, reused.stderrText)
                assertEquals(listOf("0|user argument"), reused.stdout)
                assertEquals(emptyList(), reused.stderr)
            }
        }
        assertEquals(listOf(1, 0), trace.events.filterIsInstance<RuntimeDispatchCompleted>().map { it.exitCode })
    }

    @Test
    fun `Shell completion bookkeeping leaves the user variable namespace unchanged`(): Unit = runBlocking {
        sh {
            // Bash lazily exposes built-in state such as PIPESTATUS after its first command.
            assertTrue("true"(timeout = 2.seconds).ok)
            val first = "builtin compgen -v"(timeout = 2.seconds)
            val second = "builtin compgen -v"(timeout = 2.seconds)
            assertTrue(first.ok, first.stderrText)
            assertTrue(second.ok, second.stderrText)
            assertEquals(first.stdout, second.stdout)
        }
    }

    @Test
    fun `Shell custom printf nonzero status and Unicode stderr remain intact`(): Unit = runBlocking {
        sh {
            val failed = """printf() { builtin printf '雪 😀' >&2; return 23; }; printf"""(timeout = 2.seconds)
            assertEquals(23, failed.exitCode)
            assertFalse(failed.timedOut)
            assertEquals(emptyList(), failed.stdout)
            assertEquals(listOf("雪 😀"), failed.stderr)
            assertTrue("unset -f printf"(timeout = 2.seconds).ok)
            assertEquals(listOf("ready"), "printf ready"(timeout = 2.seconds).stdout)
        }
    }
}
