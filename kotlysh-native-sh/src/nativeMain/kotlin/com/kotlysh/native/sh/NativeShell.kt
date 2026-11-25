package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EPIPE
import platform.posix.EWOULDBLOCK
import platform.posix.POLLIN
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLNVAL
import platform.posix.POLLOUT
import platform.posix.errno
import platform.posix.poll
import platform.posix.pollfd
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class NativeShellResult(
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val idleTimedOut: Boolean = false,
    val processExited: Boolean = false,
    val outputLimited: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !idleTimedOut && !processExited && !outputLimited
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")
}

@OptIn(ExperimentalForeignApi::class)
class NativeShellSession(
    private val shellPath: String = "/bin/sh",
) : AutoCloseable {
    private var child: NativeOwnedChild? = null
    private var input: NativePipe? = null
    private var output: NativePipe? = null
    private var errors: NativePipe? = null
    // Only the dispatch/close owner accesses pipes, the child and poisoned state.
    // CLOSING requests that the active owner clean up before publishing CLOSED.
    private val admission = AtomicInt(IDLE)
    private var poisoned = false

    private companion object {
        const val IDLE = 0
        const val BUSY = 1
        const val CLOSING = 2
        const val CLOSED = 3
    }

    init {
        require(shellPath.isNotBlank()) { "Shell path must not be blank" }
        requireNativeLaunchText(shellPath, "Shell path")
        start()
    }

    operator fun String.invoke(
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        outputLimits: NativeOutputLimits = NativeOutputLimits(),
    ): NativeShellResult {
        checkOpen()
        val budget = NativeProcessBudget(timeout, idleTimeout)
        requireNativeLaunchText(this, "Shell source")
        if (!acquire(budget))
            return NativeShellResult(emptyList(), emptyList(), null, budget.timedOut, budget.idleTimedOut)
        try {
            check(!poisoned) { "Native shell session is closed after timeout, output limit or process exit" }
            checkOpen()
            val marker = "__KOTLYSH_NATIVE_SH_${Random.nextLong().toULong().toString(16)}__"
            val bytes = wrapScript(normalizeScript(), marker).encodeToByteArray()
            return dispatch(bytes, marker, budget, outputLimits)
        } catch (failure: Throwable) {
            poisoned = true
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            if (!admission.compareAndSet(BUSY, IDLE)) {
                try { release() } finally { admission.value = CLOSED }
            }
        }
    }

    override fun close() {
        while (true) {
            when (admission.value) {
                CLOSED -> return
                IDLE -> if (admission.compareAndSet(IDLE, CLOSING)) {
                    try { release() } finally { admission.value = CLOSED }
                    return
                }
                BUSY -> admission.compareAndSet(BUSY, CLOSING)
                CLOSING -> pause(5)
            }
        }
    }

    private fun checkOpen() {
        check(admission.value < CLOSING) { "Native shell session is closed" }
    }

    /** Queueing charges the caller's budgets without touching the current owner. */
    private fun acquire(budget: NativeProcessBudget): Boolean {
        while (true) {
            checkOpen()
            val waitMs = budget.pollMillis() ?: return false
            if (admission.compareAndSet(IDLE, BUSY)) return true
            pause(waitMs)
        }
    }

    private fun pause(milliseconds: Int) {
        val result = poll(null, 0.convert(), milliseconds)
        check(result >= 0 || errno == EINTR) { "Failed to wait for native session: errno=$errno" }
    }

    private fun release() {
        input?.close()
        output?.close()
        errors?.close()
        val owned = child
        child = null
        owned?.close()
    }

    private fun start() {
        try {
            val stdin = NativePipe().also { input = it }
            val stdout = NativePipe().also { output = it }
            val stderr = NativePipe().also { errors = it }
            val owned = NativeOwnedChild().also { child = it }
            nativeLaunch(owned, shellPath, listOf("-s"), null, emptyMap(), searchPath = false,
                input = stdin, output = stdout, errors = stderr)
            stdin.closeRead()
            stdout.closeWrite()
            stderr.closeWrite()
            nativeNonblocking(stdin.writeFd)
            nativeNonblocking(stdout.readFd)
            nativeNonblocking(stderr.readFd)
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun wrapScript(script: String, marker: String): String {
        val statusVariable = "${marker}_status"
        return buildString {
            appendLine(script.trimEnd())
            // User printf functions/aliases and status-shaped bindings remain shell state.
            appendLine("$statusVariable=$?")
            appendLine("command printf '%s:%s\\n' '$marker' \"\$$statusVariable\"")
            appendLine("command printf '%s:%s\\n' '$marker' \"\$$statusVariable\" >&2")
            appendLine("command unset '$statusVariable'")
        }
    }

    private fun String.normalizeScript(): String =
        if ('\n' in this || '\r' in this) trimIndent() else this

    private fun dispatch(
        bytes: ByteArray,
        marker: String,
        budget: NativeProcessBudget,
        outputLimits: NativeOutputLimits,
    ): NativeShellResult {
        val stdinFd = checkNotNull(input).writeFd
        val stdoutFd = checkNotNull(output).readFd
        val stderrFd = checkNotNull(errors).readFd
        val owned = checkNotNull(child)
        val capture = NativeOutputCapture(outputLimits, marker)
        var stdoutEof = false
        var stderrEof = false
        var processExited = false
        var offset = 0
        val scratch = ByteArray(4096)

        fun receive(fd: Int, isStdout: Boolean) {
            val count = nativeRead(fd, scratch)
            if (count > 0) {
                budget.receivedBytes()
                capture.consume(isStdout, scratch, count)
            } else if (count == 0) {
                if (isStdout) stdoutEof = true else stderrEof = true
            }
        }

        memScoped {
            val fds = allocArray<pollfd>(3)
            while (offset < bytes.size || !capture.stdoutDone || !capture.stderrDone) {
                checkOpen()
                if (capture.outputLimited) break
                if (owned.pollExit() != null) { processExited = true; break }
                val waitMs = budget.pollMillis() ?: break
                fds[0].fd = if (offset < bytes.size) stdinFd else -1
                fds[0].events = POLLOUT.convert()
                fds[1].fd = if (capture.stdoutDone || stdoutEof) -1 else stdoutFd
                fds[1].events = POLLIN.convert()
                fds[2].fd = if (capture.stderrDone || stderrEof) -1 else stderrFd
                fds[2].events = POLLIN.convert()
                for (index in 0..2) fds[index].revents = 0
                val ready = poll(fds, 3.convert(), waitMs)
                if (ready < 0 && errno == EINTR) continue
                check(ready >= 0) { "poll failed while dispatching shell source: errno=$errno" }
                checkOpen()
                if (ready == 0) continue
                if ((fds[1].revents.toInt() and (POLLIN or POLLHUP or POLLERR or POLLNVAL)) != 0)
                    receive(stdoutFd, true)
                if ((fds[2].revents.toInt() and (POLLIN or POLLHUP or POLLERR or POLLNVAL)) != 0)
                    receive(stderrFd, false)
                if (capture.outputLimited) break
                if ((stdoutEof && !capture.stdoutDone) || (stderrEof && !capture.stderrDone)) {
                    processExited = true
                    break
                }
                if ((fds[0].revents.toInt() and (POLLOUT or POLLHUP or POLLERR or POLLNVAL)) != 0) {
                    val written = nativeWrite(stdinFd, bytes, offset)
                    when {
                        written > 0 -> offset += written
                        written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) -> Unit
                        written < 0 && errno == EPIPE -> { processExited = true; break }
                        else -> error("Failed to submit shell source: errno=$errno")
                    }
                }
            }
        }

        if (budget.timedOut || budget.idleTimedOut || processExited || capture.outputLimited) {
            poisoned = true
            owned.stop()
            // Preserve available output and partial lines without waiting on inherited pipes.
            nativeDrain(stdoutFd, scratch) { capture.consume(true, scratch, it) }
            nativeDrain(stderrFd, scratch) { capture.consume(false, scratch, it) }
            capture.finish()
            release()
        }
        return NativeShellResult(capture.stdout, capture.stderr, capture.exitCode,
            budget.timedOut, budget.idleTimedOut, processExited, capture.outputLimited)
    }

}

fun nativeSh(
    shellPath: String = "/bin/sh",
    block: NativeShellSession.() -> Unit,
) {
    val session = NativeShellSession(shellPath)
    try {
        session.block()
    } finally {
        session.close()
    }
}
