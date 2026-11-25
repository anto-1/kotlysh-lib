package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Bounds on trace metadata. The output fields remain a compatibility fallback
 * for explicitly installed trace contexts; prefer [withRuntimeOutputLimits].
 * All runtimes capture bounded output even when tracing is disabled.
 * [maxCommandCharacters] charges at least one unit per argument, including empty ones.
 */
data class RuntimeTraceLimits(
    val maxOutputLinesPerDispatch: Int = 10_000,
    val maxOutputCharactersPerDispatch: Int = 1_000_000,
    val maxScriptPreviewCharacters: Int = 4_096,
    val maxCommandCharacters: Int = 1_024,
) {
    init {
        require(maxOutputLinesPerDispatch > 0) { "Maximum output lines must be positive" }
        require(maxOutputCharactersPerDispatch > 0) { "Maximum output characters must be positive" }
        require(maxScriptPreviewCharacters >= 0) { "Maximum script preview characters must not be negative" }
        require(maxCommandCharacters > 0) { "Maximum command characters must be positive" }
    }
}

/** One immutable runtime lifecycle or dispatch event. */
sealed interface RuntimeTraceEvent {
    val sessionId: String
    val runtime: String
    val atEpochMillis: Long
}

data class RuntimeSessionStarted(
    override val sessionId: String,
    override val runtime: String,
    override val atEpochMillis: Long,
    val processId: Long,
    val command: List<String>,
    val commandTruncated: Boolean = false,
) : RuntimeTraceEvent {
    constructor(sessionId: String, runtime: String, atEpochMillis: Long, processId: Long, command: List<String>) :
        this(sessionId, runtime, atEpochMillis, processId, command, false)

    /** Preserve the original Java-source and JVM copy signature. */
    fun copy(
        sessionId: String, runtime: String, atEpochMillis: Long, processId: Long, command: List<String>,
    ): RuntimeSessionStarted = copy(sessionId, runtime, atEpochMillis, processId, command, commandTruncated)

    companion object {
        @Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
        @JvmStatic
        @JvmName("copy\$default")
        @Suppress("UNUSED_PARAMETER")
        fun legacyCopyDefault(
            original: RuntimeSessionStarted,
            sessionId: String?, runtime: String?, atEpochMillis: Long, processId: Long, command: List<String>?,
            mask: Int, marker: Any?,
        ): RuntimeSessionStarted = original.copy(
            if (mask and 1 != 0) original.sessionId else checkNotNull(sessionId),
            if (mask and 2 != 0) original.runtime else checkNotNull(runtime),
            if (mask and 4 != 0) original.atEpochMillis else atEpochMillis,
            if (mask and 8 != 0) original.processId else processId,
            if (mask and 16 != 0) original.command else checkNotNull(command),
        )
    }
}

data class RuntimeDispatchStarted(
    override val sessionId: String,
    override val runtime: String,
    override val atEpochMillis: Long,
    val dispatchId: Long,
    val scriptSha256: String?,
    val scriptPreview: String?,
    val operation: String = "exec",
    val inputSessionIds: List<String> = emptyList(),
) : RuntimeTraceEvent

data class RuntimeDispatchOutput(
    override val sessionId: String,
    override val runtime: String,
    override val atEpochMillis: Long,
    val dispatchId: Long,
    val sequence: Int,
    val stream: RuntimeStream,
    val text: String,
) : RuntimeTraceEvent

