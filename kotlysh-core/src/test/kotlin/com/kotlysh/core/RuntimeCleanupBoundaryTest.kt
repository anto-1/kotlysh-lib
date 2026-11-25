package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeCleanupBoundaryTest {
    @Test
    fun `stdin close failure still closes streams channels workers and trace`(): Unit = runBlocking {
        assertCleanup(null)
    }

    @Test
    fun `stdin close failure remains primary when terminal trace also fails`(): Unit = runBlocking {
        assertCleanup(ClosureFailure())
    }

    @Test
    fun `fatal stdin close failure still finishes session housekeeping`(): Unit = runBlocking {
        assertCleanup(null, StdinCloseFatal())
    }

    @Test
    fun `fatal stdin close failure remains primary when terminal trace fails`(): Unit = runBlocking {
        assertCleanup(ClosureFailure(), StdinCloseFatal())
    }

    @Test
    fun `repeated fatal close failure cannot suppress itself`(): Unit = runBlocking {
        val failure = StdinCloseFatal()
        assertCleanup(failure, failure)
    }

    @Test
    fun `later stream and observer failures retain the first close failure and finish every resource`(): Unit = runBlocking {
        assertCleanup(ClosureFailure(), StdinCloseFailure(), StreamCloseFailure(3), StreamCloseFailure(4))
    }

    @Test
    fun `another caller delivering terminal telemetry attaches its error to the native close failure`(): Unit = runBlocking {
        assertCleanup(ClosureFailure(), deferTrace = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun assertCleanup(
        observerFailure: Throwable?,
        failure: Throwable = StdinCloseFailure(),
        stdoutFailure: Throwable? = null,
        stderrFailure: Throwable? = null,
        deferTrace: Boolean = false,
    ) {
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        val started = CompletableDeferred<Unit>()
        val releaseStarted = CountDownLatch(1)
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (deferTrace && event is RuntimeSessionStarted) {
                started.complete(Unit)
                assertTrue(releaseStarted.await(10, TimeUnit.SECONDS), "Startup observer was not released")
            }
            if (event is RuntimeSessionClosed && observerFailure != null) throw observerFailure
        }, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpec(
            "stdin-close-failure",
            listOf("/bin/bash", "--noprofile", "--norc", "-c", "while :; do printf '%4096s\\n' output; done"),
            RuntimeSpecs.Bash.wrap,
        ))
        var launched: Process? = null
        try {
            val startup = if (deferTrace) owner.async { runtime.start() } else null
            if (startup == null) runtime.start()
            else withTimeout(5.seconds) { started.await() }
            val processField = ProcessRuntime::class.java.getDeclaredField("process").apply { isAccessible = true }
            val child = processField.get(runtime) as Process
            launched = child
            val wrapped = CloseFailureProcess(child, failure, stdoutFailure, stderrFailure)
            processField.set(runtime, wrapped)

            // Ensure the graceful EOF branch is taken without canceling the readers.
            val writer = runtime.readField<Job>("writerJob")
            writer.cancelAndJoin()
            val workers = runtime.readField<List<Job>>("ioJobs").toList()
            val events = runtime.readField<Channel<*>>("events")
            val requests = runtime.readField<Channel<*>>("requests")

            val actual = runCatching { runtime.closeSuspending() }.exceptionOrNull()

            assertSame(failure, actual)
            assertFalse(child.isAlive, "The real interpreter must stop before the close error propagates")
            assertTrue(events.isClosedForSend, "Cleanup failure left the output channel open")
            assertTrue(requests.isClosedForSend, "Cleanup failure left the request channel open")
            assertTrue(workers.all { it.isCompleted }, "Cleanup failure left an I/O worker active")
            assertTrue(wrapped.stdoutClosed && wrapped.stderrClosed, "Cleanup failure skipped stream closure")
            if (startup != null) {
                assertTrue(failure.suppressed.isEmpty(), "Closure must remain queued behind the active observer")
                releaseStarted.countDown()
                assertSame(observerFailure, runCatching { startup.await() }.exceptionOrNull())
            }
            assertEquals(listOfNotNull(stdoutFailure, stderrFailure, observerFailure).filter { it !== failure },
                failure.suppressed.toList())
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
            assertTrue(trace.events.last() is RuntimeSessionClosed)
            runtime.closeSuspending()
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
        } finally {
            releaseStarted.countDown()
            try {
                runtime.closeSuspending()
            } finally {
                launched?.let { child ->
                    OwnedProcessCleanup.finish(child, emptyList(), graceful = false)
                    listOf(child.outputStream, child.inputStream, child.errorStream).forEach { runCatching { it.close() } }
                }
                ownerJob.cancelAndJoin()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ProcessRuntime.readField(name: String): T =
        ProcessRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    // Preserve failure identity through coroutine exception recovery.
    private class StdinCloseFailure(val boundary: Int = 1) : IOException("Simulated stdin close failure")
    private class StdinCloseFatal(val boundary: Int = 1) : Error("Simulated fatal stdin close failure")
    private class StreamCloseFailure(val boundary: Int) : Error("Simulated stream close failure")
    private class ClosureFailure(val boundary: Int = 2) : Error("Simulated terminal observer failure")

    /** The real process and its readers stay intact; only stream-close outcomes change. */
    private class CloseFailureProcess(
        private val child: Process,
        private val failure: Throwable,
        private val stdoutFailure: Throwable?,
        private val stderrFailure: Throwable?,
    ) : Process() {
        var stdoutClosed = false
            private set
        var stderrClosed = false
            private set
        private val stdin = object : FilterOutputStream(child.outputStream) {
            override fun close() {
                super.close()
                throw failure
            }
        }
        private val stdout = object : FilterInputStream(child.inputStream) {
            override fun close() {
                stdoutClosed = true
                super.close()
                stdoutFailure?.let { throw it }
            }
        }
        private val stderr = object : FilterInputStream(child.errorStream) {
            override fun close() {
                stderrClosed = true
                super.close()
                stderrFailure?.let { throw it }
            }
        }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = child.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = child.waitFor(timeout, unit)
        override fun exitValue(): Int = child.exitValue()
        override fun destroy() = child.destroy()
        override fun destroyForcibly(): Process { child.destroyForcibly(); return this }
        override fun isAlive(): Boolean = child.isAlive
        override fun pid(): Long = child.pid()
        override fun toHandle(): ProcessHandle = child.toHandle()
    }
}
