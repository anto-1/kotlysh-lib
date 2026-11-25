package com.kotlysh.nativesh

/** Combined stdout/stderr limits per call. Bytes include user LF/CR and exclude shell completion records. */
data class NativeOutputLimits(
    val maxLinesPerDispatch: Int = 10_000,
    val maxBytesPerDispatch: Int = 1_000_000,
) {
    init {
        require(maxLinesPerDispatch > 0) { "Maximum native output lines must be positive" }
        require(maxBytesPerDispatch > 0) { "Maximum native output bytes must be positive" }
    }
}

/** Own the captured values without exposing a mutable list or mutable views. */
private class NativeOutputSnapshot(lines: Collection<String>) : AbstractList<String>() {
    private val values = lines.toTypedArray()
    override val size: Int get() = values.size
    override fun get(index: Int): String = values[index]
}

/** One admission rule for completed and unfinished output on both native pipes. */
internal class NativeOutputCapture(private val limits: NativeOutputLimits, marker: String? = null) {
    private val markerPrefix = marker?.let { "$it:".encodeToByteArray() }
    private val output = Stream()
    private val errors = Stream()
    private var admittedBytes = 0
    private var admittedLines = 0
    var outputLimited = false
        private set
    val stdout: List<String> get() = NativeOutputSnapshot(output.lines)
    val stderr: List<String> get() = NativeOutputSnapshot(errors.lines)
    val stdoutDone: Boolean get() = output.exitCode != null
    val stderrDone: Boolean get() = errors.exitCode != null
    val exitCode: Int? get() = errors.exitCode ?: output.exitCode

    fun consume(isStdout: Boolean, bytes: ByteArray, count: Int) {
        val stream = if (isStdout) output else errors
        for (index in 0 until count) {
            if (outputLimited || stream.exitCode != null) break
            stream.receive(bytes[index])
        }
    }

    fun finish() {
        output.finish()
        errors.finish()
    }

    private inner class Stream {
        val lines = mutableListOf<String>()
        var exitCode: Int? = null
        private val pending = NativeOutputLines(limits.maxBytesPerDispatch)
        private var lineReserved = false
        // At most the private marker plus three decimal status digits and one probe byte.
        private val probe = ByteArray((markerPrefix?.size ?: 0) + 4)
        private var probeSize = 0

        fun receive(byte: Byte) {
            if (byte == '\n'.code.toByte()) {
                val status = completionStatus()
                if (status != null) {
                    probeSize = 0
                    emitPartial()
                    exitCode = status
                } else {
                    flushProbe()
                    if (admitByte()) {
                        lines += pending.takeRemainder() ?: ""
                        lineReserved = false
                    }
                }
                return
            }
            if (markerPrefix == null) { append(byte); return }
            probe[probeSize++] = byte
            // Keep only a suffix that can still be a completion record. Overlapping
            // underscores in user output must not hide the following private marker.
            while (probeSize > 0 && !isCompletionPrefix()) {
                append(probe[0])
                probe.copyInto(probe, 0, 1, probeSize)
                probeSize--
                if (outputLimited) { probeSize = 0; break }
            }
        }

        private fun isCompletionPrefix(): Boolean {
            val prefix = checkNotNull(markerPrefix)
            for (index in 0 until minOf(probeSize, prefix.size))
                if (probe[index] != prefix[index]) return false
            if (probeSize <= prefix.size) return true
            if (probeSize > prefix.size + 3) return false
            for (index in prefix.size until probeSize)
                if (probe[index] !in '0'.code.toByte()..'9'.code.toByte()) return false
            return true
        }

        private fun completionStatus(): Int? {
            val prefix = markerPrefix ?: return null
            if (probeSize <= prefix.size) return null
            var value = 0
            for (index in prefix.size until probeSize) value = value * 10 + probe[index] - '0'.code
            return value.takeIf { it <= 255 }
        }

        private fun flushProbe() {
            for (index in 0 until probeSize) append(probe[index])
            probeSize = 0
        }

        private fun admitByte(): Boolean {
            if (outputLimited) return false
            if (admittedBytes >= limits.maxBytesPerDispatch ||
                (!lineReserved && admittedLines >= limits.maxLinesPerDispatch)) {
                outputLimited = true
                return false
            }
            if (!lineReserved) { lineReserved = true; admittedLines++ }
            admittedBytes++
            return true
        }

        private fun append(byte: Byte) {
            if (admitByte()) pending.appendByte(byte)
        }

        private fun emitPartial() {
            pending.takeRemainder(clipped = outputLimited)?.let { lines += it }
            lineReserved = false
        }

        fun finish() {
            flushProbe()
            emitPartial()
        }
    }
}
