package com.kotlysh.core

/** Scans an append-only line without revisiting its already accepted status digits. */
internal class RuntimeCompletionFrameScanner(
    private val markerPrefix: String,
    private val allowIncompleteStatus: Boolean = false,
) {
    private var searchFrom = 0
    var frameStart: Int? = null
        private set
    private var statusFrom = 0
    private var statusCursor = 0
    private var negative = false
    private var hasDigit = false
    // Negative accumulation permits Int.MIN_VALUE as well as Int.MAX_VALUE.
    private var value = 0
    var payloadStart: Int? = null
        private set

    /** Length of the current valid status candidate, including its optional sign. */
    val statusCharacters: Int
        get() = if (frameStart == null) 0 else statusCursor - statusFrom

    fun reset() {
        searchFrom = 0
        frameStart = null
        payloadStart = null
    }

    /** Returns the first frame eligible for transport or partial-log suppression. */
    fun scan(text: CharSequence): Int? {
        if (payloadStart != null) return frameStart
        // A trailing CR may be half of a CRLF delimiter. An embedded CR will
        // be checked when another character arrives, so it cannot hide logs.
        val end = text.length - if (text.lastOrNull() == '\r') 1 else 0
        while (true) {
            if (frameStart == null) {
                val start = text.indexOf(markerPrefix, searchFrom)
                if (start < 0) {
                    searchFrom = (end - markerPrefix.length + 1).coerceAtLeast(0)
                    return null
                }
                frameStart = start
                statusFrom = start + markerPrefix.length
                statusCursor = statusFrom
                negative = false
                hasDigit = false
                value = 0
            }
            while (statusCursor < end) {
                val character = text[statusCursor]
                if (character == ':') {
                    if (hasDigit) {
                        payloadStart = statusCursor + 1
                        return frameStart
                    }
                    break
                }
                if (statusCursor == statusFrom && (character == '+' || character == '-')) {
                    negative = character == '-'
                } else {
                    val digit = character.digitToIntOrNull(10) ?: break
                    val limit = if (negative) Int.MIN_VALUE else -Int.MAX_VALUE
                    if (value < limit / 10) break
                    val multiplied = value * 10
                    if (multiplied < limit + digit) break
                    value = multiplied - digit
                    hasDigit = true
                }
                statusCursor++
            }
            if (statusCursor == end) return if (allowIncompleteStatus) frameStart else null
            // Earlier accepted characters are only a sign and digits; none
            // can begin our marker. The first rejected character can.
            searchFrom = statusCursor
            frameStart = null
        }
    }
}
