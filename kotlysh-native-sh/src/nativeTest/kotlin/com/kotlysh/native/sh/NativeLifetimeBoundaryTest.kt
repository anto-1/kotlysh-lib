package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class NativeLifetimeBoundaryTest {
    private val ownedSleep = """
        sleep 30 & child=${'$'}!
        command printf 'owned:%s:%s\n' "${'$'}child" "${'$'}(/usr/bin/awk '{print ${'$'}22}' /proc/${'$'}child/stat)"
    """.trimIndent() + "\n"

    @Test
    fun shellSubmissionUsesTheDispatchWallDeadline() {
        nativeSh {
            val started = TimeSource.Monotonic.markNow()
            val result = ("sleep 0.6\n#" + "x".repeat(512 * 1024))(timeout = 100.milliseconds, idleTimeout = 1.seconds)
            assertTrue(result.timedOut, "Blocked submission escaped the dispatch deadline")
            assertFalse(result.idleTimedOut)
            assertTrue(started.elapsedNow() < 400.milliseconds, "Submission waited for the script's sleep")
            assertFailsWith<IllegalStateException> { "must not run"() }
        }
    }

    @Test
    fun directTimeoutStopsItsOwnedChild() {
        val result = NativeExecScope().execute("/bin/sh", "-c", ownedSleep + "wait\n",
            timeout = 200.milliseconds, idleTimeout = 1.seconds)
        val child = ownedChild(result.stdout)
        try {
            assertTrue(result.timedOut)
            child.assertStopped()
        } finally { child.cleanup() }
    }

    @Test
    fun shellTimeoutStopsItsOwnedChild() {
        nativeSh {
            val result = (ownedSleep + "wait\n")(timeout = 200.milliseconds, idleTimeout = 1.seconds)
            val child = ownedChild(result.stdout)
            try {
                assertTrue(result.timedOut)
                child.assertStopped()
            } finally { child.cleanup() }
        }
    }

    @Test
    fun shellScopeCloseStopsItsBackgroundChild() {
        var child: NativeTestProcess? = null
        try {
            nativeSh {
                val result = ownedSleep(timeout = 1.seconds)
                assertTrue(result.ok)
                child = ownedChild(result.stdout)
            }
            checkNotNull(child).assertStopped()
        } finally { child?.cleanup() }
    }

    @Test
    fun rootExitDoesNotWaitForInheritedChildPipes() {
        val result = NativeExecScope().execute("/bin/sh", "-c", ownedSleep + "exit 0\n",
            timeout = 200.milliseconds, idleTimeout = 1.seconds)
        val child = ownedChild(result.stdout)
        try {
            assertTrue(result.ok, "Completed root was reported as an inherited-pipe timeout")
            assertFalse(result.timedOut)
            assertEquals(0, result.exitCode)
            child.assertStopped()
        } finally { child.cleanup() }
    }

    @Test
    fun nonpositiveNativeTimeoutsAreRejectedWithoutPoisoningCallers() {
        for (timeout in listOf(Duration.ZERO, (-1).milliseconds)) {
            nativeExec {
                assertFailsWith<IllegalArgumentException> { execute("/usr/bin/printf", "%s", "wrong", timeout = timeout) }
                assertFailsWith<IllegalArgumentException> { execute("/usr/bin/printf", "%s", "wrong", idleTimeout = timeout) }
                assertEquals("ready", execute("/usr/bin/printf", "%s", "ready").stdout.single())
            }
            nativeSh {
                assertFailsWith<IllegalArgumentException> { "printf wrong"(timeout = timeout) }
                assertFailsWith<IllegalArgumentException> { "printf wrong"(idleTimeout = timeout) }
                assertEquals("ready", "printf ready"().stdout.single())
            }
        }
    }

    @Test
    fun unrepresentableShellSourceIsRejectedBeforeSubmission() {
        nativeSh {
            for (source in listOf("printf wrong\u0000", "\uD800", "\uDC00")) {
                assertFailsWith<IllegalArgumentException> { source(timeout = 1.seconds) }
                assertEquals("ready", "printf ready"().stdout.single())
            }
        }
    }

    @Test
    fun shellSubmissionAlsoUsesTheIdleDeadline() {
        nativeSh {
            val started = TimeSource.Monotonic.markNow()
            val result = ("sleep 0.6\n#" + "x".repeat(512 * 1024))(
                timeout = 1.seconds, idleTimeout = 100.milliseconds)
            assertTrue(result.idleTimedOut)
            assertFalse(result.timedOut)
            assertTrue(started.elapsedNow() < 400.milliseconds)
        }
    }

    @Test
    fun shellReadsBothStreamsWhileSubmissionIsPending() {
        nativeSh {
            val line = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2)
            val source = "/usr/bin/awk 'BEGIN { for (i=0; i<2048; i++) { print \"$line\"; print \"$line\" > \"/dev/stderr\" } }'\n" +
                "#" + "x".repeat(512 * 1024) + "\nprintf 'done\\n'\n"
            val result = source(timeout = 3.seconds, idleTimeout = 1.seconds)
            assertTrue(result.ok, "Duplex submission failed: $result")
            assertEquals(List(2048) { line } + "done", result.stdout)
            assertEquals(List(2048) { line }, result.stderr)
            assertEquals("ready", "printf ready"().stdout.single())
        }
    }

    @Test
    fun closingShellStdinReportsExitAndKeepsTheHostAlive() {
        nativeSh {
            val source = "exec 0<&-; sleep 1\n#" + "x".repeat(512 * 1024)
            val result = source(timeout = 2.seconds)
            assertTrue(result.processExited)
            assertFalse(result.timedOut)
            assertFalse(result.idleTimedOut)
            assertFailsWith<IllegalStateException> { "must not run"() }
        }
        assertEquals("alive", NativeExecScope().execute("/usr/bin/printf", "%s", "alive").stdout.single())
    }

    @Test
    fun directRootExitPreservesBothOutputBurstsAndTheExitStatus() {
        val line = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(2)
        val source = "/usr/bin/awk 'BEGIN { for (i=0; i<512; i++) { print \"$line\"; print \"$line\" > \"/dev/stderr\" } }'\n" +
            "printf tail; printf error-tail >&2; exit 7"
        val result = NativeExecScope().execute("/bin/sh", "-c", source, timeout = 2.seconds)
        assertEquals(7, result.exitCode)
        assertEquals(null, result.signal)
        assertFalse(result.timedOut)
        assertEquals(List(512) { line } + "tail", result.stdout)
        assertEquals(List(512) { line } + "error-tail", result.stderr)
    }

    @Test
    fun shellRootExitPreservesPartialOutputAndPoisonsTheSession() {
        nativeSh {
            val result = "printf tail; printf error-tail >&2; exit 7"(timeout = 1.seconds)
            assertTrue(result.processExited)
            assertFalse(result.timedOut)
            assertEquals(listOf("tail"), result.stdout)
            assertEquals(listOf("error-tail"), result.stderr)
            assertFailsWith<IllegalStateException> { "must not run"() }
        }
    }

    @Test
    fun directSignalStatusRemainsDistinctFromTimeout() {
        val result = NativeExecScope().execute("/bin/sh", "-c", "kill -TERM ${'$'}${'$'}", timeout = 1.seconds)
        assertEquals(null, result.exitCode)
        assertEquals(15, result.signal)
        assertFalse(result.timedOut)
        assertFalse(result.idleTimedOut)
    }

    @Test
    fun infiniteBudgetsDoNotOverflowPollOrReportTimeout() {
        val result = NativeExecScope().execute("/usr/bin/printf", "%s", "direct",
            timeout = Duration.INFINITE, idleTimeout = Duration.INFINITE)
        assertTrue(result.ok)
        assertEquals(listOf("direct"), result.stdout)
        nativeSh {
            val shell = "printf shell"(timeout = Duration.INFINITE, idleTimeout = Duration.INFINITE)
            assertTrue(shell.ok)
            assertEquals(listOf("shell"), shell.stdout)
        }
    }

    @Test
    fun shellTimeoutKeepsUnfinishedLinesFromBothStreams() {
        nativeSh {
            val result = "printf tail; printf error-tail >&2; sleep 1"(
                timeout = 100.milliseconds, idleTimeout = 1.seconds)
            assertTrue(result.timedOut)
            assertEquals(listOf("tail"), result.stdout)
            assertEquals(listOf("error-tail"), result.stderr)
        }
    }

    @Test
    fun directCaptureLimitStopsItsOwnedChild() {
        val result = NativeExecScope().execute("/bin/sh", "-c",
            ownedSleep + "/usr/bin/awk 'BEGIN { for (i=0; i<100; i++) print \"x\" }'; wait\n",
            timeout = 2.seconds, outputLimits = NativeOutputLimits(3, 1024))
        val child = ownedChild(result.stdout)
        try {
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            child.assertStopped()
        } finally { child.cleanup() }
    }

    @Test
    fun shellCaptureLimitStopsItsOwnedChildAndPoisonsTheSession() {
        nativeSh {
            val result = (ownedSleep + "/usr/bin/awk 'BEGIN { for (i=0; i<100; i++) print \"x\" }'; wait\n")(
                timeout = 2.seconds, outputLimits = NativeOutputLimits(3, 1024))
            val child = ownedChild(result.stdout)
            try {
                assertTrue(result.outputLimited)
                assertFalse(result.timedOut)
                child.assertStopped()
                assertFailsWith<IllegalStateException> { "must not run"() }
            } finally { child.cleanup() }
        }
    }

    private fun ownedChild(lines: List<String>): NativeTestProcess {
        val values = lines.single { it.startsWith("owned:") }.split(':')
        assertTrue(values[2].isNotEmpty(), "Fixture did not record the child's start time")
        return NativeTestProcess(values[1].toInt(), values[2])
    }
}
