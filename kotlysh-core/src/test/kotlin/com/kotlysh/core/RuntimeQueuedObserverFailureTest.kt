package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeQueuedObserverFailureTest {
    @Test
    fun `fatal queued start observer stops the active owned tree before propagating`(): Unit = runBlocking {
        assertFatalQueuedObserver(terminal = false)
    }

    @Test
    fun `fatal queued failure observer stops the active owned tree before propagating`(): Unit = runBlocking {
        assertFatalQueuedObserver(terminal = true)
    }

    @Test
    fun `queued observer failure remains primary when closure observer also fails`(): Unit = runBlocking {
        assertFatalQueuedObserver(terminal = true, distinctCleanupFailure = true)
    }

    @Test
    fun `repeated fatal queued and closure error does not self suppress`(): Unit = runBlocking {
        assertFatalQueuedObserver(terminal = false, sameCleanupFailure = true)
    }

    @Test
    fun `fatal observer on a free slot deadline also stops its acquired interpreter`(): Unit = runBlocking {
        for (terminal in listOf(false, true)) assertFatalQueuedObserver(terminal, freeSlot = true)
    }

    private suspend fun assertFatalQueuedObserver(
        terminal: Boolean,
        distinctCleanupFailure: Boolean = false,
        sameCleanupFailure: Boolean = false,
        freeSlot: Boolean = false,
    ) {
        val failure = ObserverError(1)
        val cleanup = if (sameCleanupFailure) failure else if (distinctCleanupFailure) ObserverError(2) else null
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val queuedId = if (freeSlot) 1L else 2L
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
            trace.onEvent(event)
            val trigger = if (terminal) event is RuntimeDispatchFailed && event.dispatchId == queuedId
                else event is RuntimeDispatchStarted && event.dispatchId == queuedId
            if (trigger) throw failure
            if (event is RuntimeSessionClosed && cleanup != null) throw cleanup
        }, RuntimeTraceLimits()))
        var wrapped = 0
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash.copy(wrap = { script, marker ->
            wrapped++
            RuntimeSpecs.Bash.wrap(script, marker)
        }))
        val owned = mutableListOf<Pair<ProcessHandle, Instant>>()
        var active: kotlinx.coroutines.Deferred<Result<RuntimeResult>>? = null
        try {
            runtime.start()
            val root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
            owned += root to root.info().startInstant().orElseThrow()
            if (!freeSlot) {
                val ready = CompletableDeferred<Unit>()
                active = owner.async {
                    runCatching {
                        runtime.exec("sleep 30 & child=\$!; printf 'owned-child=%s\\n' \"\$child\"; wait \"\$child\"",
                            timeout = 10.seconds, output = { line ->
                                val child = ProcessHandle.of(line.text.removePrefix("owned-child=").toLong()).orElseThrow()
                                assertTrue(root.descendants().use { it.anyMatch { candidate -> candidate.pid() == child.pid() } })
                                owned += child to child.info().startInstant().orElseThrow()
                                ready.complete(Unit)
                            })
                    }
                }
                withTimeout(5.seconds) { ready.await() }
                assertEquals(2, owned.size)
            }
            val actual = runCatching {
                runtime.exec("printf must-not-run", timeout = if (freeSlot) 1.nanoseconds else 40.milliseconds)
            }.exceptionOrNull()
            assertTrue(actual === failure, "The original queued observer error must reach its caller")
            // Assert before caller cleanup: a direct ProcessRuntime caller must
            // not inherit a live tree after fatal telemetry propagation.
            assertTrue(owned.none { it.first.isAlive }, "The queued observer error left its owned tree alive")
            assertFalse(runtime.isOpen)
            assertEquals(if (freeSlot) 0 else 1, wrapped, "An expired request must not invoke its wrapper")
            assertEquals(if (distinctCleanupFailure) listOf(cleanup) else emptyList(), failure.suppressed.toList())
            active?.let { withTimeout(5.seconds) { it.await() } }
            val events = trace.events
            val queuedFailure = events.filterIsInstance<RuntimeDispatchFailed>().single { it.dispatchId == queuedId }
            assertEquals("kotlinx.coroutines.TimeoutCancellationException", queuedFailure.errorType)
            assertTrue(queuedFailure.message.orEmpty().contains("not executed"))
            assertTrue(events.filterIsInstance<RuntimeDispatchStarted>().single { it.dispatchId == queuedId }.inputSessionIds.isEmpty())
            assertTrue(events.filterIsInstance<RuntimeDispatchOutput>().none { it.dispatchId == queuedId })
            assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
            val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
            assertTrue(closed.poisoned)
            assertEquals(closed, events.last())
            if (!freeSlot) {
                val interrupted = events.filterIsInstance<RuntimeDispatchFailed>().single { it.dispatchId == 1L }
                assertEquals("java.lang.IllegalStateException", interrupted.errorType)
                assertTrue(events.indexOf(interrupted) < events.indexOf(closed))
            }
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                active?.cancelAndJoin()
                ownerJob.cancelAndJoin()
                for ((handle, started) in owned.asReversed()) {
                    if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
                }
            }
        }
    }

    // Stateful errors retain their identity through coroutine stack recovery.
    private class ObserverError(val boundary: Int) : Error("Queued trace observer $boundary")
}
