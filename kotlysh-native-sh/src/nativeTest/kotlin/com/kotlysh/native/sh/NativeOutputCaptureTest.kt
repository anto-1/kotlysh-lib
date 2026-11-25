package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeOutputCaptureTest {
    @Test
    fun completionAndUtf8AreIndependentOfReadPartitions() {
        val bytes = "é雪😀\n\nx\r\nprefix__done__:7\nignored\n".encodeToByteArray()
        for (first in 0..bytes.size) {
            for (second in first..bytes.size) {
                val capture = NativeOutputCapture(NativeOutputLimits(4, 20), "__done__")
                for ((from, to) in listOf(0 to first, first to second, second to bytes.size)) {
                    val chunk = bytes.copyOfRange(from, to)
                    capture.consume(true, chunk, chunk.size)
                }
                capture.finish()
                assertEquals(listOf("é雪😀", "", "x", "prefix"), capture.stdout, "Partition $first/$second")
                assertTrue(capture.stdoutDone)
                assertEquals(7, capture.exitCode)
                assertFalse(capture.outputLimited)
            }
        }
    }

    @Test
    fun overlappingUnderscoresDoNotHideCompletion() {
        for (prefix in listOf("_", "__", "___", "____", "__done_", "__done__:bad")) {
            val capture = NativeOutputCapture(NativeOutputLimits(1, prefix.length), "__done__")
            for (byte in "$prefix${"__done__"}:0\n".encodeToByteArray()) capture.consume(true, byteArrayOf(byte), 1)
            capture.finish()
            assertEquals(listOf(prefix), capture.stdout)
            assertTrue(capture.stdoutDone)
            assertFalse(capture.outputLimited)
        }
    }

    @Test
    fun unicodeClippingKeepsWholeCharactersForEveryBudgetAndPartition() {
        val bytes = "é😀z".encodeToByteArray()
        val expected = listOf(emptyList(), listOf("é"), listOf("é"), listOf("é"), listOf("é"), listOf("é😀"), listOf("é😀z"))
        for (budget in 1..7) {
            for (chunkSize in 1..bytes.size) {
                val capture = NativeOutputCapture(NativeOutputLimits(1, budget))
                for (chunk in bytes.toList().chunked(chunkSize)) capture.consume(true, chunk.toByteArray(), chunk.size)
                capture.finish()
                assertEquals(expected[budget - 1], capture.stdout, "Budget $budget/chunk $chunkSize")
                assertEquals(budget < bytes.size, capture.outputLimited)
            }
        }
    }

    @Test
    fun clippingDistinguishesValidPrefixesFromOverlongSurrogateAndOutOfRangeBytes() {
        val valid = listOf(
            byteArrayOf(0xC2.toByte()),
            byteArrayOf(0xE0.toByte(), 0xA0.toByte()),
            byteArrayOf(0xED.toByte(), 0x9F.toByte()),
            byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x8F.toByte(), 0x80.toByte()),
        )
        val malformed = listOf(
            byteArrayOf(0xC0.toByte()),
            byteArrayOf(0xE0.toByte(), 0x9F.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte()),
            byteArrayOf(0xF0.toByte(), 0x8F.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte()),
            byteArrayOf(0xF5.toByte(), 0x80.toByte()),
        )
        for (prefix in valid + malformed) {
            val capture = NativeOutputCapture(NativeOutputLimits(1, prefix.size))
            val bytes = prefix + byteArrayOf(0x80.toByte())
            capture.consume(true, bytes, bytes.size)
            capture.finish()
            val expected = if (valid.any { it.contentEquals(prefix) }) emptyList() else listOf(prefix.decodeToString())
            assertEquals(expected, capture.stdout)
            assertTrue(capture.outputLimited)
        }
    }

    @Test
    fun malformedBytesAndNulRemainDataWithinTheRawByteBudget() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xC3.toByte(), '\n'.code.toByte(), 0)
        val capture = NativeOutputCapture(NativeOutputLimits(2, bytes.size))
        capture.consume(true, bytes, bytes.size)
        capture.finish()
        assertEquals(listOf("\uFFFD\uFFFD", "\u0000"), capture.stdout)
        assertFalse(capture.outputLimited)
    }

    @Test
    fun invalidCompletionStatusIsOrdinaryBoundedOutput() {
        for (status in listOf("", "-1", "256", "9999", "abc")) {
            val bytes = "__done__:$status\n".encodeToByteArray()
            val capture = NativeOutputCapture(NativeOutputLimits(1, bytes.size), "__done__")
            capture.consume(true, bytes, bytes.size)
            capture.finish()
            assertEquals(listOf("__done__:$status"), capture.stdout)
            assertFalse(capture.stdoutDone)
            assertFalse(capture.outputLimited)
        }
    }

    @Test
    fun unfinishedCompletionCandidateIsChargedWhenTheStreamEnds() {
        val capture = NativeOutputCapture(NativeOutputLimits(1, 1), "__done__")
        capture.consume(true, "__".encodeToByteArray(), 2)
        capture.finish()
        assertEquals(listOf("_"), capture.stdout)
        assertTrue(capture.outputLimited)
        assertFalse(capture.stdoutDone)
    }

    @Test
    fun rawLineSeparatorsCountAndDoNotCreateExtraLinesOnOverflow() {
        for ((input, expected) in listOf("x\n" to listOf("x"), "\n\n" to listOf(""))) {
            val capture = NativeOutputCapture(NativeOutputLimits(2, 1))
            val bytes = input.encodeToByteArray()
            capture.consume(true, bytes, bytes.size)
            capture.finish()
            assertEquals(expected, capture.stdout)
            assertTrue(capture.outputLimited)
        }
    }

    @Test
    fun partialStreamsReserveSharedLineSlotsBeforeCompletion() {
        val capture = NativeOutputCapture(NativeOutputLimits(2, 7))
        capture.consume(true, "abc".encodeToByteArray(), 3)
        capture.consume(false, "def".encodeToByteArray(), 3)
        capture.consume(true, "\nx".encodeToByteArray(), 2)
        capture.finish()
        assertEquals(listOf("abc"), capture.stdout)
        assertEquals(listOf("def"), capture.stderr)
        assertTrue(capture.outputLimited)
    }
}
