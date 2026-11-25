package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeClosureDeliveryFailureTest {
    @Test
    fun `another closer delivering terminal telemetry preserves the originating failure and cleanup error`() = fixture(sameFailure = false)

    @Test
    fun `another closer delivering the same fatal error does not self suppress`() = fixture(sameFailure = true)

    private fun fixture(sameFailure: Boolean): Unit = runBlocking {
        val original = ObserverError(1)
        val cleanup = if (sameFailure) original else ObserverError(2)
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeDispatchFailed && event.dispatchId == 2L) throw original
            if (event is RuntimeSessionClosed) throw cleanup
        }, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        val owned = mutableListOf<Pair<ProcessHandle, Instant>>()
        var active: kotlinx.coroutines.Deferred<Result<RuntimeResult>>? = null
        var queued: kotlinx.coroutines.Deferred<Result<RuntimeResult>>? = null
        var dispatcher: PausedReturnDispatcher? = null
        try {
            runtime.start()
            val root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
            owned += root to root.info().startInstant().orElseThrow()
            val ready = CompletableDeferred<Unit>()
            active = owner.async {
                runCatching {
                    runtime.exec("sleep 30 & child=\$!; printf 'owned-child=%s\\n' \"\$child\"; wait \"\$child\"",
                        timeout = 10.seconds, output = { line ->
                            val child = ProcessHandle.of(line.text.substringAfter('=').toLong()).orElseThrow()
                            check(child.parent().orElseThrow().pid() == root.pid())
                            owned += child to child.info().startInstant().orElseThrow()
                            ready.complete(Unit)
                        })
                }
            }
            withTimeout(5.seconds) { ready.await() }
            val paused = PausedReturnDispatcher(root).also { dispatcher = it }
            queued = owner.async(paused) {
                runCatching { runtime.exec("printf must-not-run", timeout = 40.milliseconds) }
            }
            // Hold only the original caller's return after physical root cleanup.
            // Another closer is then free to deliver the already queued terminal record.
            withTimeout(5.seconds) { paused.held.await() }
            assertEquals(2, owned.size)
            assertTrue(owned.none { it.first.isAlive }, "Original closure must stop its tree before returning")
            runCatching { runtime.closeSuspending() }
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
            paused.release()
            val actual = withTimeout(5.seconds) { queued.await() }.exceptionOrNull()
            assertSame(original, actual)
            assertEquals(if (sameFailure) emptyList() else listOf(cleanup), original.suppressed.toList())
            withTimeout(5.seconds) { active.await() }
            val events = trace.events
            val expired = events.filterIsInstance<RuntimeDispatchFailed>().single { it.dispatchId == 2L }
            assertTrue(expired.message.orEmpty().contains("not executed"))
            assertEquals("kotlinx.coroutines.TimeoutCancellationException", expired.errorType)
            assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
            val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
            assertTrue(closed.poisoned)
            assertEquals(closed, events.last())
        } finally {
            dispatcher?.release()
            try {
                runtime.closeSuspending()
            } finally {
                queued?.cancelAndJoin()
                active?.cancelAndJoin()
                ownerJob.cancelAndJoin()
                owned.asReversed().forEach { (handle, started) ->
                    if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
                }
            }
        }
    }

    private class PausedReturnDispatcher(private val root: ProcessHandle) : CoroutineDispatcher() {
        val held = CompletableDeferred<Unit>()
        private val enabled = AtomicBoolean(true)
        private val waiting = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (enabled.get() && !root.isAlive) {
                waiting.add(context to block)
                held.complete(Unit)
                if (!enabled.get()) release()
            } else {
                Dispatchers.Default.dispatch(context, block)
            }
        }

        fun release() {
            enabled.set(false)
            while (true) {
                val (context, block) = waiting.poll() ?: return
                Dispatchers.Default.dispatch(context, block)
            }
        }
    }

    private class ObserverError(val boundary: Int) : Error("Closure delivery observer $boundary")
}
