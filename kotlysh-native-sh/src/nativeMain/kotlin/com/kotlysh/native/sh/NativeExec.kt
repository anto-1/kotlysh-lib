package com.kotlysh.nativesh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.poll
import platform.posix.pollfd
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class NativeExecResult(
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val idleTimedOut: Boolean = false,
    val signal: Int? = null,
    val outputLimited: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !idleTimedOut && signal == null && !outputLimited
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")
}

private data class NativeExecCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
)

class NativeExecScope {
    fun execute(
        executable: String,
        vararg arguments: String,
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        outputLimits: NativeOutputLimits = NativeOutputLimits(),
    ): NativeExecResult = NativeProcessRunner.run(
        NativeExecCommand(executable, arguments.toList(), cwd, env.toMap()),
        timeout,
        idleTimeout,
        outputLimits,
    )

    operator fun String.invoke(
        vararg args: String,
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        outputLimits: NativeOutputLimits = NativeOutputLimits(),
    ): NativeExecResult = execute(this, *args, timeout = timeout, idleTimeout = idleTimeout, outputLimits = outputLimits)
}

fun nativeExec(block: NativeExecScope.() -> Unit) {
    NativeExecScope().block()
}

suspend fun nativeExecSuspending(
    executable: String,
    vararg arguments: String,
    cwd: String? = null,
    env: Map<String, String> = emptyMap(),
    timeout: Duration = 30.seconds,
    idleTimeout: Duration = timeout,
    outputLimits: NativeOutputLimits = NativeOutputLimits(),
): NativeExecResult = withContext(Dispatchers.Default) {
    val context = coroutineContext
    context.ensureActive()
    NativeProcessRunner.run(
        NativeExecCommand(executable, arguments.toList(), cwd, env.toMap()),
        timeout,
        idleTimeout,
        outputLimits,
        checkCancellation = { context.ensureActive() },
    )
}

@OptIn(ExperimentalForeignApi::class)
private object NativeProcessRunner {
    fun run(
        command: NativeExecCommand,
        timeout: Duration,
        idleTimeout: Duration,
        outputLimits: NativeOutputLimits,
        checkCancellation: () -> Unit = {},
    ): NativeExecResult {
        checkCancellation()
        val budget = NativeProcessBudget(timeout, idleTimeout)
        require(command.executable.isNotBlank()) { "Executable path must not be blank" }
        requireNativeLaunchText(command.executable, "Executable path")
        command.arguments.forEachIndexed { index, argument ->
            requireNativeLaunchText(argument, "Argument $index")
        }
        command.workingDirectory?.let { requireNativeLaunchText(it, "Working directory") }
        command.environment.forEach { (name, value) ->
            require(name.isNotEmpty() && '=' !in name) { "Environment name must be nonempty and contain no '='" }
            requireNativeLaunchText(name, "Environment name")
            requireNativeLaunchText(value, "Environment value")
        }
        checkCancellation()

        var stdoutPipe: NativePipe? = null
        var stderrPipe: NativePipe? = null
        var child: NativeOwnedChild? = null
        var failure: Throwable? = null
        try {
            val stdout = NativePipe().also { stdoutPipe = it }
            val stderr = NativePipe().also { stderrPipe = it }
            val owned = NativeOwnedChild().also { child = it }
            nativeLaunch(owned, command.executable, command.arguments, command.workingDirectory,
                command.environment, searchPath = true, input = null, output = stdout, errors = stderr,
                checkCancellation = checkCancellation)
            checkCancellation()
            stdout.closeWrite()
            stderr.closeWrite()
            nativeNonblocking(stdout.readFd)
            nativeNonblocking(stderr.readFd)
            return collectProcessOutput(owned, stdout.readFd, stderr.readFd, budget, outputLimits, checkCancellation)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            stdoutPipe?.close()
            stderrPipe?.close()
            try { child?.close() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun collectProcessOutput(
        child: NativeOwnedChild,
        stdoutFd: Int,
        stderrFd: Int,
        budget: NativeProcessBudget,
        outputLimits: NativeOutputLimits,
        checkCancellation: () -> Unit,
    ): NativeExecResult {
        val capture = NativeOutputCapture(outputLimits)
        var stdoutDone = false
        var stderrDone = false
        var exitCode: Int? = null
        var signal: Int? = null
        val scratch = ByteArray(4096)

        fun receive(fd: Int, isStdout: Boolean): Boolean {
            val count = nativeRead(fd, scratch)
            if (count > 0) {
                capture.consume(isStdout, scratch, count)
                budget.receivedBytes()
            }
            return count == 0
        }

        memScoped {
            val fds = allocArray<pollfd>(2)
            while (!capture.outputLimited) {
                checkCancellation()
                child.pollExit()?.let { status ->
                    val decoded = decodeWaitStatus(status)
                    exitCode = decoded.first
                    signal = decoded.second
                }
                if (exitCode != null || signal != null) break
                val waitMs = budget.pollMillis() ?: break
                fds[0].fd = if (stdoutDone) -1 else stdoutFd
                fds[0].events = POLLIN.convert()
                fds[0].revents = 0
                fds[1].fd = if (stderrDone) -1 else stderrFd
                fds[1].events = POLLIN.convert()
                fds[1].revents = 0
                val ready = poll(fds, 2.convert(), waitMs)
                if (ready < 0 && errno == EINTR) continue
                check(ready >= 0) { "poll failed while reading process output: errno=$errno" }
                if (ready == 0) continue
                if ((fds[0].revents.toInt() and (POLLIN or POLLHUP or POLLERR or POLLNVAL)) != 0)
                    stdoutDone = receive(stdoutFd, true)
                if ((fds[1].revents.toInt() and (POLLIN or POLLHUP or POLLERR or POLLNVAL)) != 0)
                    stderrDone = receive(stderrFd, false)
            }
        }

        child.stop()
        checkCancellation()
        nativeDrain(stdoutFd, scratch) {
            checkCancellation()
            capture.consume(true, scratch, it)
        }
        nativeDrain(stderrFd, scratch) {
            checkCancellation()
            capture.consume(false, scratch, it)
        }
        capture.finish()
        checkCancellation()
        return NativeExecResult(capture.stdout, capture.stderr, exitCode,
            budget.timedOut, budget.idleTimedOut, signal, capture.outputLimited)
    }

    private fun decodeWaitStatus(status: Int): Pair<Int?, Int?> {
        val signal = status and 0x7f
        if (signal == 0) {
            return ((status ushr 8) and 0xff) to null
        }
        if (signal != 0x7f) {
            return null to signal
        }
        return null to null
    }

}
