package com.kotlysh.nativesh

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NativeExecTest {
    @Test
    fun invokesBinaryWithArgumentVector() {
        nativeExec {
            val result = "/usr/bin/printf"("%s\n", "hello")

            assertTrue(result.ok)
            assertEquals(listOf("hello"), result.stdout)
            assertEquals(emptyList(), result.stderr)
            assertEquals(0, result.exitCode)
        }
    }

    @Test
    fun preservesShellSyntaxAsPlainArguments() {
        nativeExec {
            val payload = "alpha; echo injected && rm -rf /"
            val result = "/usr/bin/printf"("%s\n", payload)

            assertTrue(result.ok)
            assertEquals(listOf(payload), result.stdout)
        }
    }

    @Test
    fun supportsCwdAndEnvironmentArguments() {
        nativeExec {
            val pwd = execute("/bin/pwd", cwd = "/tmp")
            val env = execute(
                "/usr/bin/env",
                env = mapOf("KOTLYSH_NATIVE_EXEC_TEST" to "present"),
            )

            assertTrue(pwd.ok)
            assertEquals("/tmp", pwd.stdout.single())
            assertTrue(env.stdout.contains("KOTLYSH_NATIVE_EXEC_TEST=present"))
        }
    }

    @Test
    fun doesNotPreserveCwdBetweenRuns() {
        nativeExec {
            val first = execute("/bin/pwd", cwd = "/tmp")
            val second = "/bin/pwd"()

            assertTrue(first.ok)
            assertTrue(second.ok)
            assertEquals("/tmp", first.stdout.single())
            assertFalse(second.stdout.single() == "/tmp")
        }
    }

    @Test
    fun capturesNonZeroExitWithoutThrowing() {
        nativeExec {
            val result = "/bin/false"()

            assertFalse(result.ok)
            assertEquals(1, result.exitCode)
            assertFalse(result.timedOut)
            assertFalse(result.idleTimedOut)
        }
    }

    @Test
    fun capturesStderrSeparately() {
        nativeExec {
            val result = "/bin/ls"("/definitely/not/a/kotlysh/path")

            assertFalse(result.ok)
            assertEquals(emptyList(), result.stdout)
            assertTrue(result.stderrText.contains("/definitely/not/a/kotlysh/path"))
        }
    }

    @Test
    fun capturesLargeStdoutWithoutDeadlock() {
        nativeExec {
            val result = "/usr/bin/seq"("1", "1500", timeout = 5.seconds)

            assertTrue(result.ok)
            assertEquals(1500, result.stdout.size)
            assertEquals("1", result.stdout.first())
            assertEquals("1500", result.stdout.last())
        }
    }

    @Test
    fun reportsWallTimeout() {
        nativeExec {
            val result = "/bin/sleep"(
                "2",
                timeout = 100.milliseconds,
                idleTimeout = 2.seconds,
            )

            assertFalse(result.ok)
            assertTrue(result.timedOut)
            assertFalse(result.idleTimedOut)
        }
    }

    @Test
    fun reportsMissingExecutableAsExit127() {
        nativeExec {
            val result = "/definitely/not/kotlysh-missing-binary"()

            assertFalse(result.ok)
            assertEquals(127, result.exitCode)
            assertTrue(result.stderrText.contains("exec failed"))
        }
    }

    @Test
    fun independentDirectProcessesCanBeOrchestratedWithCoroutines() = runBlocking {
        val one = async {
            nativeExecSuspending("/usr/bin/printf", "%s\n", "one")
        }
        val two = async {
            nativeExecSuspending("/usr/bin/printf", "%s\n", "two")
        }

        assertEquals("one", one.await().stdout.single())
        assertEquals("two", two.await().stdout.single())
    }
}