data class RuntimeDispatchCompleted @JvmOverloads constructor(
    override val sessionId: String,
    override val runtime: String,
    override val atEpochMillis: Long,
    val dispatchId: Long,
    val elapsedMillis: Long,
    val exitCode: Int?,
    val timedOut: Boolean,
    val idleTimedOut: Boolean,
    val outputLimited: Boolean,
    val processExited: Boolean,
    val stdoutLines: Int,
    val stderrLines: Int,
    val errorType: String? = null,
    val message: String? = null,
) : RuntimeTraceEvent {
    /** Preserve the original Java-source and JVM copy signature. */
    fun copy(
        sessionId: String, runtime: String, atEpochMillis: Long, dispatchId: Long,
        elapsedMillis: Long, exitCode: Int?, timedOut: Boolean, idleTimedOut: Boolean,
        outputLimited: Boolean, processExited: Boolean, stdoutLines: Int, stderrLines: Int,
    ): RuntimeDispatchCompleted = copy(
        sessionId, runtime, atEpochMillis, dispatchId, elapsedMillis, exitCode,
        timedOut, idleTimedOut, outputLimited, processExited, stdoutLines, stderrLines,
        errorType, message,
    )

    companion object {
        @Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
        @JvmStatic
        @JvmName("copy\$default")
        @Suppress("UNUSED_PARAMETER")
        fun legacyCopyDefault(
            original: RuntimeDispatchCompleted,
            sessionId: String?, runtime: String?, atEpochMillis: Long, dispatchId: Long,
            elapsedMillis: Long, exitCode: Int?, timedOut: Boolean, idleTimedOut: Boolean,
            outputLimited: Boolean, processExited: Boolean, stdoutLines: Int, stderrLines: Int,
            mask: Int, marker: Any?,
        ): RuntimeDispatchCompleted = original.copy(
            if (mask and 1 != 0) original.sessionId else checkNotNull(sessionId),
            if (mask and 2 != 0) original.runtime else checkNotNull(runtime),
            if (mask and 4 != 0) original.atEpochMillis else atEpochMillis,
            if (mask and 8 != 0) original.dispatchId else dispatchId,
            if (mask and 16 != 0) original.elapsedMillis else elapsedMillis,
            if (mask and 32 != 0) original.exitCode else exitCode,
            if (mask and 64 != 0) original.timedOut else timedOut,
            if (mask and 128 != 0) original.idleTimedOut else idleTimedOut,
            if (mask and 256 != 0) original.outputLimited else outputLimited,
            if (mask and 512 != 0) original.processExited else processExited,
            if (mask and 1024 != 0) original.stdoutLines else stdoutLines,
            if (mask and 2048 != 0) original.stderrLines else stderrLines,
        )
    }
}

data class RuntimeDispatchFailed @JvmOverloads constructor(
    override val sessionId: String,
    override val runtime: String,
    override val atEpochMillis: Long,
    val dispatchId: Long,
    val elapsedMillis: Long,
    val errorType: String,
    val message: String?,
    val stdoutLines: Int? = null,
    val stderrLines: Int? = null,
) : RuntimeTraceEvent {
    /** Preserve the original Java-source and JVM copy signature. */
    fun copy(
        sessionId: String, runtime: String, atEpochMillis: Long, dispatchId: Long,
        elapsedMillis: Long, errorType: String, message: String?,
    ): RuntimeDispatchFailed = copy(
        sessionId, runtime, atEpochMillis, dispatchId, elapsedMillis, errorType, message,
        stdoutLines, stderrLines,
    )

    companion object {
        @Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
        @JvmStatic
        @JvmName("copy\$default")
        @Suppress("UNUSED_PARAMETER")
        fun legacyCopyDefault(
            original: RuntimeDispatchFailed,
            sessionId: String?, runtime: String?, atEpochMillis: Long, dispatchId: Long,
            elapsedMillis: Long, errorType: String?, message: String?, mask: Int, marker: Any?,
        ): RuntimeDispatchFailed = original.copy(
            if (mask and 1 != 0) original.sessionId else checkNotNull(sessionId),
            if (mask and 2 != 0) original.runtime else checkNotNull(runtime),
            if (mask and 4 != 0) original.atEpochMillis else atEpochMillis,
            if (mask and 8 != 0) original.dispatchId else dispatchId,
            if (mask and 16 != 0) original.elapsedMillis else elapsedMillis,
            if (mask and 32 != 0) original.errorType else checkNotNull(errorType),
            if (mask and 64 != 0) original.message else message,
        )
    }
}

data class RuntimeSessionClosed(
    override val sessionId: String,
    override val runtime: String,
    override val atEpochMillis: Long,
    val processId: Long?,
    val poisoned: Boolean,
) : RuntimeTraceEvent

