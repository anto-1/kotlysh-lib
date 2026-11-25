package com.kotlysh.nativesh

/** Owns an unfinished byte line so UTF-8 characters can span native reads. */
internal class NativeOutputLines(private val maxBytes: Int) {
    private var pending = ByteArray(minOf(256, maxBytes))
    private var size = 0

    fun appendByte(byte: Byte) {
        check(size < maxBytes) { "Native output line exceeds its byte budget" }
        ensureCapacity(size + 1)
        pending[size++] = byte
    }

    fun takeRemainder(clipped: Boolean = false): String? {
        if (size == 0) return null
        if (clipped) {
            size = completeUtf8Prefix(size)
            if (size == 0) return null
        }
        return takeLine()
    }

    private fun takeLine(): String {
        val line = pending.decodeToString(0, size).removeSuffix("\r")
        size = 0
        return line
    }

    private fun ensureCapacity(required: Int) {
        check(required <= maxBytes) { "Native output line exceeds its byte budget" }
        if (required > pending.size) {
            val doubled = if (pending.size <= Int.MAX_VALUE / 2) pending.size * 2 else Int.MAX_VALUE
            pending = pending.copyOf(minOf(maxBytes, maxOf(required, doubled)))
        }
    }

    /** Omit an incomplete valid sequence only when clipping created the boundary. */
    private fun completeUtf8Prefix(end: Int): Int {
        var lead = end - 1
        while (lead > 0 && end - lead < 4 && (pending[lead].toInt() and 0xC0) == 0x80) lead--
        val first = pending[lead].toInt() and 0xFF
        val expected = when (first) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return end
        }
        if (end - lead >= expected) return end
        for (index in lead + 1 until end) {
            val byte = pending[index].toInt() and 0xFF
            val range = if (index != lead + 1) 0x80..0xBF else when (first) {
                0xE0 -> 0xA0..0xBF
                0xED -> 0x80..0x9F
                0xF0 -> 0x90..0xBF
                0xF4 -> 0x80..0x8F
                else -> 0x80..0xBF
            }
            if (byte !in range) return end
        }
        return lead
    }
}
