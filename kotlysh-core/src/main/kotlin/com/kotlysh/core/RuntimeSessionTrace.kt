package com.kotlysh.core

import java.security.MessageDigest

/**
 * Owns trace admission, terminal facts and serialized observer delivery.
 * ProcessRuntime owns physical cleanup. It marks this ledger closing before
 * stopping pipes so later output/completion cannot reopen a terminal session.
 * Observer callbacks run outside publicationLock and may reenter the runtime.
 */
internal class RuntimeSessionTrace(
    private val sessionId: String,
    private val runtime: String,
    private val context: RuntimeTraceContext?,
) {
    private enum class Phase { OPEN, CLOSING, CLOSED }
    @Volatile private var phase = Phase.OPEN
    val isClosing: Boolean get() = phase != Phase.OPEN
    private val publicationLock = Any()
    private val traceEvents = java.util.ArrayDeque<RuntimeTraceEvent>()
    private var traceDraining = false
    private var closingFailure: Throwable? = null
    private data class PendingDispatch(
        val startedAtNanos: Long,
        var stdoutLines: Int = 0,
        var stderrLines: Int = 0,
    )
    private val pendingDispatches = mutableMapOf<Long, PendingDispatch>()
    private var nextDispatchId = 1L

    fun beginClose() = synchronized(publicationLock) {
        if (phase == Phase.OPEN) phase = Phase.CLOSING
    }

    fun rememberClosingFailure(failure: Throwable, onlyWhileOpen: Boolean = false) = synchronized(publicationLock) {
        if ((!onlyWhileOpen || phase == Phase.OPEN) && closingFailure == null) closingFailure = failure
    }

    fun hasPendingDispatch(dispatchId: Long): Boolean = synchronized(publicationLock) {
        context != null && dispatchId in pendingDispatches
    }

    fun startDispatch(
        script: String?,
        operation: String,
        inputSessionIds: List<String>,
        startedAtNanos: Long,
        startedAtEpochMillis: Long,
    ): Long {
        val dispatchId = admitDispatch(script, operation, inputSessionIds, startedAtNanos, startedAtEpochMillis)
        drain()
        return dispatchId
    }

    /** Admit and enqueue atomically; delivery belongs to the caller after admission. */
    private fun admitDispatch(
        script: String?,
        operation: String,
        inputSessionIds: List<String>,
        startedAtNanos: Long,
        startedAtEpochMillis: Long,
    ): Long = synchronized(publicationLock) {
        check(phase == Phase.OPEN) { "${runtime} runtime is closed" }
        val dispatchId = nextDispatchId++
        context?.let { context ->
            pendingDispatches[dispatchId] = PendingDispatch(startedAtNanos)
            enqueue(RuntimeDispatchStarted(
                sessionId = sessionId,
                runtime = runtime,
                atEpochMillis = startedAtEpochMillis,
                dispatchId = dispatchId,
                scriptSha256 = script?.sha256(),
                scriptPreview = script?.utf16PrefixWithin(context.limits.maxScriptPreviewCharacters),
                operation = operation,
                inputSessionIds = inputSessionIds.immutableSnapshot(),
            ))
        }
        dispatchId
    }

    /** Record an expired queued request before closure can publish its terminal facts. */
    fun expiredBeforeSubmission(
        script: String?,
        operation: String,
        startedAtNanos: Long,
        startedAtEpochMillis: Long,
    ): RuntimeDispatchFailed = synchronized(publicationLock) {
        val dispatchId = admitDispatch(script, operation, emptyList(), startedAtNanos, startedAtEpochMillis)
        val failure = RuntimeDispatchFailed(
            sessionId = sessionId,
            runtime = runtime,
            atEpochMillis = System.currentTimeMillis(),
            dispatchId = dispatchId,
            elapsedMillis = elapsedMillisSince(startedAtNanos),
            errorType = "kotlinx.coroutines.TimeoutCancellationException",
            message = "Deadline expired before submission to the session; the request was not executed.",
        )
        enqueue(failure)
        failure
    }

    fun publish(event: RuntimeTraceEvent) {
        enqueue(event)
        drain()
    }

    fun enqueue(event: RuntimeTraceEvent) {
        synchronized(publicationLock) {
            if (phase == Phase.CLOSED) return
            if (phase != Phase.OPEN && event !is RuntimeSessionClosed && event !is RuntimeDispatchFailed) return
            var publication = event
            when (event) {
                is RuntimeDispatchOutput -> pendingDispatches[event.dispatchId]?.let { pending ->
                    when (event.stream) {
                        RuntimeStream.Stdout -> pending.stdoutLines++
                        RuntimeStream.Stderr -> pending.stderrLines++
                    }
                }
                is RuntimeDispatchCompleted -> if (pendingDispatches.remove(event.dispatchId) == null) return
                is RuntimeDispatchFailed -> {
                    val pending = pendingDispatches.remove(event.dispatchId) ?: return
                    publication = event.copy(stdoutLines = pending.stdoutLines, stderrLines = pending.stderrLines)
                }
                is RuntimeSessionClosed -> {
                    // A caller can close the session while a dispatch is still
                    // receiving output. Finish its trace before publishing closure;
                    // the receiver's later failure must not reopen the session.
                    for ((dispatchId, pending) in pendingDispatches.toMap()) {
                        enqueue(RuntimeDispatchFailed(
                            sessionId = sessionId,
                            runtime = runtime,
                            atEpochMillis = event.atEpochMillis,
                            dispatchId = dispatchId,
                            elapsedMillis = elapsedMillisSince(pending.startedAtNanos),
                            errorType = "java.lang.IllegalStateException",
                            message = "Runtime session closed before dispatch completion.",
                        ))
                    }
                    phase = Phase.CLOSED
                }
                else -> Unit
            }
            if (context != null) traceEvents.addLast(publication)
        }
    }

    fun drain() {
        synchronized(publicationLock) {
            if (traceDraining || traceEvents.isEmpty()) return
            traceDraining = true
        }
        try {
            while (true) {
                val event = synchronized(publicationLock) {
                    traceEvents.pollFirst().also { if (it == null) traceDraining = false }
                } ?: return
                try {
                    context?.sink?.onEvent(event)
                } catch (_: Exception) {
                    // Telemetry remains observational. Fatal errors propagate
                    // to the caller delivering the event, after releasing guards.
                } catch (failure: Throwable) {
                    if (event is RuntimeSessionClosed) {
                        // Another closer may drain this record before the caller
                        // which initiated failure cleanup resumes on its dispatcher.
                        synchronized(publicationLock) { closingFailure }?.suppressOnce(failure)
                    }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            synchronized(publicationLock) { traceDraining = false }
            throw failure
        }
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal fun Throwable.suppressOnce(other: Throwable) {
    if (other === this) return
    synchronized(this) {
        if (suppressed.none { it === other }) addSuppressed(other)
    }
}
