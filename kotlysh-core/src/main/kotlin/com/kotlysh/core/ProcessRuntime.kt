package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** One owned interpreter, with serialized requests and independent output readers. */
class ProcessRuntime(
    private val ownerScope: CoroutineScope,
    private val spec: RuntimeSpec,
) : Closeable {
    private sealed interface Event {
        data class Chunk(val stream: RuntimeStream, val text: String) : Event
        data class End(val stream: RuntimeStream, val error: IOException? = null) : Event
        data class WriteFailed(val error: IOException) : Event
    }

    private val execMutex = Mutex()
    private val closeMutex = Mutex()
    // An elastic IO view has its own permit, so a blocked native writer cannot
    // starve the cleanup which must terminate its process to unblock it.
    private val cleanupDispatcher = Dispatchers.IO.limitedParallelism(1)
    // A blocked native write must not occupy the managed DSL's ordinary IO
    // permit and starve dispatch deadlines or delivery of queued results.
    private val writerDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val events = Channel<Event>(64)
    private val requests = Channel<String>(1)
    private val ioJobs = mutableListOf<Job>()
    private var writerJob: Job? = null
    internal val sessionId = UUID.randomUUID().toString()
    private val traceContext = ownerScope.coroutineContext[RuntimeTraceContext] ?: defaultRuntimeTraceContext()
    private val outputLimits = ownerScope.coroutineContext.runtimeOutputLimits()
    private val responseLimits = ownerScope.coroutineContext[RuntimeResponseLimitsContext]?.limits ?: RuntimeResponseLimits()
    private val tracing = RuntimeSessionTrace(sessionId, spec.name, traceContext)
    @Volatile private var process: Process? = null
    @Volatile private var poisoned = false
    private val closed: Boolean get() = tracing.isClosing

    /** The exact interpreter started by this session, for diagnostics. */
    val processId: Long? get() = process?.pid()
    internal val name: String get() = spec.name
    internal val jsonNumberPolicy: RuntimeJsonNumberPolicy get() = spec.jsonNumberPolicy
    internal val isOpen: Boolean get() = !closed && !poisoned && process?.isAlive == true

    suspend fun start() {
        var tookOwnership = false
        try {
            withContext(Dispatchers.IO) {
                // Closing cannot finish before ownership of a starting process is recorded.
                closeMutex.withLock {
                    check(process == null && !closed) { "${spec.name} runtime cannot be started twice" }
                    ownerScope.coroutineContext.ensureActive()
                    // The caller may retain a mutable specification list. Launch
                    // and telemetry must describe the same acquired argument vector.
                    val command = spec.command.immutableSnapshot()
                    require(command.isNotEmpty()) { "${spec.name} runtime command must not be empty" }
                    command.forEachIndexed { index, argument ->
                        requireNativeText(argument, "Runtime command argument $index")
                    }
                    // Argument capture can execute caller-provided collection
                    // code. Recheck both scopes before acquiring a process.
                    coroutineContext.ensureActive()
                    ownerScope.coroutineContext.ensureActive()
                    val started = ProcessBuilder(command).start()
                    process = started
                    tookOwnership = true
                    pump(started, started.inputStream, RuntimeStream.Stdout)
                    pump(started, started.errorStream, RuntimeStream.Stderr)
                    val writer = ownerScope.launch(writerDispatcher) {
                        try {
                            // Native pipes belong to closeSuspending. Closing
                            // stdin here on cancellation can orphan descendants
                            // before the session has captured their identities.
                            val output = started.outputStream.writer(Charsets.UTF_8)
                            for (request in requests) {
                                if (closed) break
                                output.write(request)
                                output.flush()
                            }
                        } catch (error: IOException) {
                            events.send(Event.WriteFailed(error))
                        }
                    }
                    writerJob = writer
                    ioJobs += writer
                    val traceCommand = boundedCommand(
                        command,
                        traceContext?.limits?.maxCommandCharacters ?: RuntimeTraceLimits().maxCommandCharacters,
                    )
                    tracing.enqueue(RuntimeSessionStarted(
                        sessionId = sessionId,
                        runtime = spec.name,
                        atEpochMillis = System.currentTimeMillis(),
                        processId = started.pid(),
                        command = traceCommand,
                        commandTruncated = traceCommand != command,
                    ))
                }
                // User callbacks may synchronously close this runtime. Publish
                // after releasing the process-ownership lock and recheck state.
                tracing.drain()
                ownerScope.coroutineContext.ensureActive()
                check(!closed && !poisoned) { "${spec.name} runtime closed during startup" }
            }
        } catch (failure: Throwable) {
            // A rejected second start owns no new process. Failures after launch,
            // including cancellation when returning from IO, must close this one.
            if (tookOwnership) {
                poisoned = true
                closeAfterFailure(failure)
            }
            throw failure
        }
    }

    suspend fun exec(
        script: String,
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        output: RuntimeOutputSink? = null,
    ): RuntimeResult {
        script.requireUtf8Source()
        return dispatch(
            RuntimeDispatchPlan(
                wire = { marker -> spec.wrap(script, marker) },
                traceScript = script,
            ),
            timeout,
            idleTimeout,
            output,
        )
    }

    internal suspend fun dispatchPrepared(
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        output: RuntimeOutputSink? = null,
        prepare: RuntimeSpec.() -> RuntimeDispatchPlan,
    ): RuntimeResult = dispatch(spec.prepare(), timeout, idleTimeout, output)

    private suspend fun dispatch(
        plan: RuntimeDispatchPlan,
        timeout: Duration,
        idleTimeout: Duration,
        output: RuntimeOutputSink?,
    ): RuntimeResult {
        val startedAtNanos = System.nanoTime()
        val startedAtEpochMillis = System.currentTimeMillis()
        require(timeout.isPositive() && idleTimeout.isPositive()) { "Timeouts must be positive" }
        val child = process ?: error("${spec.name} runtime has not been started")
        var locked = false
        try {
            val admitted = withTimeoutOrNull(timeout) {
                execMutex.lock()
                locked = true
                true
            } ?: false
            if (!admitted || !(timeout - elapsedDurationSince(startedAtNanos)).isPositive()) {
                return queuedTimeout(
                    child, startedAtNanos, startedAtEpochMillis, plan.traceScript, plan.traceOperation,
                )
            }
            return dispatchLocked(
                plan, timeout, idleTimeout, output, startedAtNanos, startedAtEpochMillis,
            )
        } finally {
            // A deadline can race the successful lock acquisition before the
            // timeout scope returns, so retain explicit ownership of the lock.
            if (locked) execMutex.unlock()
        }
    }

    private suspend fun dispatchLocked(
        plan: RuntimeDispatchPlan,
        timeout: Duration,
        idleTimeout: Duration,
        output: RuntimeOutputSink?,
        startedAtNanos: Long,
        startedAtEpochMillis: Long,
    ): RuntimeResult {
        val child = process ?: error("${spec.name} runtime has not been started")
        check(!closed && !poisoned) { "${spec.name} runtime is closed after timeout/cancellation or process exit" }
        check(child.isAlive) { "${spec.name} runtime process has exited" }
        // Binding errors are rejected before submission and do not poison a session.
        plan.validateBeforeSubmission()
        if (!(timeout - elapsedDurationSince(startedAtNanos)).isPositive()) {
            return queuedTimeout(
                child, startedAtNanos, startedAtEpochMillis, plan.traceScript, plan.traceOperation,
            )
        }

        val dispatchId = try {
            tracing.startDispatch(
                plan.traceScript, plan.traceOperation, plan.traceInputSessionIds,
                startedAtNanos, startedAtEpochMillis,
            )
        } catch (failure: Throwable) {
            // Starting telemetry runs before submission. Its pending dispatch
            // still needs a terminal event, and its owned session must close.
            poisoned = true
            closeAfterFailure(failure)
            throw failure
        }

        val marker = "__KOTLYSH_${UUID.randomUUID().toString().replace("-", "")}__"
        var idleTimedOut = false
        var processExited = false
        var outputSequence = 0

        fun deliver(stream: RuntimeStream, accepted: String) {
            // Retention is an observed fact even if the consumer fails or
            // closes the session. Queue it before running caller code, while
            // preserving the normal consumer-before-observer callback order.
            tracing.enqueue(RuntimeDispatchOutput(
                sessionId = sessionId,
                runtime = spec.name,
                atEpochMillis = System.currentTimeMillis(),
                dispatchId = dispatchId,
                sequence = outputSequence++,
                stream = stream,
                text = accepted,
            ))
            try {
                output?.onLine(RuntimeOutputLine(spec.name, stream, accepted))
            } catch (failure: Throwable) {
                try {
                    tracing.drain()
                } catch (observerFailure: Throwable) {
                    if (observerFailure !== failure) failure.addSuppressed(observerFailure)
                }
                throw failure
            }
            tracing.drain()
        }
        val collected = RuntimeDispatchCollector(
            spec.name, marker, outputLimits, responseLimits, plan.expectedResponseKind, ::deliver,
        )

        return try {
            check(!closed && !poisoned) { "${spec.name} runtime closed before dispatch submission" }
            val completed = withTimeoutOrNull(timeout - elapsedDurationSince(startedAtNanos)) {
                // Writing happens on its own I/O worker, so a blocked stdin write is
                // covered by the same deadline as execution and output collection.
                requests.send(plan.wire(marker))
                while (!collected.allDone) {
                    val event = withTimeoutOrNull(idleTimeout) { events.receive() }
                    if (event == null) {
                        idleTimedOut = true
                        break
                    }
                    when (event) {
                        is Event.Chunk -> collected.consume(event.stream, event.text)
                        is Event.End -> {
                            // A failed read remains a failure even if the process exits later.
                            event.error?.let { collected.readFailed(event.stream, it) }
                            if (collected.end(event.stream)) processExited = true
                        }
                        is Event.WriteFailed -> throw event.error
                    }
                    if (collected.outputLimited) break
                    if (collected.allFinished) break
                }
                true
            } ?: false

            val mustClose = !completed || idleTimedOut || collected.outputLimited || processExited
            var exitCode = collected.exitCode
            val response = collected.response
            if (!mustClose && exitCode == 0 && plan.responseRequired) {
                check(response != null) { "Missing structured response from ${spec.name}" }
            }
            if (mustClose) {
                poisoned = true
                if (processExited && child.waitFor(100, TimeUnit.MILLISECONDS)) {
                    exitCode = child.exitValue()
                }
                // The collector excludes unfinished transport from partial output.
                collected.flushIncomplete()
            }

            val result = RuntimeResult(
                runtime = spec.name,
                stdout = collected.output(RuntimeStream.Stdout),
                stderr = collected.output(RuntimeStream.Stderr),
                exitCode = exitCode,
                timedOut = !completed,
                idleTimedOut = idleTimedOut,
                outputLimited = collected.outputLimited,
                processExited = processExited,
                sessionId = sessionId,
                dispatchId = dispatchId,
                elapsedMillis = elapsedMillisSince(startedAtNanos),
                response = response,
            )
            // The successful response has been validated and this session's
            // admission lock is still held. Observers and resumed waiters must
            // see the caller's successful transition before execution resumes.
            if (result.ok) plan.onSuccessfulResult(result)
            tracing.publish(RuntimeDispatchCompleted(
                sessionId = sessionId,
                runtime = spec.name,
                atEpochMillis = System.currentTimeMillis(),
                dispatchId = dispatchId,
                elapsedMillis = result.elapsedMillis,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                idleTimedOut = result.idleTimedOut,
                outputLimited = result.outputLimited,
                processExited = result.processExited,
                stdoutLines = result.stdout.size,
                stderrLines = result.stderr.size,
                errorType = collected.responseErrorType
                    ?.utf16PrefixWithin(RUNTIME_TRACE_DIAGNOSTIC_LIMIT),
                message = collected.responseErrorMessage
                    ?.utf16PrefixWithin(RUNTIME_TRACE_DIAGNOSTIC_LIMIT),
            ))
            if (mustClose) closeSuspending()
            result
        } catch (failure: Throwable) {
            // Cancellation or a failed output callback must not leave late output
            // queued for another dispatch in this same session.
            poisoned = true
            val pendingFailure = try {
                // Only a still-pending trace needs failure diagnostics. A
                // completed or already closed dispatch keeps its first terminal
                // fact without invoking another user-supplied message getter.
                if (tracing.hasPendingDispatch(dispatchId)) {
                    val message = try {
                        failure.message?.utf16PrefixWithin(RUNTIME_TRACE_DIAGNOSTIC_LIMIT)
                    } catch (diagnosticFailure: Exception) {
                        if (diagnosticFailure is java.util.concurrent.CancellationException) throw diagnosticFailure
                        null
                    }
                    tracing.publish(RuntimeDispatchFailed(
                        sessionId = sessionId,
                        runtime = spec.name,
                        atEpochMillis = System.currentTimeMillis(),
                        dispatchId = dispatchId,
                        elapsedMillis = elapsedMillisSince(startedAtNanos),
                        errorType = (failure::class.qualifiedName ?: failure::class.simpleName ?: "Throwable")
                            .utf16PrefixWithin(RUNTIME_TRACE_DIAGNOSTIC_LIMIT),
                        message = message,
                    ))
                }
                failure
            } catch (diagnosticFailure: Throwable) {
                // Preserve the existing diagnostic/getter failure precedence;
                // subsequent cleanup must not replace the selected failure.
                diagnosticFailure
            }
            closeAfterFailure(pendingFailure)
            throw pendingFailure
        }
    }

    private suspend fun queuedTimeout(
        child: Process,
        startedAtNanos: Long,
        startedAtEpochMillis: Long,
        script: String?,
        operation: String,
    ): RuntimeResult {
        check(!closed && !poisoned) { "${spec.name} runtime is closed after timeout/cancellation or process exit" }
        check(child.isAlive) { "${spec.name} runtime process has exited" }
        val failure = tracing.expiredBeforeSubmission(script, operation, startedAtNanos, startedAtEpochMillis)
        val result = RuntimeResult(
            runtime = spec.name,
            stdout = emptyList(),
            stderr = emptyList(),
            exitCode = null,
            timedOut = true,
            sessionId = sessionId,
            dispatchId = failure.dispatchId,
            elapsedMillis = failure.elapsedMillis,
        )
        try {
            tracing.drain()
        } catch (failure: Throwable) {
            // Expiration alone preserves active work, but a fatal observer
            // error has the same owned-cleanup contract as an admitted call.
            // Keep the queued request's already recorded timeout fact.
            poisoned = true
            closeAfterFailure(failure)
            throw failure
        }
        return result
    }

    override fun close() {
        // The suspending form is used by the DSL and also joins the I/O workers.
        kotlinx.coroutines.runBlocking { closeSuspending() }
    }

    /** Close an owned session without replacing a failure already in flight. */
    internal suspend fun closeAfterFailure(failure: Throwable) {
        tracing.rememberClosingFailure(failure, onlyWhileOpen = true)
        try {
            closeSuspending()
        } catch (cleanup: Throwable) {
            failure.suppressOnce(cleanup)
        }
    }

    suspend fun closeSuspending(): Unit = withContext(NonCancellable) {
        var cleanupFailure: Throwable? = null
        fun retainFailure(failure: Throwable) {
            val pending = cleanupFailure
            if (pending == null) {
                cleanupFailure = failure
                // Another caller may deliver the queued terminal record.
                tracing.rememberClosingFailure(failure)
            } else {
                pending.suppressOnce(failure)
            }
        }
        try {
            withContext(cleanupDispatcher) {
                closeMutex.withLock {
                    if (closed) return@withLock
                    // Linearize closure against completion/output facts even while
                    // physical cleanup is still stopping readers and the process.
                    tracing.beginClose()
                    val child = process
                    requests.close()
                    writerJob?.cancel()
                    try {
                        if (child != null) {
                            OwnedProcessCleanup.finish(child, { OwnedProcessCleanup.captureDescendants(child) }, graceful = !poisoned) {
                                // Only an already finished writer permits safe EOF: a native
                                // pipe write may otherwise hold the lock needed by close().
                                if (writerJob?.isCompleted == true) child.outputStream.close()
                            }
                        }
                    } catch (failure: Throwable) {
                        retainFailure(failure)
                    }
                    // Finish each resource even when a preceding close failed.
                    if (child != null) {
                        listOf(child.outputStream, child.inputStream, child.errorStream).forEach { stream ->
                            try {
                                stream.close()
                            } catch (_: IOException) {
                                // Process termination can already have closed a native pipe.
                            } catch (failure: Throwable) {
                                retainFailure(failure)
                            }
                        }
                    }
                    ioJobs.forEach { job ->
                        try {
                            job.cancelAndJoin()
                        } catch (failure: Throwable) {
                            retainFailure(failure)
                        }
                    }
                    events.close()
                    tracing.enqueue(RuntimeSessionClosed(
                        sessionId = sessionId,
                        runtime = spec.name,
                        atEpochMillis = System.currentTimeMillis(),
                        processId = child?.pid(),
                        poisoned = poisoned,
                    ))
                }
            }
        } catch (failure: Throwable) {
            retainFailure(failure)
        }
        // Delivery stays out of the cleanup mutex and its single IO permit.
        // Reentrant close from a sink only enqueues, while its outer delivery
        // drains the terminal records after that callback returns.
        try {
            tracing.drain()
        } catch (failure: Throwable) {
            retainFailure(failure)
        }
        cleanupFailure?.let { throw it }
    }

    private fun pump(child: Process, input: java.io.InputStream, stream: RuntimeStream) {
        ioJobs += ownerScope.launch(Dispatchers.IO) {
            var failure: IOException? = null
            try {
                readOwnedProcessText(child, input, isTerminating = { closed }) { text ->
                    events.send(Event.Chunk(stream, text))
                }
            } catch (error: IOException) {
                failure = error
            }
            events.send(Event.End(stream, failure))
        }
    }

    private fun elapsedDurationSince(startedAtNanos: Long): Duration =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L).nanoseconds

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)

}
