package com.kotlysh.nativesh

import com.kotlysh.nativesh.posixshim.kotlysh_available
import com.kotlysh.nativesh.posixshim.kotlysh_nonblocking
import com.kotlysh.nativesh.posixshim.kotlysh_exit_status
import com.kotlysh.nativesh.posixshim.kotlysh_pipe
import com.kotlysh.nativesh.posixshim.kotlysh_write_pipe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EAGAIN
import platform.posix.ECHILD
import platform.posix.EINTR
import platform.posix.ESRCH
import platform.posix.EWOULDBLOCK
import platform.posix.SIGKILL
import platform.posix.close
import platform.posix.errno
import platform.posix.getpgid
import platform.posix.kill
import platform.posix.read
import platform.posix.setpgid
import platform.posix.waitpid
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
internal class NativePipe : AutoCloseable {
    var readFd: Int = -1
        private set
    var writeFd: Int = -1
        private set

    init {
        memScoped {
            val ends = allocArray<IntVar>(2)
            check(kotlysh_pipe(ends) == 0) { "Failed to create native pipe: errno=$errno" }
            readFd = ends[0]
            writeFd = ends[1]
        }
    }

    fun closeRead() {
        if (readFd >= 0) close(readFd)
        readFd = -1
    }

    fun closeWrite() {
        if (writeFd >= 0) close(writeFd)
        writeFd = -1
    }

    override fun close() {
        closeRead()
        closeWrite()
    }
}

/** Owns a direct child and its inherited group; the host must not reap this child. */
@OptIn(ExperimentalForeignApi::class)
internal class NativeOwnedChild : AutoCloseable {
    private val pidCell = nativeHeap.alloc<IntVar>().apply { value = -1 }
    val pidPointer: CPointer<IntVar> get() = pidCell.ptr
    private var pid: Int
        get() = pidCell.value
        set(value) { pidCell.value = value }
    private var released = false
    private var groupOwned = false

    fun establishGroup() {
        check(!released && pid > 0) { "Native process has not been launched" }
        // The child also sets its group before exec, closing the parent/exec race.
        setpgid(pid, pid)
        groupOwned = getpgid(pid) == pid
        check(groupOwned) { "Failed to establish native process group" }
    }

    fun pollExit(): Int? {
        val status = readStatus()
        return if (status == -2) null else status
    }

    private fun readStatus(): Int {
        check(!released && pid > 0) { "Native process is no longer owned" }
        val status = kotlysh_exit_status(pid)
        if (status == -1) {
            // An external SIGCHLD reaper relinquished the PID reservation.
            if (errno == ECHILD) pid = -1
            error("Failed to observe native process: errno=$errno")
        }
        return status
    }

    override fun close() {
        if (released) return
        val ownedPid = pid
        try {
            if (ownedPid <= 0) return
            val groupFailure = signalOwnedProcesses()
            var reaped: Int
            do { reaped = waitpid(ownedPid, null, 0) } while (reaped < 0 && errno == EINTR)
            check(reaped == ownedPid || (reaped < 0 && errno == ECHILD)) {
                "Failed to reap native process: errno=$errno"
            }
            groupFailure?.let { throw it }
        } finally {
            pid = -1
            released = true
            nativeHeap.free(pidCell.ptr.rawValue)
        }
    }

    fun stop() {
        signalOwnedProcesses()?.let { throw it }
    }

    private fun signalOwnedProcesses(): Throwable? {
        // WNOWAIT reserves the PID under the host's exclusive-wait contract.
        val exited = readStatus() != -2
        var groupFailure: Throwable? = null
        if (groupOwned && kill(-pid, SIGKILL) != 0 && errno != ESRCH)
            groupFailure = IllegalStateException("Failed to stop native process group: errno=$errno")
        val rootSignal = kill(pid, SIGKILL)
        // Never block in waitpid after failing to stop a still-running root.
        check(exited || rootSignal == 0 || errno == ESRCH) {
            "Failed to stop native process: errno=$errno"
        }
        return groupFailure
    }
}

/** Wall and idle budgets include submission; only received bytes reset idle time. */
internal class NativeProcessBudget(
    private val timeout: Duration,
    private val idleTimeout: Duration,
) {
    private val started = TimeSource.Monotonic.markNow()
    private var lastActivity = started
    var timedOut = false
        private set
    var idleTimedOut = false
        private set

    init {
        require(timeout > Duration.ZERO) { "Timeout must be positive" }
        require(idleTimeout > Duration.ZERO) { "Idle timeout must be positive" }
    }

    fun receivedBytes() { lastActivity = TimeSource.Monotonic.markNow() }

    fun pollMillis(): Int? {
        val wall = timeout - started.elapsedNow()
        val idle = idleTimeout - lastActivity.elapsedNow()
        if (wall <= Duration.ZERO) { timedOut = true; return null }
        if (idle <= Duration.ZERO) { idleTimedOut = true; return null }
        // Cap before converting infinity/large durations and round fractional ms up.
        val remaining = minOf(wall, idle, 50.milliseconds)
        val whole = remaining.inWholeMilliseconds
        return (whole + if (remaining > whole.milliseconds) 1 else 0).toInt().coerceAtLeast(1)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun nativeNonblocking(fd: Int) {
    check(kotlysh_nonblocking(fd) == 0) { "Failed to configure native pipe: errno=$errno" }
}

/** Positive bytes, zero EOF, or -1 for a retryable interrupted/would-block read. */
@OptIn(ExperimentalForeignApi::class)
internal fun nativeRead(fd: Int, scratch: ByteArray, limit: Int = scratch.size): Int {
    val count = scratch.usePinned { read(fd, it.addressOf(0), limit.convert()) }.toInt()
    check(count >= 0 || errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
        "Failed to read native output: errno=$errno"
    }
    return count
}

@OptIn(ExperimentalForeignApi::class)
internal fun nativeWrite(fd: Int, bytes: ByteArray, offset: Int): Int =
    bytes.usePinned {
        kotlysh_write_pipe(fd, it.addressOf(offset), (bytes.size - offset).convert())
    }.toInt()

/** Drain a fixed snapshot, so an inherited writer cannot extend root completion. */
@OptIn(ExperimentalForeignApi::class)
internal fun nativeDrain(fd: Int, scratch: ByteArray, onBytes: (Int) -> Unit) {
    var remaining = kotlysh_available(fd)
    check(remaining >= 0) { "Failed to inspect native output: errno=$errno" }
    while (remaining > 0) {
        val count = nativeRead(fd, scratch, minOf(scratch.size, remaining))
        if (count < 0 && errno == EINTR) continue
        if (count <= 0) break
        onBytes(count)
        remaining -= count
    }
}
