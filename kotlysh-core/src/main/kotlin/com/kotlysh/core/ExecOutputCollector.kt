package com.kotlysh.core

/** One exec owns both stream buffers and quotas. Its monitor serializes collection and delivery. */
internal class ExecOutputCollector(
    private val limits: RuntimeOutputLimits,
    private val onOutput: (RuntimeStream, String) -> Unit,
) {
    private class StreamState {
        val pending = StringBuilder()
        val output = mutableListOf<String>()
        var afterCarriageReturn = false
    }

    private val monitor = Any()
    private val stdout = StreamState()
    private val stderr = StreamState()
    private var outputLines = 0
    private var outputCharacters = 0

    @Volatile
    var outputLimited = false
        private set

    private fun state(stream: RuntimeStream): StreamState = when (stream) {
        RuntimeStream.Stdout -> stdout
        RuntimeStream.Stderr -> stderr
    }

    fun output(stream: RuntimeStream): List<String> = synchronized(monitor) {
        state(stream).output.immutableSnapshot()
    }

    fun consume(stream: RuntimeStream, text: String) {
        synchronized(monitor) {
            val state = state(stream)
            val pending = state.pending
            for (character in text) {
                if (outputLimited) break
                if (state.afterCarriageReturn && character == '\n') {
                    state.afterCarriageReturn = false
                    continue
                }
                state.afterCarriageReturn = character == '\r'
                if (character == '\n' || character == '\r') {
                    line(stream, pending.toString())
                    pending.setLength(0)
                } else {
                    pending.append(character)
                }
            }
            partialLines()
        }
    }

    /** Flush a final ordinary line only after a successful reader completion. */
    fun end(stream: RuntimeStream) {
        synchronized(monitor) {
            val pending = state(stream).pending
            if (!outputLimited && pending.isNotEmpty()) {
                line(stream, pending.toString())
                pending.setLength(0)
                partialLines()
            }
        }
    }

    // Only the synchronized entry points call these methods; delivery stays in their transaction.
    private fun line(stream: RuntimeStream, text: String) {
        if (outputLimited) return
        val remaining = limits.maxCharactersPerDispatch - outputCharacters
        val denied = outputLines >= limits.maxLinesPerDispatch || remaining <= 0 && text.isNotEmpty()
        val accepted = if (denied) "" else text.utf16PrefixWithin(remaining)
        if (denied || accepted.length < text.length) outputLimited = true
        if (denied || accepted.isEmpty() && text.isNotEmpty()) return
        state(stream).output += accepted
        outputLines++
        outputCharacters += accepted.length
        onOutput(stream, accepted)
    }

    private fun partialLines() {
        if (outputLimited) return
        val out = stdout.pending.length
        val err = stderr.pending.length
        val lines = (if (out > 0) 1 else 0) + (if (err > 0) 1 else 0)
        if (lines > limits.maxLinesPerDispatch - outputLines ||
            out.toLong() + err > limits.maxCharactersPerDispatch - outputCharacters) {
            if (out > 0) line(RuntimeStream.Stdout, stdout.pending.toString())
            stdout.pending.setLength(0)
            if (err > 0) line(RuntimeStream.Stderr, stderr.pending.toString())
            stderr.pending.setLength(0)
            outputLimited = true
        }
    }
}
