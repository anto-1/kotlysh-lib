package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EPIPE
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.SIGPIPE
import platform.posix.SIG_BLOCK
import platform.posix.SIG_SETMASK
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.pthread_sigmask
import platform.posix.raise
import platform.posix.sigaddset
import platform.posix.sigemptyset
import platform.posix.sigismember
import platform.posix.sigpending
import platform.posix.sigset_t
import platform.posix.sigtimedwait
import platform.posix.timespec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class NativeProcessControlTest {
    @Test
    fun pipeDescriptorsAreOwnedAboveStdioAndClosedOnExec() {
        val pipe = NativePipe()
        try {
            assertTrue(pipe.readFd >= 3)
            assertTrue(pipe.writeFd >= 3)
            assertTrue(fcntl(pipe.readFd, F_GETFD) and FD_CLOEXEC != 0)
            assertTrue(fcntl(pipe.writeFd, F_GETFD) and FD_CLOEXEC != 0)
        } finally { pipe.close() }
        assertEquals(-1, pipe.readFd)
        assertEquals(-1, pipe.writeFd)
        pipe.close()
    }

    @Test
    fun closedPipeWriteReturnsEpipeWithoutTerminatingTheHost() {
        val pipe = NativePipe()
        try {
            nativeNonblocking(pipe.writeFd)
            pipe.closeRead()
            assertEquals(-1, nativeWrite(pipe.writeFd, "data".encodeToByteArray(), 0))
            assertEquals(EPIPE, errno)
        } finally { pipe.close() }
    }

    @Test
    fun closedPipeWritePreservesTheThreadMaskAndAnAlreadyPendingSigpipe() {
        val pipe = NativePipe()
        try {
            pipe.closeRead()
            memScoped {
                val blocked = alloc<sigset_t>()
                val previous = alloc<sigset_t>()
                val pending = alloc<sigset_t>()
                val restored = alloc<sigset_t>()
                val immediately = alloc<timespec>()
                immediately.tv_sec = 0
                immediately.tv_nsec = 0
                sigemptyset(blocked.ptr)
                sigaddset(blocked.ptr, SIGPIPE)
                assertEquals(0, pthread_sigmask(SIG_BLOCK, blocked.ptr, previous.ptr))
                try {
                    assertEquals(0, raise(SIGPIPE))
                    assertEquals(-1, nativeWrite(pipe.writeFd, byteArrayOf(1), 0))
                    assertEquals(EPIPE, errno)
                    assertEquals(0, sigpending(pending.ptr))
                    assertEquals(1, sigismember(pending.ptr, SIGPIPE))
                    assertEquals(SIGPIPE, sigtimedwait(blocked.ptr, null, immediately.ptr))
                    assertEquals(-1, nativeWrite(pipe.writeFd, byteArrayOf(1), 0))
                    assertEquals(EPIPE, errno)
                    assertEquals(0, sigpending(pending.ptr))
                    assertEquals(0, sigismember(pending.ptr, SIGPIPE))
                } finally {
                    // Also keep a failed assertion from delivering our fixture signal.
                    sigtimedwait(blocked.ptr, null, immediately.ptr)
                    pthread_sigmask(SIG_SETMASK, previous.ptr, null)
                }
                assertEquals(-1, nativeWrite(pipe.writeFd, byteArrayOf(1), 0))
                assertEquals(EPIPE, errno)
                assertEquals(0, pthread_sigmask(SIG_BLOCK, null, restored.ptr))
                for (signal in 1..31)
                    assertEquals(sigismember(previous.ptr, signal), sigismember(restored.ptr, signal))
            }
        } finally { pipe.close() }
    }
}
