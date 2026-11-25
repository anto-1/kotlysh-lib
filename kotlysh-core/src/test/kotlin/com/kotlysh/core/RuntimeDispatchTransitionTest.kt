package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(15)
class RuntimeDispatchTransitionTest {
    @Test
    fun `successful transition precedes completion observers and the next admission in Bash and Python`(): Unit = runBlocking {
        listOf(
            RuntimeCase(RuntimeSpecs.Bash, "printf '%s\\n' first", "printf '%s\\n' second"),
            RuntimeCase(RuntimeSpecs.Python, "print('first')", "print('second')"),
        ).forEach { runtimeCase ->
            verifyTransitionOrder(runtimeCase)
        }
    }

    private suspend fun CoroutineScope.verifyTransitionOrder(runtimeCase: RuntimeCase) {
        val ownerJob = SupervisorJob()
        val transitioned = AtomicBoolean(false)
        val secondStarted = AtomicBoolean(false)
        val transitionEntered = CompletableDeferred<Unit>()
        val releaseTransition = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val owner = CoroutineScope(
            ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
                when {
                    event is RuntimeDispatchCompleted && event.dispatchId == 1L -> {
                        assertTrue(transitioned.get(), "completion observer ran before the success transition")
                        order += "first-completed"
                    }
                    event is RuntimeDispatchStarted && event.dispatchId == 2L -> {
                        secondStarted.set(true)
                        order += "second-started"
                    }
                }
            }, RuntimeTraceLimits()),
        )
        val runtime = ProcessRuntime(owner, runtimeCase.spec)
        try {
            runtime.start()
            val first = async(Dispatchers.Default) {
                runtime.dispatchPrepared(timeout = 5.seconds) {
                    val wrapper = wrap
                    RuntimeDispatchPlan(
                        wire = { marker -> wrapper(runtimeCase.firstScript, marker) },
                        traceOperation = "${runtimeCase.spec.name}-transition-test",
                        onSuccessfulResult = { result ->
                            assertTrue(result.ok)
                            transitioned.set(true)
                            order += "transition"
                            transitionEntered.complete(Unit)
                            assertTrue(
                                releaseTransition.await(5, TimeUnit.SECONDS),
                                "${runtimeCase.spec.name} test did not release the successful transition",
                            )
                        },
                    )
                }
            }

            transitionEntered.await()
            val queuedAttempted = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                queuedAttempted.complete(Unit)
                runtime.exec(runtimeCase.secondScript)
            }
            queuedAttempted.await()
            delay(100)
            assertFalse(
                secondStarted.get(),
                "${runtimeCase.spec.name} admitted a queued request while the transition held admission",
            )

            releaseTransition.countDown()
            assertEquals(listOf("first"), first.await().stdout)
            assertEquals(listOf("second"), second.await().stdout)

            val observed = synchronized(order) { order.toList() }
            assertEquals(
                listOf("transition", "first-completed", "second-started"),
                observed,
            )
        } finally {
            releaseTransition.countDown()
            try {
                runtime.closeSuspending()
            } finally {
                ownerJob.cancelAndJoin()
            }
        }
    }

    private data class RuntimeCase(
        val spec: RuntimeSpec,
        val firstScript: String,
        val secondScript: String,
    )
}
