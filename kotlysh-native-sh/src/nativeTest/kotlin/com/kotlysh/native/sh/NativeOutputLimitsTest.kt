package com.kotlysh.nativesh

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NativeOutputLimitsTest {
    @Test
    fun defaultLineLimitBoundsEmptyDirectOutput() {
        val result = NativeExecScope().execute("/usr/bin/awk", "BEGIN { for (i=0; i<10001; i++) print \"\" }",
            timeout = 2.seconds)
        assertTrue(result.outputLimited)
        assertFalse(result.ok)
        assertEquals(List(10_000) { "" }, result.stdout)
    }

    @Test
    fun defaultByteLimitBoundsUnfinishedShellOutput() {
        nativeSh {
            val result = "/usr/bin/head -c 1000001 /dev/zero | /usr/bin/tr '\\000' x; sleep 0.2"(timeout = 2.seconds)
            assertTrue(result.outputLimited)
            assertFalse(result.ok)
            assertEquals(listOf("x".repeat(1_000_000)), result.stdout)
            assertFailsWith<IllegalStateException> { "must not run"() }
        }
    }

    @Test
    fun limitsMustBePositive() {
        for (invalid in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { NativeOutputLimits(maxLinesPerDispatch = invalid) }
            assertFailsWith<IllegalArgumentException> { NativeOutputLimits(maxBytesPerDispatch = invalid) }
        }
    }

    @Test
    fun exactBudgetShellCompletionIsFreeAndTheSessionRemainsUsable() {
        nativeSh {
            val limits = NativeOutputLimits(1, 3)
            repeat(2) {
                val result = "printf 'é\\n'"(outputLimits = limits, timeout = 1.seconds)
                assertTrue(result.ok)
                assertFalse(result.outputLimited)
                assertEquals(listOf("é"), result.stdout)
            }
        }
    }

    @Test
    fun bothStreamsShareTheByteBudgetEvenWithoutNewlines() {
        nativeSh {
            val result = "printf abc; printf def >&2; sleep 0.2"(
                outputLimits = NativeOutputLimits(2, 5), timeout = 1.seconds)
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertFalse(result.idleTimedOut)
            assertEquals(5, (result.stdout + result.stderr).sumOf { it.encodeToByteArray().size })
            assertEquals(2, result.stdout.size + result.stderr.size)
        }
    }

    @Test
    fun bothStreamsShareLineSlotsBeforeAnUnfinishedLineEnds() {
        nativeSh {
            val result = "printf abc; printf def >&2; sleep 0.2"(
                outputLimits = NativeOutputLimits(1, 100), timeout = 1.seconds)
            assertTrue(result.outputLimited)
            assertEquals(1, result.stdout.size + result.stderr.size)
            assertEquals(listOf("abc"), result.stdout)
        }
    }

    @Test
    fun clippedUnicodeDoesNotInventAReplacementCharacter() {
        val result = NativeExecScope().execute("/usr/bin/printf", "%s", "é😀z",
            outputLimits = NativeOutputLimits(1, 5), timeout = 1.seconds)
        assertTrue(result.outputLimited)
        assertEquals(listOf("é"), result.stdout)
    }

    @Test
    fun directByteOverflowRetainsOnlyTheAcceptedPrefix() {
        val result = NativeExecScope().execute("/usr/bin/printf", "%s", "long",
            outputLimits = NativeOutputLimits(1, 3), timeout = 1.seconds)
        assertTrue(result.outputLimited)
        assertFalse(result.ok)
        assertEquals(listOf("lon"), result.stdout)
    }

    @Test
    fun deadlinesRetainPartialOutputUnderTheCaptureBudget() {
        nativeSh {
            val result = "printf partial; sleep 1"(timeout = 100.milliseconds, idleTimeout = 1.seconds,
                outputLimits = NativeOutputLimits(1, 100))
            assertTrue(result.timedOut)
            assertFalse(result.outputLimited)
            assertEquals(listOf("partial"), result.stdout)
        }
    }

    @Test
    fun directStringAndSuspendingEntryPointsForwardThePolicy() {
        nativeExec {
            val result = "/usr/bin/printf"("%s", "long", outputLimits = NativeOutputLimits(1, 3))
            assertTrue(result.outputLimited)
            assertEquals(listOf("lon"), result.stdout)
        }
        runBlocking {
            val result = nativeExecSuspending("/usr/bin/printf", "%s", "long", outputLimits = NativeOutputLimits(1, 3))
            assertTrue(result.outputLimited)
            assertEquals(listOf("lon"), result.stdout)
        }
    }

    @Test
    fun captureFlagSurvivesCopyAndPreventsSuccessfulResults() {
        val direct = NativeExecResult(emptyList(), emptyList(), 0, outputLimited = true)
        val shell = NativeShellResult(emptyList(), emptyList(), 0, outputLimited = true)
        assertFalse(direct.ok)
        assertFalse(shell.ok)
        assertTrue(direct.copy().outputLimited)
        assertTrue(shell.copy().outputLimited)
        assertFalse(direct.copy() == direct.copy(outputLimited = false))
        assertFalse(shell.copy() == shell.copy(outputLimited = false))
    }
}
