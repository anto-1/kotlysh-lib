package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NativeShellSessionTest {
    @Test
    fun capturesStdout() {
        nativeSh {
            val result = "printf 'hello\\n'"()

            assertTrue(result.ok)
            assertEquals(listOf("hello"), result.stdout)
            assertEquals(emptyList(), result.stderr)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun capturesStdoutWithoutTrailingNewline() {
        nativeSh {
            val result = "printf 'hello'"()

            assertTrue(result.ok)
            assertEquals(listOf("hello"), result.stdout)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun capturesStderrSeparately() {
        nativeSh {
            val result = "printf 'warning\\n' >&2"()

            assertTrue(result.ok)
            assertEquals(emptyList(), result.stdout)
            assertEquals(listOf("warning"), result.stderr)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun capturesStderrWithoutTrailingNewline() {
        nativeSh {
            val result = "printf 'warning' >&2"()

            assertTrue(result.ok)
            assertEquals(emptyList(), result.stdout)
            assertEquals(listOf("warning"), result.stderr)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun capturesStdoutAndStderrInOneDispatch() {
        nativeSh {
            val result = "printf 'out\\n'; printf 'err\\n' >&2"()

            assertTrue(result.ok)
            assertEquals(listOf("out"), result.stdout)
            assertEquals(listOf("err"), result.stderr)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun handlesEmptySuccessfulCommand() {
        nativeSh {
            val result = ":"()

            assertTrue(result.ok)
            assertEquals(emptyList(), result.stdout)
            assertEquals(emptyList(), result.stderr)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun capturesNonZeroExitWithoutThrowing() {
        nativeSh {
            val result = "false"()

            assertFalse(result.ok)
            assertEquals(1, result.exitCode)
            assertFalse(result.timedOut)
            assertFalse(result.idleTimedOut)
        }
    }

    @Test
    fun keepsSessionUsableAfterNonZeroExit() {
        nativeSh {
            val failed = "false"()
            val alive = "printf 'alive\\n'"()

            assertFalse(failed.ok)
            assertEquals(1, failed.exitCode)
            assertTrue(alive.ok)
            assertEquals("alive", alive.stdout.single())
        }
    }

    @Test
    fun preservesWorkingDirectoryBetweenInvokes() {
        nativeSh {
            "cd /tmp"()
            val result = "pwd"()

            assertTrue(result.ok)
            assertEquals("/tmp", result.stdout.single())
        }
    }

    @Test
    fun preservesEnvironmentBetweenInvokes() {
        nativeSh {
            "export KOTLYSH_NATIVE_TEST=present"()
            val result = "printf '%s\\n' \"\$KOTLYSH_NATIVE_TEST\""()

            assertTrue(result.ok)
            assertEquals("present", result.stdout.single())
        }
    }

    @Test
    fun preservesFunctionsBetweenInvokes() {
        nativeSh {
            """
            greet() {
                printf 'hello %s\n' "$1"
            }
            """.trimIndent()()
            val result = "greet kotlysh"()

            assertTrue(result.ok)
            assertEquals("hello kotlysh", result.stdout.single())
        }
    }

    @Test
    fun runsMultilineScriptlets() {
        nativeSh {
            val result = """
                for item in alpha beta gamma; do
                    printf '%s\n' "${'$'}item"
                done
            """()

            assertTrue(result.ok)
            assertEquals(listOf("alpha", "beta", "gamma"), result.stdout)
        }
    }

    @Test
    fun capturesLargeStdoutBeyondOneReadBuffer() {
        nativeSh {
            val result = """
                i=0
                while [ "${'$'}i" -lt 600 ]; do
                    printf 'line-%s\n' "${'$'}i"
                    i=${'$'}((i + 1))
                done
            """.trimIndent()()

            assertTrue(result.ok)
            assertEquals(600, result.stdout.size)
            assertEquals("line-0", result.stdout.first())
            assertEquals("line-599", result.stdout.last())
        }
    }

    @Test
    fun sequentialDispatchesSeePriorShellState() {
        nativeSh {
            "counter=1"()
            "counter=\$((counter + 1))"()
            val result = "printf '%s\\n' \"\$counter\""()

            assertTrue(result.ok)
            assertEquals("2", result.stdout.single())
        }
    }

    @Test
    fun reportsWallTimeoutEvenWhenOutputContinues() {
        nativeSh {
            val result = """
                while :; do
                    printf 'tick\n'
                    sleep 1
                done
            """.trimIndent()(
                timeout = 250.milliseconds,
                idleTimeout = 2.seconds,
            )

            assertFalse(result.ok)
            assertTrue(result.timedOut)
            assertFalse(result.idleTimedOut)
            assertTrue(result.stdout.isNotEmpty())
        }
    }

    @Test
    fun reportsIdleTimeout() {
        nativeSh {
            val result = "sleep 2; printf 'done\\n'"(
                timeout = 3.seconds,
                idleTimeout = 100.milliseconds,
            )

            assertFalse(result.ok)
            assertTrue(result.idleTimedOut)
        }
    }

    @Test
    fun refusesFurtherDispatchAfterTimeout() {
        val session = NativeShellSession()
        try {
            with(session) {
                val result = "sleep 2"(
                    timeout = 100.milliseconds,
                    idleTimeout = 2.seconds,
                )

                assertFalse(result.ok)
                assertTrue(result.timedOut)
                assertFailsWith<IllegalStateException> {
                    "printf 'should-not-run\\n'"()
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun reportsProcessExitAndRefusesFurtherDispatch() {
        val session = NativeShellSession()
        try {
            with(session) {
                val result = "exit 7"(
                    timeout = 2.seconds,
                    idleTimeout = 2.seconds,
                )

                assertFalse(result.ok)
                assertTrue(result.processExited)
                assertFailsWith<IllegalStateException> {
                    "printf 'should-not-run\\n'"()
                }
            }
        } finally {
            session.close()
        }
    }
}
