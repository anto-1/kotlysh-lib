package com.kotlysh.core

import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One dispatch owns its stream framing, quotas and captured output. Calls are serialized by its owner. */
internal class RuntimeDispatchCollector(
    private val runtimeName: String,
    private val marker: String,
    private val outputLimits: RuntimeOutputLimits,
    private val responseLimits: RuntimeResponseLimits,
    private val expectedResponseKind: String?,
    private val onOutput: (RuntimeStream, String) -> Unit,
) {
    private class StreamState(markerPrefix: String) {
        val pending = StringBuilder()
        val output = mutableListOf<String>()
        val responseScan = RuntimeCompletionFrameScanner(markerPrefix)
        val logScan = RuntimeCompletionFrameScanner(markerPrefix, allowIncompleteStatus = true)
        var done = false
        var ended = false

        fun resetResponseFrame() {
            responseScan.reset()
            logScan.reset()
        }
    }

    /** Inspect the next line segment without copying or extending the retained buffer. */
    private class AppendedLine(
        private val pending: CharSequence,
        private val incoming: String,
        private val start: Int,
        private val end: Int,
    ) : CharSequence {
        override val length: Int get() = pending.length + end - start
        override fun get(index: Int): Char =
            if (index < pending.length) pending[index] else incoming[start + index - pending.length]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            buildString(endIndex - startIndex) {
                for (index in startIndex until endIndex) append(this@AppendedLine[index])
            }
    }

    private val markerTag = "__KOTLYSH_"
    private val markerPrefix = "$marker:"
    private val stdout = StreamState(markerPrefix)
    private val stderr = StreamState(markerPrefix)
    private var outputLines = 0
    private var outputCharacters = 0

    var exitCode: Int? = null
        private set
    var response: JsonObject? = null
        private set
    var outputLimited = false
        private set

    private val errorResponse: JsonObject?
        get() = response?.takeIf {
            (it["kind"] as? JsonPrimitive)?.takeIf { kind -> kind.isString }?.content == "error"
        }
    val responseErrorType: String?
        get() = (errorResponse?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val responseErrorMessage: String?
        get() = (errorResponse?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content

    val allDone: Boolean get() = stdout.done && stderr.done
    val allFinished: Boolean get() = (stdout.done || stdout.ended) && (stderr.done || stderr.ended)

    private fun state(stream: RuntimeStream): StreamState = when (stream) {
        RuntimeStream.Stdout -> stdout
        RuntimeStream.Stderr -> stderr
    }

    fun output(stream: RuntimeStream): List<String> = state(stream).output.immutableSnapshot()

    private fun emit(stream: RuntimeStream, text: String) {
        if (outputLimited) return
        val remainingCharacters = outputLimits.maxCharactersPerDispatch - outputCharacters
        val denied = outputLines >= outputLimits.maxLinesPerDispatch ||
            remainingCharacters <= 0 && text.isNotEmpty()
        val accepted = if (denied) "" else text.utf16PrefixWithin(remainingCharacters)
        if (!denied && (accepted.isNotEmpty() || text.isEmpty())) {
            state(stream).output += accepted
            outputLines++
            outputCharacters += accepted.length
            onOutput(stream, accepted)
        }
        if (denied || accepted.length < text.length) outputLimited = true
    }

    private fun completionStart(text: String): Int {
        var start = text.indexOf(markerPrefix)
        while (start >= 0) {
            val statusStart = start + markerPrefix.length
            val statusEnd = text.indexOf(':', statusStart).let { if (it >= 0) it else text.length }
            if (text.substring(statusStart, statusEnd).toIntOrNull() != null) return start
            start = text.indexOf(markerPrefix, start + markerPrefix.length)
        }
        return -1
    }

    private fun unframedCharacters(
        pending: CharSequence,
        minimumMarkerCharacters: Int = 1,
        scanState: StreamState? = null,
    ): Int {
        // CR can arrive before the LF that terminates a frame. Keep a
        // possible marker/status out of logs at that boundary too.
        val end = pending.length - if (pending.lastOrNull() == '\r') 1 else 0
        if (scanState != null) {
            scanState.logScan.scan(pending)?.let { return it }
        } else {
            var markerStart = pending.indexOf(markerPrefix)
            while (markerStart >= 0) {
                val statusStart = markerStart + markerPrefix.length
                val delimiter = pending.indexOf(":", statusStart)
                val statusEnd = if (delimiter >= 0) delimiter else end
                val status = pending.substring(statusStart, statusEnd)
                if (status.toIntOrNull() != null ||
                    delimiter < 0 && (status.isEmpty() || status == "+" || status == "-")) return markerStart
                markerStart = pending.indexOf(markerPrefix, markerStart + markerPrefix.length)
            }
        }
        // A read can end inside the completion marker. Keep that possible
        // suffix out of the log budget until later input resolves it.
        val maxSuffixLength = minOf(end, markerPrefix.length - 1)
        for (length in maxSuffixLength downTo minimumMarkerCharacters) {
            val start = end - length
            if ((0 until length).all { pending[start + it] == markerPrefix[it] }) return start
        }
        return pending.length
    }

    private fun line(stream: RuntimeStream, text: String, atEndOfInput: Boolean = false) {
        val state = state(stream)
        val start = completionStart(text)
        if (start >= 0) {
            val suffix = text.substring(start + marker.length + 1)
            val status = checkNotNull(suffix.substringBefore(':').toIntOrNull())
            // printf/write need not end in a newline before the driver's marker.
            if (start > 0) emit(stream, text.substring(0, start))
            check(exitCode == null || exitCode == status) { "Conflicting completion statuses from ${runtimeName}" }
            exitCode = status
            if (':' in suffix) {
                check(stream == RuntimeStream.Stdout && response == null) { "Duplicate or misplaced runtime response" }
                val encoded = suffix.substringAfter(':')
                check(encoded.length.toLong() <= responseLimits.maxEncodedCharacters) {
                    "${runtimeName} response exceeds the payload byte limit of ${responseLimits.maxPayloadBytes}"
                }
                val bytes = Base64.getDecoder().decode(encoded)
                check(bytes.size <= responseLimits.maxPayloadBytes) {
                    "${runtimeName} response exceeds the payload byte limit of ${responseLimits.maxPayloadBytes}"
                }
                val json = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                responseLimits.checkNesting(json, runtimeName)
                checkRuntimeJsonObjectKeys(json, runtimeName)
                response = checkNotNull(Json.parseToJsonElement(json) as? JsonObject) {
                    "Malformed response payload from ${runtimeName}: expected a JSON object"
                }.also {
                    it.validateRuntimeJsonLiterals(runtimeName)
                    it.validateRuntimeResponse(status, expectedResponseKind, runtimeName)
                }.immutableJsonSnapshot() as JsonObject
            }
            state.done = true
        } else {
            // During collection, even a short possible suffix is held out
            // of the budget. At EOF, preserve ambiguous ordinary text such
            // as a trailing underscore unless the full marker tag is known.
            val log = if (atEndOfInput) text.take(unframedCharacters(text, markerTag.length)) else text
            if (log.isNotEmpty() || text.isEmpty()) emit(stream, log)
        }
    }

    private fun boundResponseFrame(stream: RuntimeStream, state: StreamState, prospective: CharSequence) {
        val frameStart = state.responseScan.scan(prospective)
        // A valid unfinished status is transport, so neither the log nor
        // payload budget bounds it. Check it before growing the line buffer.
        if (state.responseScan.statusCharacters > MAX_COMPLETION_STATUS_CHARACTERS) {
            val start = checkNotNull(state.responseScan.frameStart)
            if (start > 0) emit(stream, prospective.subSequence(0, start).toString())
            error("${runtimeName} completion status exceeds the limit of " +
                "$MAX_COMPLETION_STATUS_CHARACTERS UTF-16 code units")
        }
        if (frameStart == null) return
        val payloadEnd = prospective.length - if (prospective.lastOrNull() == '\r') 1 else 0
        if ((payloadEnd - checkNotNull(state.responseScan.payloadStart)).toLong() > responseLimits.maxEncodedCharacters) {
            if (frameStart > 0) emit(stream, prospective.subSequence(0, frameStart).toString())
            error("${runtimeName} response exceeds the payload byte limit of ${responseLimits.maxPayloadBytes}")
        }
    }

    fun consume(stream: RuntimeStream, text: String) {
        val state = state(stream)
        val pending = state.pending
        var start = 0
        while (start < text.length && !outputLimited) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            boundResponseFrame(stream, state, AppendedLine(pending, text, start, end))
            pending.append(text, start, end)
            if (newline < 0) break
            val textLine = pending.toString().removeSuffix("\r")
            pending.clear()
            state.resetResponseFrame()
            line(stream, textLine)
            start = newline + 1
        }
        if (!outputLimited) boundPendingLogs()
    }

    private fun pendingLogCharacters(state: StreamState): Int {
        val pending = state.pending
        val ending = if (pending.lastOrNull() == '\r') 1 else 0
        return minOf(unframedCharacters(pending, scanState = state), pending.length - ending)
    }

    private fun boundPendingLogs() {
        val out = pendingLogCharacters(stdout)
        val err = pendingLogCharacters(stderr)
        val lines = (if (out > 0) 1 else 0) + (if (err > 0) 1 else 0)
        if (lines > outputLimits.maxLinesPerDispatch - outputLines ||
            out.toLong() + err > outputLimits.maxCharactersPerDispatch - outputCharacters) {
            if (out > 0) emit(RuntimeStream.Stdout, stdout.pending.substring(0, out))
            if (err > 0) emit(RuntimeStream.Stderr, stderr.pending.substring(0, err))
            stdout.pending.clear()
            stderr.pending.clear()
            outputLimited = true
        }
    }

    /** A failed read preserves ordinary output but never decodes unfinished transport. */
    fun readFailed(stream: RuntimeStream, failure: Throwable): Nothing {
        val state = state(stream)
        val pending = state.pending.toString().removeSuffix("\r")
        val log = pending.take(unframedCharacters(pending, markerTag.length))
        try {
            if (log.isNotEmpty()) emit(stream, log)
        } catch (outputFailure: Throwable) {
            failure.suppressOnce(outputFailure)
        }
        state.pending.clear()
        throw failure
    }

    /** Returns whether EOF arrived before this stream's completion frame. */
    fun end(stream: RuntimeStream): Boolean {
        val state = state(stream)
        state.ended = true
        if (state.pending.isNotEmpty()) {
            line(stream, state.pending.toString().removeSuffix("\r"), atEndOfInput = true)
            state.pending.clear()
        }
        return !state.done
    }

    fun flushIncomplete() {
        flushIncomplete(RuntimeStream.Stdout, stdout)
        flushIncomplete(RuntimeStream.Stderr, stderr)
    }

    private fun flushIncomplete(stream: RuntimeStream, state: StreamState) {
        if (state.pending.isNotEmpty()) {
            val text = state.pending.toString()
            val log = text.take(unframedCharacters(text, markerTag.length))
            if (log.isNotEmpty()) emit(stream, log)
        }
    }

    private companion object {
        const val MAX_COMPLETION_STATUS_CHARACTERS = 8 * 1024 * 1024
    }
}
