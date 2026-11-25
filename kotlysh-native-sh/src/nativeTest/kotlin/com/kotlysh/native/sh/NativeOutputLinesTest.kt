package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeOutputLinesTest {
    @Test
    fun utf8EmptyLinesAndCrLfAreIndependentOfReadPartitions() {
        val bytes = "é雪😀\n\nx\r\nyé\r".encodeToByteArray()
        val expected = listOf("é雪😀", "", "x", "yé")
        for (first in 0..bytes.size) {
            for (second in first..bytes.size) {
                val capture = NativeOutputCapture(NativeOutputLimits())
                for ((from, to) in listOf(0 to first, first to second, second to bytes.size)) {
                    val chunk = bytes.copyOfRange(from, to)
                    capture.consume(true, chunk, chunk.size)
                }
                capture.finish()
                assertEquals(expected, capture.stdout, "Partition $first/$second")
                capture.finish()
                assertEquals(expected, capture.stdout)
            }
        }
    }

    @Test
    fun partialReadsDoNotConsumeOldScratchBytes() {
        val capture = NativeOutputCapture(NativeOutputLimits())
        val scratch = "ok\nwrong\n".encodeToByteArray()
        capture.consume(true, scratch, 3)
        assertEquals(listOf("ok"), capture.stdout)
        capture.consume(true, byteArrayOf('\r'.code.toByte()), 1)
        capture.finish()
        assertEquals(listOf("ok", ""), capture.stdout)
    }

    @Test
    fun malformedSequencesUseWholeLineReplacementAndPreserveNulData() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xC3.toByte(), '\n'.code.toByte(), 0, 0xF0.toByte(), 0x9F.toByte())
        val expected = listOf(bytes.decodeToString(0, 2), bytes.decodeToString(3, bytes.size))
        for (chunkSize in 1..bytes.size) {
            val capture = NativeOutputCapture(NativeOutputLimits())
            var from = 0
            while (from < bytes.size) {
                val to = minOf(from + chunkSize, bytes.size)
                val chunk = bytes.copyOfRange(from, to)
                capture.consume(true, chunk, chunk.size)
                from = to
            }
            capture.finish()
            assertEquals(expected, capture.stdout, "Chunk size $chunkSize")
        }
    }
}
