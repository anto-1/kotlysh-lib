package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeBrokenStdinTest {
    @Test
    fun `native writer failure closes its session before propagating`(): Unit = runBlocking {
        assertBrokenStdin(null)
    }

    @Test
    fun `native writer failure remains primary when closure telemetry fails`(): Unit = runBlocking {
        assertBrokenStdin(ClosureFailure())
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun assertBrokenStdin(closureFailure: Throwable?) {
        val directory = Files.createTempDirectory("kotlysh-broken-stdin-")
        val ready = directory.resolve("ready")
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink {
            trace.onEvent(it)
            if (it is RuntimeSessionClosed && closureFailure != null) throw closureFailure
        }, RuntimeTraceLimits()))
        // Close the native read end while retaining a live root and both output
        // pipes. The file acknowledges closure before the request is submitted.
        val runtime = ProcessRuntime(owner, RuntimeSpec(
            "broken-stdin",
            listOf("/bin/bash", "--noprofile", "--norc", "-c",
                "exec 0<&-; printf ready > ${shLiteral(ready.toString())}; exec /bin/sleep 30"),
            RuntimeSpecs.Bash.wrap,
        ))
        try {
            runtime.start()
            withTimeout(5.seconds) {
                while (!Files.exists(ready)) delay(10)
            }
            val pid = checkNotNull(runtime.processId)
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive)

            val failure = assertFailsWith<IOException> {
                runtime.exec("printf must-not-run", timeout = 5.seconds)
            }

            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Writer failure left its root alive")
            assertTrue(ownerJob.isActive, "A session I/O error cancelled the independent owner")
            assertTrue(runtime.readField<List<Job>>("ioJobs").all { it.isCompleted })
            assertTrue(runtime.readField<Channel<*>>("events").isClosedForSend)
            assertTrue(runtime.readField<Channel<*>>("requests").isClosedForSend)
            assertEquals(listOfNotNull(closureFailure), failure.suppressed.toList())
            assertFailsWith<IllegalStateException> { runtime.exec("printf late-request") }

            val failed = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
            assertEquals("java.io.IOException", failed.errorType)
            assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
            assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
            assertTrue(trace.events.last() is RuntimeSessionClosed)
            runtime.closeSuspending()
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)

            // The failed writer and queued error cannot affect a new session.
            sh {
                assertEquals(listOf("healthy"), "printf 'healthy\\n'"().stdout)
            }
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                ownerJob.cancelAndJoin()
                Files.deleteIfExists(ready)
                Files.deleteIfExists(directory)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ProcessRuntime.readField(name: String): T =
        ProcessRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private class ClosureFailure : Error("Closure observer failed")
}
