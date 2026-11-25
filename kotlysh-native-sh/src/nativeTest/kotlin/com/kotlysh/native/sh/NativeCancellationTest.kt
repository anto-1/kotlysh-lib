package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class NativeCancellationTest {
    @Test
    fun cancellationStopsAQuietRootAndItsOwnedChild() = cancelRunning("wait\n")

    @Test
    fun receivedOutputDoesNotPostponeCancellation() =
        cancelRunning("while :; do printf 'active\\n'; sleep 0.01; done\n")

    @Test
    fun cancellationStopsARootAfterBothOutputPipesReachEof() =
        cancelRunning("exec 1>/dev/null 2>/dev/null; wait\n")

    @Test
    fun cancellationLeavesAnIndependentConcurrentCallAlive() = cancelRunning("wait\n", sibling = true)

    @Test
    fun alreadyCancelledCallsDoNotLaunch() = runBlocking {
        val root = nativeTestTempDirectory("cancel")
        val marker = "$root/launched"
        try {
            val work = async {
                coroutineContext.cancel(CancellationException("already cancelled"))
                nativeExecSuspending("/usr/bin/touch", marker)
            }
            assertFailsWith<CancellationException> { work.await() }
            work.join()
            assertEquals(null, readNativeTestFile(marker))
            assertEquals("ready", nativeExecSuspending("/usr/bin/printf", "%s", "ready").stdout.single())
        } finally { unlink(marker); rmdir(root) }
    }

    @Test
    fun cancellationDuringEnvironmentSnapshotPreventsLaunch() = runBlocking {
        val root = nativeTestTempDirectory("cancel")
        val marker = "$root/launched"
        try {
            val work = async {
                val context = coroutineContext
                val delegate = mapOf("KOTLYSH_CANCEL_TEST" to "present")
                val environment = object : Map<String, String> by delegate {
                    override val entries: Set<Map.Entry<String, String>>
                        get() {
                            context.cancel(CancellationException("snapshot cancelled"))
                            return delegate.entries
                        }
                }
                nativeExecSuspending("/usr/bin/touch", marker, env = environment)
            }
            val failure = assertFailsWith<CancellationException> { work.await() }
            assertEquals("snapshot cancelled", failure.message)
            work.join()
            assertEquals(null, readNativeTestFile(marker), "Cancelled snapshot launched a process")
        } finally { unlink(marker); rmdir(root) }
    }

    private fun cancelRunning(tail: String, sibling: Boolean = false) = runBlocking {
        // Initialize the dispatcher before comparing the caller's descriptor cohort.
        assertTrue(nativeExecSuspending("/bin/true").ok)
        val descriptorCount = nativeTestDescriptorCount()
        val root = nativeTestTempDirectory("cancel")
        val marker = "$root/owned"
        val release = "$root/release"
        val independent = if (sibling) async {
            nativeExecSuspending("/bin/sh", "-c",
                "while [ ! -e '$release' ]; do sleep 0.01; done; printf survivor",
                timeout = 4.seconds, idleTimeout = 4.seconds)
        } else null
        val source = """
            sleep 30 & child=${'$'}!
            root_birth=${'$'}(/usr/bin/awk '{print ${'$'}22}' /proc/${'$'}${'$'}/stat)
            child_birth=${'$'}(/usr/bin/awk '{print ${'$'}22}' /proc/${'$'}child/stat)
            command printf '%s:%s\n%s:%s\n' "${'$'}${'$'}" "${'$'}root_birth" "${'$'}child" "${'$'}child_birth" > '$marker'
        """.trimIndent() + "\n" + tail
        val work = async {
            nativeExecSuspending("/bin/sh", "-c", source, timeout = 4.seconds, idleTimeout = 4.seconds)
        }
        var owned = emptyList<NativeTestProcess>()
        try {
            val startup = TimeSource.Monotonic.markNow()
            while (startup.elapsedNow() < 2.seconds) {
                owned = readNativeTestFile(marker).orEmpty().lineSequence().mapNotNull { line ->
                    val values = line.split(':')
                    if (values.size != 2 || values[1].isEmpty()) null
                    else values[0].toIntOrNull()?.let { NativeTestProcess(it, values[1]) }
                }.toList()
                if (owned.size == 2 && owned.all { it.running() }) break
                delay(5)
            }
            assertEquals(2, owned.size, "Fixture did not publish root and child identity")
            assertTrue(owned.all { it.running() })
            val started = TimeSource.Monotonic.markNow()
            work.cancel(CancellationException("caller cancelled"))
            work.join()
            assertTrue(started.elapsedNow() < 800.milliseconds, "Cancellation waited for the process timeout")
            assertTrue(work.isCompleted)
            val failure = assertFailsWith<CancellationException> { work.await() }
            assertEquals("caller cancelled", failure.message)
            owned.forEach { it.assertStopped() }
            owned.first().assertReaped()
            if (independent != null) {
                assertFalse(independent.isCompleted, "Independent call ended with the cancelled group")
                writeNativeTestFile(release, "ready")
                val survivor = independent.await()
                assertTrue(survivor.ok)
                assertEquals(listOf("survivor"), survivor.stdout)
            }
            assertEquals(descriptorCount, nativeTestDescriptorCount(), "Cancellation leaked parent descriptors")
            assertEquals("ready", nativeExecSuspending("/usr/bin/printf", "%s", "ready").stdout.single())
        } finally {
            work.cancel()
            independent?.cancel()
            withContext(NonCancellable) { work.join(); independent?.join() }
            owned.forEach { it.cleanup() }
            unlink(marker)
            unlink(release)
            rmdir(root)
        }
    }

}
