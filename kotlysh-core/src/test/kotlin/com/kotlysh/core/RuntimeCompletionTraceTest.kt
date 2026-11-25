package com.kotlysh.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeCompletionTraceTest {
    @Test
    fun `fatal completion observer cannot turn successful execution into another terminal record`(): Unit = runBlocking {
        assertCompletionObserverFailure("printf completed", exitCode = 0)
    }

    @Test
    fun `fatal completion observer preserves the foreign failure terminal record`(): Unit = runBlocking {
        assertCompletionObserverFailure("printf failed; false", exitCode = 1)
    }

    @Test
    fun `fatal completion observer preserves the active timeout terminal record`(): Unit = runBlocking {
        assertCompletionObserverFailure("sleep 30", exitCode = null, timeout = 150.milliseconds, timedOut = true)
    }

    @Test
    fun `completed dispatch failure never reads a hostile observer error message getter`(): Unit = runBlocking {
        val failure = HostileCompletionError()
        assertCompletionObserverFailure("printf completed", exitCode = 0, failure = failure)
        assertEquals(0, failure.getterReads)
    }

    @Test
    fun `observer closing after completion does not request failure diagnostics or a second terminal record`(): Unit = runBlocking {
        val failure = HostileCompletionError()
        assertCompletionObserverFailure("printf completed", exitCode = 0, failure = failure, closeBeforeThrow = true)
        assertEquals(0, failure.getterReads)
    }

    @Test
    fun `ordinary completion observer failure leaves one terminal record and a reusable session`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeDispatchCompleted) error("Ordinary completion observer failure")
        }) {
            sh {
                assertTrue("printf first"().ok)
                assertTrue(runtime.isOpen)
                assertEquals(listOf("second"), "printf second"().stdout)
            }
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertTrue(trace.events.none { it is RuntimeDispatchFailed })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private suspend fun assertCompletionObserverFailure(
        script: String,
        exitCode: Int?,
        timeout: Duration = 5.seconds,
        timedOut: Boolean = false,
        failure: Throwable = CompletionError(1),
        closeBeforeThrow: Boolean = false,
    ) {
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        lateinit var runtime: ProcessRuntime
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        val capturedChildren = mutableListOf<CapturedProcess>()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> } +
            RuntimeTraceContext(RuntimeTraceSink { event ->
                trace.onEvent(event)
                if (event is RuntimeDispatchCompleted && event.dispatchId == 2L) {
                    checkNotNull(root).handle.descendants().use { descendants ->
                        descendants.forEach { capturedChildren += capture(it) }
                    }
                    if (closeBeforeThrow) runtime.close()
                    throw failure
                }
            }, RuntimeTraceLimits()))
        runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        try {
            runtime.start()
            val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
            root = ownedRoot
            val setup = runtime.exec("sleep 30 & child=\$!; printf 'owned-child=%s\\n' \"\$child\"", output = { line ->
                val ownedChild = capture(ProcessHandle.of(line.text.removePrefix("owned-child=").toLong()).orElseThrow())
                child = ownedChild
                assertTrue(ownedRoot.handle.descendants().use { descendants ->
                    descendants.anyMatch { it.pid() == ownedChild.handle.pid() }
                })
            })
            assertTrue(setup.ok)
            assertTrue(checkNotNull(child).handle.isAlive)

            val actual = runCatching { runtime.exec(script, timeout = timeout) }.exceptionOrNull()

            // Observe before caller cleanup: reporting errors must still stop
            // the acquired runtime tree and keep the real execution fact.
            assertFalse(ownedRoot.handle.isAlive)
            assertFalse(checkNotNull(child).handle.isAlive)
            assertTrue(capturedChildren.isNotEmpty())
            assertTrue(capturedChildren.none { it.handle.isAlive })
            assertTrue(actual === failure, "The completion observer error must remain observable")
            val events = trace.events
            val completed = events.filterIsInstance<RuntimeDispatchCompleted>().single { it.dispatchId == 2L }
            assertEquals(exitCode, completed.exitCode)
            assertEquals(timedOut, completed.timedOut)
            assertTrue(events.none { it is RuntimeDispatchFailed }, "Completion must be the sole terminal record")
            val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
            assertEquals(!closeBeforeThrow, closed.poisoned)
            assertEquals(closed, events.last())
            assertTrue(events.indexOf(completed) < events.indexOf(closed))
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                capturedChildren.forEach { it.destroyIfOwned() }
                child?.destroyIfOwned()
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    private fun capture(handle: ProcessHandle): CapturedProcess =
        CapturedProcess(handle, handle.info().startInstant().orElseThrow())

    private data class CapturedProcess(val handle: ProcessHandle, val started: Instant) {
        fun destroyIfOwned() {
            if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
        }
    }

    // Extra fields keep coroutine stack recovery from copying fixture errors.
    private class CompletionError(val boundary: Int) : Error("Simulated completion observer $boundary")
    private class HostileCompletionError : Error() {
        var getterReads = 0
        override val message: String? get() {
            getterReads++
            throw CancellationException("Completion diagnostics must not be requested")
        }
    }
}
