package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ExecResult(
    val command: List<String>,
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val outputLimited: Boolean = false,
) {
    constructor(command: List<String>, stdout: List<String>, stderr: List<String>, exitCode: Int?, timedOut: Boolean = false) :
        this(command, stdout, stderr, exitCode, timedOut, false)

    /** Preserve the original Java-source and JVM copy signature. */
    fun copy(command: List<String>, stdout: List<String>, stderr: List<String>, exitCode: Int?, timedOut: Boolean): ExecResult =
        copy(command, stdout, stderr, exitCode, timedOut, outputLimited)

    companion object {
        @Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
        @JvmStatic
        @JvmName("copy\$default")
        @Suppress("UNUSED_PARAMETER")
        fun legacyCopyDefault(
            original: ExecResult,
            command: List<String>?, stdout: List<String>?, stderr: List<String>?, exitCode: Int?, timedOut: Boolean,
            mask: Int, marker: Any?,
        ): ExecResult = original.copy(
            if (mask and 1 != 0) original.command else checkNotNull(command),
            if (mask and 2 != 0) original.stdout else checkNotNull(stdout),
            if (mask and 4 != 0) original.stderr else checkNotNull(stderr),
            if (mask and 8 != 0) original.exitCode else exitCode,
            if (mask and 16 != 0) original.timedOut else timedOut,
        )
    }

    val ok: Boolean get() = exitCode == 0 && !timedOut && !outputLimited
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")
}

suspend fun exec(
    command: List<String>,
    cwd: Path? = null,
    env: Map<String, String> = emptyMap(),
    timeout: Duration? = 30.seconds,
    output: RuntimeOutputSink? = null,
): ExecResult {
    require(command.isNotEmpty()) { "command must not be empty" }
    return runExec(command, cwd, env, timeout, output)
}

private suspend fun runExec(
    command: List<String>,
    cwd: Path?,
    env: Map<String, String>,
    timeout: Duration?,
    output: RuntimeOutputSink?,
): ExecResult = withContext(Dispatchers.IO) {
    // Acquire one complete vector before validation or launch. Caller callbacks
    // cannot mix an earlier executable with arguments from a later list state.
    val argv = command.immutableSnapshot()
    ensureActive()
    require(argv.isNotEmpty()) { "command must not be empty" }
    require(argv.first().isNotBlank()) { "executable must not be blank" }
    require(timeout == null || timeout.isPositive()) { "Timeout must be positive" }
    val limits = coroutineContext.runtimeOutputLimits()

    argv.forEachIndexed { index, argument -> requireNativeText(argument, "Command argument $index") }
    val environment = env.toMap()
    environment.forEach { (name, value) ->
        requireNativeText(name, "Environment variable name")
        requireNativeText(value, "Environment variable value")
    }
    val processBuilder = ProcessBuilder(argv)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
    cwd?.let { selected ->
        val directory = java.io.File(selected.toFile().path)
        requireNativeText(directory.path, "Working directory")
        // A native Path can retain undecodable filename bytes that File's text
        // would replace, potentially selecting an existing lookalike directory.
        require(directory.toPath().toAbsolutePath() == selected.toAbsolutePath()) {
            "Working directory changes native path when converted for process launch"
        }
        processBuilder.directory(directory)
    }
    processBuilder.environment().putAll(environment)

    coroutineScope {
        // Allocate bookkeeping before acquisition, then enter owned cleanup as
        // soon as start returns, including failures constructing the tracker.
        val terminating = AtomicBoolean()
        var ownership: OwnedProcessCleanup.Tracker? = null
        var failure: Throwable? = null
        // Caller-owned environment/path capture can cancel us during preparation.
        ensureActive()
        val process = processBuilder.start()
        // Keep cleanup inside the scope: its readers must be unblocked before
        // structured concurrency waits for them after cancellation or failure.
        try {
            val tracker = OwnedProcessCleanup.Tracker(process)
            ownership = tracker
            ensureActive()
            process.outputStream.close()
            fun deliver(stream: RuntimeStream, accepted: String) {
                // Caller code can make the root exit before cleanup runs.
                if (output != null) tracker.capture()
                try {
                    output?.onLine(RuntimeOutputLine("exec", stream, accepted))
                } catch (cancelled: CancellationException) {
                    // A cancelled reader alone does not cancel its parent.
                    // Stop the local exec scope so its process wait is also
                    // interrupted and owned cleanup runs before returning.
                    this@coroutineScope.cancel(cancelled)
                    throw cancelled
                }
            }
            val collected = ExecOutputCollector(limits, ::deliver)

            val stdoutReader = async(Dispatchers.IO) {
                readExecOutput(process, process.inputStream, terminating, collected, RuntimeStream.Stdout)
            }
            val stderrReader = async(Dispatchers.IO) {
                readExecOutput(process, process.errorStream, terminating, collected, RuntimeStream.Stderr)
            }
            suspend fun complete(): Boolean {
                while (true) {
                    tracker.capture()
                    if (collected.outputLimited) return false
                    if (!process.isAlive) break
                    // Release the ordinary IO permit so both readers and their
                    // streaming consumers can run while the process is alive.
                    delay(50)
                }
                stdoutReader.await()
                stderrReader.await()
                return !collected.outputLimited
            }
            val completion = if (timeout == null) {
                complete()
            } else {
                withTimeoutOrNull(timeout) { complete() }
            }
            // False means a capture limit; null means the deadline expired.
            // Later reader activity cannot replace the established timeout fact.
            val completed = completion == true
            val timedOut = completion == null
            if (!completed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    terminating.set(true)
                    OwnedProcessCleanup.finish(
                        process, { tracker.snapshot() }, graceful = false,
                    )
                }
                stdoutReader.await()
                stderrReader.await()
            }
            ExecResult(
                command = argv,
                stdout = collected.output(RuntimeStream.Stdout),
                stderr = collected.output(RuntimeStream.Stderr),
                exitCode = if (completed) process.exitValue() else null,
                timedOut = timedOut,
                outputLimited = collected.outputLimited,
            )
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                terminating.set(true)
                try {
                    OwnedProcessCleanup.finish(
                        process, { ownership?.snapshot() ?: OwnedProcessCleanup.captureDescendants(process) }, graceful = false,
                    )
                } catch (cleanup: Throwable) {
                    val original = failure ?: throw cleanup
                    if (cleanup !== original && original.suppressed.none { it === cleanup }) original.addSuppressed(cleanup)
                } finally {
                    runCatching { process.outputStream.close() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            }
        }
    }
}

private suspend fun readExecOutput(
    process: Process,
    input: java.io.InputStream,
    terminating: AtomicBoolean,
    collected: ExecOutputCollector,
    stream: RuntimeStream,
) {
    readOwnedProcessText(process, input, isTerminating = terminating::get) { text ->
        collected.consume(stream, text)
    }
    collected.end(stream)
}