fun interface RuntimeTraceSink {
    /**
     * Different sessions may call concurrently. One ProcessRuntime serializes
     * events without holding its cleanup locks; closing it from a callback is
     * supported. Events produced during that callback are delivered afterward.
     * Ordinary exceptions are isolated; fatal errors reach the delivering caller.
     * During cleanup of an existing failure, that failure retains the cleanup
     * error as a suppressed exception instead of being replaced by it.
     * A dispatch keeps its first terminal fact if its completion observer fails;
     * it needs no second terminal record or failure-message diagnostics.
     */
    fun onEvent(event: RuntimeTraceEvent)
}

const val RUNTIME_TRACE_JSONL_PREFIX: String = "@@kotlysh "
const val RUNTIME_TRACE_PROTOCOL_VERSION: Int = 1
const val RUNTIME_TRACE_JSONL_PROPERTY: String = "kotlysh.trace.jsonl"
internal const val RUNTIME_TRACE_DIAGNOSTIC_LIMIT: Int = 4_096

/** IDE/CLI opt-in; an explicit coroutine trace context takes precedence. */
internal fun defaultRuntimeTraceContext(): RuntimeTraceContext? =
    if (java.lang.Boolean.getBoolean(RUNTIME_TRACE_JSONL_PROPERTY)) AutomaticRuntimeTrace.context else null

private object AutomaticRuntimeTrace {
    // One sink serializes automatic records across independent sessions. Resolve
    // stdout at publication time so embedding applications can redirect it.
    // Start a physical line even after an unterminated ordinary task print.
    val context = RuntimeTraceContext(RuntimeJsonlTraceSink { System.out.println("\n$it") }, RuntimeTraceLimits())
}

/**
 * JSONL adapter used by command-line runners and IDE process listeners.
 * Calls to [writeLine] are serialized within this sink across runtime sessions.
 * Concurrent and reentrant publications enqueue and return while a writer is active.
 * Pending records are bounded by count and UTF-16 characters. Overflow drops
 * new records, increments [droppedEventCount], and emits a protocol-v1
 * `trace-dropped` notice through the same serialized writer. Loss never blocks
 * another publisher or changes runtime execution.
 * Writer callbacks run outside the queue lock; the active publisher drains the queue.
 * Ordinary writer failures drain that queue, then reach the active publisher;
 * a fatal writer failure stops delivery and discards the remaining queue.
 */
