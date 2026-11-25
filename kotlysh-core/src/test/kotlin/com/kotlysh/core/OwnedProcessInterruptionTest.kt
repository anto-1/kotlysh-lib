package com.kotlysh.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(10)
class OwnedProcessInterruptionTest {
    @Test
    fun `descendant lookup failure still stops the root and restores interruption`() {
        assertLookupFailure(CloseFailure())
    }

    @Test
    fun `fatal descendant lookup failure still stops the root and restores interruption`() {
        assertLookupFailure(Error("fatal descendant lookup failure"))
    }

    private fun assertLookupFailure(original: Throwable) {
        withRoot { process ->
            var lookups = 0
            var callbacks = 0
            val failure: Throwable?
            val interrupted: Boolean
            try {
                Thread.currentThread().interrupt()
                failure = runCatching {
                    OwnedProcessCleanup.finish(process, { lookups++; throw original }, graceful = true) { callbacks++ }
                }.exceptionOrNull()
                interrupted = Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted()
            }
            assertSame(original, failure)
            assertFalse(process.isAlive, "Lookup failure must not bypass root termination")
            assertTrue(interrupted)
            assertTrue(lookups == 1, "Failed lookup must not be retried")
            assertTrue(callbacks == 0, "Graceful closure requires completed descendant lookup")
        }
    }

    @Test
    fun `an interrupted cleanup still stops a signal ignoring root and restores the interrupt flag`() {
        withRoot { process ->
            val failure: Throwable?
            val interrupted: Boolean
            try {
                Thread.currentThread().interrupt()
                failure = runCatching { OwnedProcessCleanup.finish(process, emptyList(), graceful = false) }.exceptionOrNull()
                interrupted = Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted()
            }
            assertNull(failure, "Interruption must not abandon owned process cleanup")
            assertFalse(process.isAlive, "The root must be stopped before cleanup returns")
            assertTrue(interrupted, "Cleanup must preserve the caller's interruption")
        }
    }

    @Test
    fun `resource close failure remains primary when its callback also interrupts cleanup`() {
        withRoot { process ->
            val original = CloseFailure()
            var callbacks = 0
            val failure: Throwable?
            val interrupted: Boolean
            try {
                failure = runCatching {
                    OwnedProcessCleanup.finish(process, emptyList(), graceful = true) {
                        callbacks++
                        Thread.currentThread().interrupt()
                        throw original
                    }
                }.exceptionOrNull()
                interrupted = Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted()
            }
            assertSame(original, failure, "Interruption must not mask the resource close failure")
            assertFalse(process.isAlive)
            assertTrue(interrupted)
            assertTrue(callbacks == 1, "Cleanup must not retry the resource callback")
        }
    }

    @Test
    fun `interruption received while waiting does not bypass forced termination`() {
        withRoot { process ->
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val interrupted = java.util.concurrent.atomic.AtomicBoolean()
            val cleaner = Thread({
                try { OwnedProcessCleanup.finish(process, emptyList(), graceful = false) }
                catch (error: Throwable) { failure.set(error) }
                finally { interrupted.set(Thread.currentThread().isInterrupted) }
            }, "Kotlysh interrupted owned cleanup")
            cleaner.start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (cleaner.isAlive && cleaner.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(cleaner.isAlive && cleaner.state == Thread.State.TIMED_WAITING, "The fixture must interrupt a live termination wait")
                cleaner.interrupt()
                cleaner.join(2_000)
                assertFalse(cleaner.isAlive)
                assertNull(failure.get())
                assertFalse(process.isAlive)
                assertTrue(interrupted.get())
            } finally {
                if (cleaner.isAlive) {
                    process.destroyForcibly()
                    cleaner.join(2_000)
                }
            }
        }
    }

    private fun withRoot(block: (Process) -> Unit) {
        // This signal-ignoring fixture exercises Unix termination.
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
            "Requires Unix signal-ignoring process behavior")
        val process = ProcessBuilder("python3", "-u", "-c",
            "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('ready'); time.sleep(30)").start()
        try {
            assertTrue(process.inputStream.bufferedReader().readLine() == "ready")
            block(process)
        } finally {
            Thread.interrupted()
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            listOf(process.outputStream, process.inputStream, process.errorStream).forEach { it.close() }
        }
    }

    private class CloseFailure : RuntimeException("resource close failed")
}
