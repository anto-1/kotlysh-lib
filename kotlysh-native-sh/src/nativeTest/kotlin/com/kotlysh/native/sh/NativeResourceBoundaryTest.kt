package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EMFILE
import platform.posix.EAGAIN
import platform.posix.F_GETFD
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.linux.RLIMIT_NOFILE
import platform.linux.RLIMIT_NPROC
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.geteuid
import platform.linux.getrlimit
import platform.posix.open
import platform.linux.rlimit
import platform.linux.setrlimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Limits are restored before assertions/cleanup outside the tested acquisition. */
@OptIn(ExperimentalForeignApi::class)
class NativeResourceBoundaryTest {
    @Test
    fun directPartialPipeFailuresReleaseEveryAcquiredDescriptor() {
        for (spare in 0..3) withDescriptorSlots(spare) {
            val failure = assertFailsWith<IllegalStateException> { NativeExecScope().execute("/bin/true") }
            assertTrue(failure.message.orEmpty().contains("create native pipe"))
        }
        assertTrue(NativeExecScope().execute("/bin/true").ok)
    }

    @Test
    fun shellPartialPipeFailuresReleaseEveryAcquiredDescriptor() {
        for (spare in 0..5) withDescriptorSlots(spare) {
            val failure = assertFailsWith<IllegalStateException> { NativeShellSession().close() }
            assertTrue(failure.message.orEmpty().contains("create native pipe"))
        }
        NativeShellSession().use { session -> assertTrue(with(session) { "printf ready"() }.ok) }
    }

    @Test
    fun directLaunchNeedsOnlyItsFourPipeDescriptorsAndReceivesEofStdin() {
        val result = withDescriptorSlots(4) {
            NativeExecScope().execute("/bin/sh", "-c", "if read line; then exit 1; fi; printf ready")
        }
        assertTrue(result.ok, "Direct launch exhausted descriptors: $result")
        assertEquals(listOf("ready"), result.stdout)
    }

    @Test
    fun shellLaunchNeedsOnlyItsSixPipeDescriptors() {
        val result = withDescriptorSlots(6) {
            NativeShellSession().use { session -> with(session) { "printf ready"() } }
        }
        assertTrue(result.ok)
        assertEquals(listOf("ready"), result.stdout)
    }

    @Test
    fun deniedForkReleasesPreparedResourcesAndAllowsLaterLaunches() {
        // Root bypasses RLIMIT_NPROC; the C fault-injection control covers it there.
        if (geteuid() == 0u) return
        val descriptors = nativeTestDescriptorCount()
        memScoped {
            val original = alloc<rlimit>()
            val limited = alloc<rlimit>()
            assertEquals(0, getrlimit(RLIMIT_NPROC.convert(), original.ptr))
            limited.rlim_cur = 0u
            limited.rlim_max = original.rlim_max
            assertEquals(0, setrlimit(RLIMIT_NPROC.convert(), limited.ptr))
            try {
                repeat(3) {
                    val direct = assertFailsWith<IllegalStateException> { NativeExecScope().execute("/bin/true") }
                    val shell = assertFailsWith<IllegalStateException> { NativeShellSession().close() }
                    assertTrue(direct.message.orEmpty().contains("prepare or fork native process"))
                    assertTrue(shell.message.orEmpty().contains("prepare or fork native process"))
                    assertTrue(direct.message.orEmpty().endsWith("errno=$EAGAIN"))
                    assertTrue(shell.message.orEmpty().endsWith("errno=$EAGAIN"))
                }
            } finally { assertEquals(0, setrlimit(RLIMIT_NPROC.convert(), original.ptr)) }
        }
        assertEquals(descriptors, nativeTestDescriptorCount())
        assertTrue(NativeExecScope().execute("/bin/true").ok)
        NativeShellSession().use { session -> assertTrue(with(session) { "printf ready"() }.ok) }
    }

    private fun <T> withDescriptorSlots(spare: Int, block: () -> T): T {
        val descriptorCount = nativeTestDescriptorCount()
        return memScoped {
            val original = alloc<rlimit>()
            val limited = alloc<rlimit>()
            assertEquals(0, getrlimit(RLIMIT_NOFILE.convert(), original.ptr))
            val held = mutableListOf<Int>()
            // Preserve existing descriptors even in a runner with extra inherited ones.
            var highest = 2
            for (fd in 0 until 1024) if (fcntl(fd, F_GETFD) >= 0) highest = fd
            limited.rlim_cur = minOf(original.rlim_cur, (highest + 16).toULong())
            limited.rlim_max = original.rlim_max
            assertEquals(0, setrlimit(RLIMIT_NOFILE.convert(), limited.ptr))
            try {
                while (true) {
                    val fd = open("/dev/null", O_RDONLY or O_CLOEXEC)
                    if (fd < 0) { assertEquals(EMFILE, errno); break }
                    held.add(fd)
                }
                assertTrue(held.size >= spare)
                repeat(spare) { close(held.removeAt(held.lastIndex)) }
                block()
            } finally {
                held.forEach { close(it) }
                assertEquals(0, setrlimit(RLIMIT_NOFILE.convert(), original.ptr))
                assertEquals(descriptorCount, nativeTestDescriptorCount(), "Acquisition leaked descriptors with $spare spare slots")
            }
        }
    }
}
