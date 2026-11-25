package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class NativeSessionConcurrencyTest {
    @Test
    fun overlappingDispatchesKeepTheirOwnOutputAndCompletion() = runBlocking {
        val root = nativeTestTempDirectory("session")
        val started = "$root/started"
        val release = "$root/release"
        val secondStarted = "$root/second"
        val session = NativeShellSession()
        val first = async(Dispatchers.Default) {
            runCatching { with(session) {
                "printf ready > '$started'; while [ ! -e '$release' ]; do sleep 0.01; done; printf 'first\\n'; printf 'first-error\\n' >&2"(4.seconds)
            } }
        }
        awaitFile(started)
        val second = async(Dispatchers.Default) {
            runCatching { with(session) {
                "printf ready > '$secondStarted'; printf 'second\\n'; printf 'second-error\\n' >&2"(4.seconds)
            } }
        }
        try {
            delay(100)
            assertFalse(second.isCompleted)
            assertEquals(null, readNativeTestFile(secondStarted))
            writeNativeTestFile(release, "ready")
            val one = first.await().getOrThrow()
            val two = second.await().getOrThrow()
            assertTrue(one.ok)
            assertTrue(two.ok)
            assertEquals(listOf("first"), one.stdout)
            assertEquals(listOf("first-error"), one.stderr)
            assertEquals(listOf("second"), two.stdout)
            assertEquals(listOf("second-error"), two.stderr)
        } finally {
            writeNativeTestFile(release, "ready")
            first.join(); second.join(); session.close()
            unlink(started); unlink(release); unlink(secondStarted); rmdir(root)
        }
    }

    @Test
    fun wallDeadlineIncludesWaitingWithoutStoppingTheOwner() = queuedDeadline(idle = false)

    @Test
    fun idleDeadlineIncludesWaitingWithoutStoppingTheOwner() = queuedDeadline(idle = true)

    private fun queuedDeadline(idle: Boolean) = runBlocking {
        val root = nativeTestTempDirectory("admission")
        val started = "$root/started"
        val release = "$root/release"
        val submitted = "$root/submitted"
        val session = NativeShellSession()
        val first = async(Dispatchers.Default) {
            runCatching { with(session) {
                "printf ready > '$started'; while [ ! -e '$release' ]; do sleep 0.01; done; printf survivor"(4.seconds)
            } }
        }
        try {
            awaitFile(started)
            val elapsed = TimeSource.Monotonic.markNow()
            val second = async(Dispatchers.Default) {
                runCatching { with(session) {
                    "printf submitted > '$submitted'"(
                        timeout = if (idle) 3.seconds else 150.milliseconds,
                        idleTimeout = if (idle) 150.milliseconds else 3.seconds)
                } }
            }
            val result = second.await().getOrThrow()
            assertTrue(elapsed.elapsedNow() < 800.milliseconds)
            assertEquals(!idle, result.timedOut)
            assertEquals(idle, result.idleTimedOut)
            assertFalse(result.processExited || result.outputLimited)
            assertEquals(null, result.exitCode)
            assertTrue(result.stdout.isEmpty() && result.stderr.isEmpty())
            assertEquals(null, readNativeTestFile(submitted), "Expired caller submitted source")
            assertFalse(first.isCompleted, "Expired caller stopped the active owner")
            writeNativeTestFile(release, "ready")
            val survivor = first.await().getOrThrow()
            assertTrue(survivor.ok)
            assertEquals(listOf("survivor"), survivor.stdout)
            assertTrue(with(session) { "printf ready"() }.ok)
        } finally {
            writeNativeTestFile(release, "ready")
            first.join(); session.close()
            unlink(started); unlink(release); unlink(submitted); rmdir(root)
        }
    }

    @Test
    fun closeStopsAnActiveDispatchAndRejectsWaitingCallers() = closeRunning(closedOutput = false)

    @Test
    fun closeStopsAnInfiniteBudgetDispatchWithClosedOutputPipes() = closeRunning(closedOutput = true)

    private fun closeRunning(closedOutput: Boolean) = runBlocking {
        async(Dispatchers.Default) { Unit }.await()
        val descriptors = nativeTestDescriptorCount()
        val root = nativeTestTempDirectory("close")
        val marker = "$root/owned"
        val queuedSource = "$root/queued"
        val session = NativeShellSession()
        val tail = if (closedOutput) "exec 1>/dev/null 2>/dev/null; wait" else "wait"
        val source = """
            sleep 30 & child=${'$'}!
            root_birth=${'$'}(/usr/bin/awk '{print ${'$'}22}' /proc/${'$'}${'$'}/stat)
            child_birth=${'$'}(/usr/bin/awk '{print ${'$'}22}' /proc/${'$'}child/stat)
            printf '%s:%s\n%s:%s\n' "${'$'}${'$'}" "${'$'}root_birth" "${'$'}child" "${'$'}child_birth" > '$marker'
            $tail
        """.trimIndent()
        val first = async(Dispatchers.Default) {
            runCatching { with(session) { source(Duration.INFINITE, Duration.INFINITE) } }
        }
        var owned = emptyList<NativeTestProcess>()
        try {
            awaitFile(marker)
            owned = checkNotNull(readNativeTestFile(marker)).trim().lines().map {
                val parts = it.split(':')
                NativeTestProcess(parts[0].toInt(), parts[1])
            }
            val waiting = async(Dispatchers.Default) {
                runCatching { with(session) { "printf started > '$queuedSource'"(4.seconds) } }
            }
            delay(100)
            val elapsed = TimeSource.Monotonic.markNow()
            val closers = List(8) { async(Dispatchers.Default) { runCatching { session.close() } } }
            closers.forEach { it.await().getOrThrow() }
            assertTrue(elapsed.elapsedNow() < 800.milliseconds, "Close waited for dispatch deadline")
            assertEquals("Native shell session is closed", assertIs<IllegalStateException>(first.await().exceptionOrNull()).message)
            assertEquals("Native shell session is closed", assertIs<IllegalStateException>(waiting.await().exceptionOrNull()).message)
            assertEquals(null, readNativeTestFile(queuedSource))
            owned.forEach { it.assertStopped() }
            owned.first().assertReaped()
            session.close()
        } finally {
            session.close(); first.join(); owned.forEach { it.cleanup() }
            unlink(marker); unlink(queuedSource); rmdir(root)
        }
        assertEquals(descriptors, nativeTestDescriptorCount(), "Active close leaked descriptors")
    }

    @Test
    fun concurrentCloseIsIdempotentAndLeavesOtherSessionsAlive() = runBlocking {
        // Warm the dispatcher before comparing descriptor counts.
        async(Dispatchers.Default) { Unit }.await()
        val descriptors = nativeTestDescriptorCount()
        val session = NativeShellSession()
        val sibling = NativeShellSession()
        try {
            assertTrue(with(session) { "printf ready"() }.ok)
            val closers = List(12) { async(Dispatchers.Default) { runCatching { session.close() } } }
            closers.forEach { it.await().getOrThrow() }
            assertIs<IllegalStateException>(runCatching { with(session) { "printf late"() } }.exceptionOrNull())
            assertEquals(listOf("alive"), with(sibling) { "printf alive"() }.stdout)
        } finally { session.close(); sibling.close() }
        assertEquals(descriptors, nativeTestDescriptorCount(), "Concurrent close leaked descriptors")
    }

    private suspend fun awaitFile(path: String) {
        val elapsed = TimeSource.Monotonic.markNow()
        while (readNativeTestFile(path).isNullOrEmpty() && elapsed.elapsedNow() < 2.seconds) delay(5)
        assertFalse(readNativeTestFile(path).isNullOrEmpty(), "Fixture did not start")
    }
}
