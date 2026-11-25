package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NativeShellCompletionStateTest {
    @Test
    fun printfFunctionPersistsWithoutInterceptingCompletion() {
        nativeSh {
            val first = """printf() { command printf 'user-format:%s\n' "${'$'}*"; }; printf payload"""(timeout = 1.seconds)
            assertTrue(first.ok)
            assertEquals(listOf("user-format:payload"), first.stdout)
            assertTrue(first.stderr.isEmpty())

            val failed = "printf again; printf diagnostic >&2; (exit 7)"(timeout = 1.seconds)
            assertEquals(7, failed.exitCode)
            assertFalse(failed.timedOut)
            assertFalse(failed.processExited)
            assertEquals(listOf("user-format:again"), failed.stdout)
            assertEquals(listOf("user-format:diagnostic"), failed.stderr)
            val reused = "printf ready"(timeout = 1.seconds)
            assertTrue(reused.ok)
            assertEquals(listOf("user-format:ready"), reused.stdout)
        }
    }

    @Test
    fun printfAliasStaysAvailableAcrossDispatches() {
        nativeSh {
            assertTrue("alias printf='echo user-format'"(timeout = 1.seconds).ok)
            val failed = "printf again; printf diagnostic >&2; false"(timeout = 1.seconds)
            assertEquals(1, failed.exitCode)
            assertFalse(failed.timedOut)
            assertFalse(failed.processExited)
            assertEquals(listOf("user-format again"), failed.stdout)
            assertEquals(listOf("user-format diagnostic"), failed.stderr)
            val reused = "printf ready"(timeout = 1.seconds)
            assertTrue(reused.ok)
            assertEquals(listOf("user-format ready"), reused.stdout)
        }
    }

    @Test
    fun readonlyStatusVariableCannotChangeTheFragmentExitCode() {
        nativeSh {
            val failed = "readonly __kotlysh_status=0; set -- 'user argument'; false"(timeout = 1.seconds)
            assertEquals(1, failed.exitCode)
            assertFalse(failed.ok)
            assertFalse(failed.processExited)
            assertTrue(failed.stderr.isEmpty())

            val reused = """command printf '%s|%s' "${'$'}__kotlysh_status" "${'$'}1";"""(timeout = 1.seconds)
            assertTrue(reused.ok)
            assertEquals(listOf("0|user argument"), reused.stdout)
            assertTrue(reused.stderr.isEmpty())
        }
    }

    @Test
    fun completionBookkeepingDoesNotLeavePersistentVariables() {
        nativeSh {
            assertTrue("true"(timeout = 1.seconds).ok)
            repeat(2) {
                val variables = "set"(timeout = 1.seconds)
                assertTrue(variables.ok)
                assertFalse(variables.stdout.any {
                    it.startsWith("__kotlysh_status=") || it.startsWith("__KOTLYSH_NATIVE_SH_")
                }, "Completion bookkeeping leaked into the user variable namespace")
            }
        }
    }
}
