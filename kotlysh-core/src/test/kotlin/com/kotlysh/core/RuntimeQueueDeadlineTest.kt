package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Timeout(15)
class RuntimeQueueDeadlineTest {
    @Test
    fun `manual close finishes active dispatch trace before session closure`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            supervisorScope {
                val runtime = ProcessRuntime(this, RuntimeSpecs.Bash)
                val releaseOutput = CountDownLatch(1)
                try {
                    runtime.start()
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val ready = CompletableDeferred<Unit>()
                    val active = async(Dispatchers.Default) {
                        runCatching {
                            runtime.exec("printf 'ready\\n'; sleep 30", output = {
                                ready.complete(Unit)
                                assertTrue(releaseOutput.await(5, TimeUnit.SECONDS))
                            })
                        }
                    }
                    ready.await()
                    runtime.closeSuspending()
                    releaseOutput.countDown()
                    active.await()
                    assertFalse(process.isAlive)
                } finally {
                    releaseOutput.countDown()
                    runtime.closeSuspending()
                }
            }
        }
        val events = trace.events
        val start = events.filterIsInstance<RuntimeDispatchStarted>().single()
        val terminal = events.filter {
            it is RuntimeDispatchCompleted && it.dispatchId == start.dispatchId ||
                it is RuntimeDispatchFailed && it.dispatchId == start.dispatchId
        }.single()
        val close = events.filterIsInstance<RuntimeSessionClosed>().single()
        assertTrue(events.indexOf(terminal) > events.indexOf(start))
        assertTrue(events.indexOf(terminal) < events.indexOf(close))
        assertEquals(close, events.last())
    }

    @Test
    fun `queued deadline returns a failed result without interrupting active work`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val ready = CompletableDeferred<Unit>()
                val active = async {
                    "printf 'ready\\n'; sleep 0.4; printf finished"(timeout = 3.seconds, output = {
                        if (it.text == "ready") ready.complete(Unit)
                    })
                }
                ready.await()
                val started = TimeSource.Monotonic.markNow()
                val queued = "printf must-not-run"(timeout = 40.milliseconds)
                assertTrue(queued.timedOut)
                assertFalse(queued.ok)
                assertEquals(null, queued.exitCode)
                assertTrue(queued.stdout.isEmpty() && queued.stderr.isEmpty())
                assertTrue(started.elapsedNow() < 250.milliseconds, "queued call took ${started.elapsedNow()}")
                assertTrue(runtime.isOpen)
                val completed = active.await()
                assertTrue(completed.ok)
                assertEquals(listOf("ready", "finished"), completed.stdout)
                assertEquals(completed.sessionId, queued.sessionId)
                assertEquals(2L, queued.dispatchId)
                assertTrue("printf usable"().ok)
            }
        }
        val starts = trace.events.filterIsInstance<RuntimeDispatchStarted>()
        assertEquals(listOf(1L, 2L, 3L), starts.map { it.dispatchId })
        val failure = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
        assertEquals(2L, failure.dispatchId)
        assertTrue(failure.message.orEmpty().contains("not executed"))
        assertTrue(starts.single { it.dispatchId == 2L }.inputSessionIds.isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().none { it.dispatchId == 2L })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `waiting for admission consumes the active execution budget`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val ready = CompletableDeferred<Unit>()
                val active = async {
                    "printf 'ready\\n'; sleep 0.2"(timeout = 3.seconds, output = {
                        if (it.text == "ready") ready.complete(Unit)
                    })
                }
                ready.await()
                val queued = "sleep 0.2; printf must-not-finish"(timeout = 300.milliseconds)
                assertTrue(queued.timedOut)
                assertTrue(queued.stdout.isEmpty())
                assertTrue(queued.elapsedMillis >= 250, "elapsed should include queue time: ${queued.elapsedMillis}")
                assertTrue(active.await().ok)
                assertFalse(runtime.isOpen)
                assertFailsWith<IllegalStateException> { "printf must-not-run"() }
            }
        }
        val timeout = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.dispatchId == 2L }
        assertTrue(timeout.timedOut)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `simultaneous queued expirations keep positive distinct monotonic trace ids`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val ready = CompletableDeferred<Unit>()
                val active = async {
                    "printf 'ready\\n'; sleep 0.4"(timeout = 3.seconds, output = { ready.complete(Unit) })
                }
                ready.await()
                val results = (1..8).map {
                    async { "printf must-not-run"(timeout = 40.milliseconds) }
                }.awaitAll()
                assertTrue(results.all { it.timedOut && it.stdout.isEmpty() })
                assertEquals((2L..9L).toList(), results.map { it.dispatchId }.sorted())
                assertEquals(1, results.map { it.sessionId }.distinct().size)
                assertTrue(active.await().ok)
                assertTrue("printf usable"().ok)
            }
        }
        assertEquals((1L..10L).toList(), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.dispatchId })
        assertEquals(8, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
    }

    @Test
    fun `deadline expiring during free-slot admission cannot leak its mutex`(): Unit = runBlocking {
        sh {
            repeat(40) {
                val result = "printf must-not-run"(timeout = 1.nanoseconds)
                assertTrue(result.timedOut)
                assertTrue(result.stdout.isEmpty())
                assertTrue(runtime.isOpen)
            }
            assertTrue(withTimeout(3.seconds) { "printf usable"() }.ok)
        }
    }

    @Test
    fun `invalid duration fails before waiting for an occupied session`(): Unit = runBlocking {
        sh {
            val ready = CompletableDeferred<Unit>()
            val active = async {
                "printf 'ready\\n'; sleep 0.4"(output = { ready.complete(Unit) })
            }
            ready.await()
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<IllegalArgumentException> { "printf invalid"(timeout = 0.milliseconds) }
            assertTrue(started.elapsedNow() < 250.milliseconds)
            assertTrue(active.await().ok)
        }
    }
}