class RuntimeJsonlTraceSink(
    val maxPendingEvents: Int = 1_024,
    val maxPendingCharacters: Int = 1_048_576,
    private val writeLine: (String) -> Unit,
) : RuntimeTraceSink {
    constructor(writeLine: (String) -> Unit) : this(1_024, 1_048_576, writeLine)

    init {
        require(maxPendingEvents > 0) { "maxPendingEvents must be positive" }
        require(maxPendingCharacters > 0) { "maxPendingCharacters must be positive" }
    }

    private val writeLock = Any()
    private val pendingLines = java.util.ArrayDeque<String>()
    private var pendingCharacters = 0
    private var droppedEvents = 0L
    private var unreportedDrops = 0L
    private var writing = false

    /** Publications omitted by queue admission, saturating at [Long.MAX_VALUE]. */
    val droppedEventCount: Long get() = synchronized(writeLock) { droppedEvents }

    override fun onEvent(event: RuntimeTraceEvent) {
        val availableCharacters = synchronized(writeLock) {
            // Full queues need not serialize discarded producer-owned metadata.
            if (pendingLines.size >= maxPendingEvents) {
                recordDrop()
                return
            }
            maxPendingCharacters - pendingCharacters
        }
        // Bound caller-owned payloads before copying lists or constructing JSON.
        // The final wire-size check also charges field syntax and JSON escaping.
        val snapshot = event.snapshotWithin(availableCharacters, forWire = true)
        val line = snapshot?.let {
            RUNTIME_TRACE_JSONL_PREFIX + it.event.toTraceJson().toString().escapeUnpairedSurrogates()
        }
        synchronized(writeLock) {
            if (line == null || pendingLines.size >= maxPendingEvents || line.length > maxPendingCharacters - pendingCharacters) {
                recordDrop()
            } else {
                pendingLines.addLast(line)
                pendingCharacters += line.length
            }
            if (writing) return
            writing = true
        }
        var firstFailure: Exception? = null
        try {
            while (true) {
                val next = synchronized(writeLock) {
                    if (unreportedDrops > 0) {
                        val count = unreportedDrops
                        unreportedDrops = 0
                        RUNTIME_TRACE_JSONL_PREFIX + "{\"protocolVersion\":1,\"event\":\"trace-dropped\"," +
                            "\"sessionId\":\"@transport\",\"runtime\":\"kotlysh\"," +
                            "\"atEpochMillis\":${System.currentTimeMillis().coerceAtLeast(0)},\"droppedEvents\":$count}"
                    } else {
                        pendingLines.pollFirst().also {
                            if (it == null) writing = false else pendingCharacters -= it.length
                        }
                    }
                } ?: break
                try {
                    writeLine(next)
                } catch (failure: Exception) {
                    val previous = firstFailure
                    if (previous == null) firstFailure = failure
                    else if (previous !== failure && previous.suppressed.size < 16 && previous.suppressed.none { it === failure }) {
                        previous.addSuppressed(failure)
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(writeLock) {
                pendingLines.clear()
                pendingCharacters = 0
                unreportedDrops = 0
                writing = false
            }
            // A queued fatal failure must not become an ordinary sink
            // exception which ProcessRuntime would deliberately isolate.
            val previous = firstFailure
            if (previous != null && previous !== failure && failure.suppressed.none { it === previous }) {
                failure.addSuppressed(previous)
            }
            throw failure
        }
        // Idle handoff already released ownership under the lock. A new
        // publisher may own the queue now, so normal completion must not clear it.
        firstFailure?.let { throw it }
    }

    /** Called only while holding [writeLock]. */
    private fun recordDrop() {
        if (droppedEvents < Long.MAX_VALUE) droppedEvents++
        if (unreportedDrops < Long.MAX_VALUE) unreportedDrops++
    }
}

fun RuntimeTraceEvent.toTraceJson(): JsonObject = buildJsonObject {
    this@toTraceJson.validateTraceScalars()
    put("protocolVersion", RUNTIME_TRACE_PROTOCOL_VERSION)
    put("event", this@toTraceJson.eventName)
    put("sessionId", sessionId)
    put("runtime", runtime)
    put("atEpochMillis", atEpochMillis)
    when (val event = this@toTraceJson) {
        is RuntimeSessionStarted -> {
            put("processId", event.processId)
            put("command", JsonArray(event.command.map {
                // Java's List<String> can contain nulls; they are invalid wire array items.
                JsonPrimitive(requireTraceMetadata(it, identities = false))
            }))
            if (event.commandTruncated) put("commandTruncated", true)
        }
        is RuntimeDispatchStarted -> {
            put("dispatchId", event.dispatchId)
            putNullable("scriptSha256", event.scriptSha256)
            putNullable("scriptPreview", event.scriptPreview)
            put("operation", event.operation)
            put("inputSessionIds", JsonArray(event.inputSessionIds.map {
                JsonPrimitive(requireTraceMetadata(it, identities = true))
            }))
        }
        is RuntimeDispatchOutput -> {
            put("dispatchId", event.dispatchId)
            put("sequence", event.sequence)
            put("stream", event.stream.name.lowercase())
            put("text", event.text)
        }
        is RuntimeDispatchCompleted -> {
            put("dispatchId", event.dispatchId)
            put("elapsedMillis", event.elapsedMillis)
            if (event.exitCode == null) put("exitCode", JsonNull) else put("exitCode", event.exitCode)
            put("timedOut", event.timedOut)
            put("idleTimedOut", event.idleTimedOut)
            put("outputLimited", event.outputLimited)
            put("processExited", event.processExited)
            put("stdoutLines", event.stdoutLines)
            put("stderrLines", event.stderrLines)
            putNullable("errorType", event.errorType)
            putNullable("message", event.message)
        }
        is RuntimeDispatchFailed -> {
            put("dispatchId", event.dispatchId)
            put("elapsedMillis", event.elapsedMillis)
            put("errorType", event.errorType)
            putNullable("message", event.message)
            event.stdoutLines?.let { put("stdoutLines", it) }
            event.stderrLines?.let { put("stderrLines", it) }
        }
        is RuntimeSessionClosed -> {
            if (event.processId == null) put("processId", JsonNull) else put("processId", event.processId)
            put("poisoned", event.poisoned)
        }
    }
}

private fun RuntimeTraceEvent.validateTraceScalars() {
    require(sessionId.isNotBlank()) { "Trace sessionId must not be blank" }
    require(runtime.isNotBlank()) { "Trace runtime must not be blank" }
    require(atEpochMillis >= 0) { "Trace atEpochMillis must not be negative" }
    when (this) {
        is RuntimeSessionStarted -> require(processId > 0) { "Trace processId must be positive" }
        is RuntimeDispatchStarted -> require(dispatchId > 0) { "Trace dispatchId must be positive" }
        is RuntimeDispatchOutput -> {
            require(dispatchId > 0) { "Trace dispatchId must be positive" }
            require(sequence >= 0) { "Trace sequence must not be negative" }
        }
        is RuntimeDispatchCompleted -> {
            require(dispatchId > 0) { "Trace dispatchId must be positive" }
            require(elapsedMillis >= 0) { "Trace elapsedMillis must not be negative" }
            requireTraceLineCounts(stdoutLines, stderrLines)
        }
        is RuntimeDispatchFailed -> {
            require(dispatchId > 0) { "Trace dispatchId must be positive" }
            require(elapsedMillis >= 0) { "Trace elapsedMillis must not be negative" }
            requireTraceLineCounts(stdoutLines, stderrLines)
        }
        is RuntimeSessionClosed -> require(processId == null || processId > 0) {
            "Trace processId must be positive when recorded"
        }
    }
}

private fun requireTraceLineCounts(stdout: Int?, stderr: Int?) {
    require(stdout == null || stdout >= 0) { "Trace stdoutLines must not be negative" }
    require(stderr == null || stderr >= 0) { "Trace stderrLines must not be negative" }
    require((stdout?.toLong() ?: 0L) + (stderr?.toLong() ?: 0L) <= Int.MAX_VALUE) {
        "Trace output line total exceeds sequence capacity"
    }
}

private fun requireTraceMetadata(value: String?, identities: Boolean): String {
    val item = requireNotNull(value) {
        if (identities) "Trace input session identities must not be null" else "Trace command arguments must not be null"
    }
    require(!identities || item.isNotBlank()) { "Trace input session identities must not be blank" }
    return item
}

private val RuntimeTraceEvent.eventName: String
    get() = when (this) {
        is RuntimeSessionStarted -> "session-started"
        is RuntimeDispatchStarted -> "dispatch-started"
        is RuntimeDispatchOutput -> "dispatch-output"
        is RuntimeDispatchCompleted -> "dispatch-completed"
        is RuntimeDispatchFailed -> "dispatch-failed"
        is RuntimeSessionClosed -> "session-closed"
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private data class RuntimeTraceSnapshot(val event: RuntimeTraceEvent, val characters: Int)

/** Bounds a publication before allocating owned metadata. Caller callbacks stay outside sink locks. */
private fun RuntimeTraceEvent.snapshotWithin(maxCharacters: Int, forWire: Boolean = false): RuntimeTraceSnapshot? {
    // A unit per event and list entry also bounds arrays of empty strings.
    var characters = 1
    if (maxCharacters < characters) return null
    fun charge(value: String?): Boolean {
        val size = value?.length ?: 0
        if (size > maxCharacters - characters) return false
        characters += size
        return true
    }
    fun snapshot(values: List<String>, identities: Boolean = false): List<String>? {
        val size = values.size
        require(size >= 0) { "Trace collection size must not be negative" }
        if (size > maxCharacters - characters) return null
        characters += size
        val owned = ArrayList<String>(minOf(size, 256))
        for (index in 0 until size) {
            val value = values[index]
            if (!charge(value)) return null
            if (forWire) requireTraceMetadata(value, identities)
            owned.add(value)
        }
        return Collections.unmodifiableList(owned)
    }
    if (!charge(sessionId) || !charge(runtime)) return null
    if (forWire) validateTraceScalars()
    val event = when (this) {
        is RuntimeSessionStarted -> copy(command = snapshot(command) ?: return null)
        is RuntimeDispatchStarted -> {
            if (!charge(scriptSha256) || !charge(scriptPreview) || !charge(operation)) return null
            copy(inputSessionIds = snapshot(inputSessionIds, identities = true) ?: return null)
        }
        is RuntimeDispatchOutput -> {
            if (!charge(text)) return null
            this
        }
        is RuntimeDispatchCompleted -> {
            if (!charge(errorType) || !charge(message)) return null
            this
        }
        is RuntimeDispatchFailed -> {
            if (!charge(errorType) || !charge(message)) return null
            this
        }
        is RuntimeSessionClosed -> this
    }
    return RuntimeTraceSnapshot(event, characters)
}

/**
 * Thread-safe trace collector suitable for tests, CLIs, and IDE adapters.
 * Collected command and lineage lists are owned snapshots; returned lists are read-only.
 * By default at most 4,096 events and 1,048,576 payload units are retained.
 * [maxCharacters] charges UTF-16 units plus one per event and metadata-list entry.
 * Oversized publications are dropped whole, without changing execution or other sinks.
 */
class RuntimeTraceBuffer(
    val maxEvents: Int = 4_096,
    val maxCharacters: Int,
) : RuntimeTraceSink {
    @JvmOverloads
    constructor(maxEvents: Int = 4_096) : this(maxEvents, 1_048_576)

    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
        require(maxCharacters > 0) { "maxCharacters must be positive" }
    }

    private val collected = mutableListOf<RuntimeTraceEvent>()
    private var retainedCharacters = 0
    private var droppedEvents = 0L

    val events: List<RuntimeTraceEvent>
        get() = synchronized(collected) { collected.immutableSnapshot() }

    /** Number of publications omitted by the retention limit, saturating at [Long.MAX_VALUE]. */
    val droppedEventCount: Long
        get() = synchronized(collected) { droppedEvents }

    override fun onEvent(event: RuntimeTraceEvent) {
        val availableCharacters = synchronized(collected) {
            if (dropIfFull()) return
            maxCharacters - retainedCharacters
        }
        // Snapshot producer-owned collections outside the lock. Their callbacks
        // may publish reentrantly or coordinate with another producer thread.
        val snapshot = event.snapshotWithin(availableCharacters)
        synchronized(collected) {
            if (snapshot == null) recordDrop()
            else if (!dropIfFull()) {
                if (snapshot.characters > maxCharacters - retainedCharacters) recordDrop()
                else {
                    collected += snapshot.event
                    retainedCharacters += snapshot.characters
                }
            }
        }
    }

    /** Called only while holding the collection lock. */
    private fun dropIfFull(): Boolean {
        if (collected.size < maxEvents && retainedCharacters < maxCharacters) return false
        recordDrop()
        return true
    }

    private fun recordDrop() {
        if (droppedEvents < Long.MAX_VALUE) droppedEvents++
    }
}

class RuntimeTraceContext(
    val sink: RuntimeTraceSink,
    val limits: RuntimeTraceLimits,
) : AbstractCoroutineContextElement(RuntimeTraceContext) {
    companion object Key : CoroutineContext.Key<RuntimeTraceContext>
}

/** Installs a trace sink; [withRuntimeOutputLimits] overrides its legacy output fields. */
suspend fun <T> withRuntimeTrace(
    sink: RuntimeTraceSink,
    limits: RuntimeTraceLimits = RuntimeTraceLimits(),
    block: suspend CoroutineScope.() -> T,
): T = withContext(RuntimeTraceContext(sink, limits), block)

internal fun boundedCommand(command: List<String>, maxCharacters: Int): List<String> {
    var remaining = maxCharacters
    val bounded = buildList {
        for (argument in command) {
            if (remaining <= 0) break
            val truncated = argument.length > remaining
            val accepted = if (truncated) {
                var end = remaining - 1
                if (end > 0 && argument[end - 1].isHighSurrogate() && argument[end].isLowSurrogate()) end--
                argument.take(end) + "…"
            } else {
                argument
            }
            add(accepted)
            remaining -= accepted.length.coerceAtLeast(1)
            if (truncated) break
        }
    }
    return Collections.unmodifiableList(ArrayList(bounded))
}
