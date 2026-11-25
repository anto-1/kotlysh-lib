package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeLaunchLifecycleTest {
    @Test
    fun `custom runtime launch joins children and owns cancellation cleanup`(): Unit = runBlocking {
        assertLifecycle(Language.Custom)
    }

    @Test
    fun `shell launch joins children and owns cancellation cleanup`(): Unit = runBlocking {
        assertLifecycle(Language.Sh)
    }

    @Test
    fun `python launch joins children and owns cancellation cleanup`(): Unit = runBlocking {
        assertLifecycle(Language.Py)
    }

    @Test
    fun `node launch joins children and owns cancellation cleanup`(): Unit = runBlocking {
        assertLifecycle(Language.Node)
    }

    @Test
    fun `ruby launch joins children and owns cancellation cleanup`(): Unit = runBlocking {
        assertLifecycle(Language.Ruby)
    }

    private suspend fun assertLifecycle(language: Language) {
        for (cancel in listOf(false, true)) {
            val ownerJob = SupervisorJob()
            val trace = RuntimeTraceBuffer()
            val unhandled = CopyOnWriteArrayList<Throwable>()
            val owner = CoroutineScope(ownerJob + Dispatchers.Default +
                CoroutineExceptionHandler { _, error -> unhandled += error } +
                RuntimeTraceContext(trace, RuntimeTraceLimits()))
            val acquired = CompletableDeferred<Long>()
            val ready = CompletableDeferred<Unit>()
            val result = CompletableDeferred<RuntimeResult>()
            try {
                val job = owner.launchFor(language) {
                    acquired.complete(checkNotNull(runtime.processId))
                    if (cancel) {
                        language.waiting(timeout = 30.seconds, output = {
                            if (it.stream == RuntimeStream.Stdout && it.text == "ready") ready.complete(Unit)
                        })
                        error("The blocked dispatch unexpectedly completed")
                    } else {
                        // The launch body returns before its child dispatches.
                        // The session must remain alive until that child finishes.
                        launch {
                            delay(50)
                            assertTrue(language.initialize().ok)
                            result.complete(language.read())
                        }
                    }
                }
                val completed = CompletableDeferred<Throwable?>()
                job.invokeOnCompletion { completed.complete(it) }
                val pid = withTimeout(5.seconds) { acquired.await() }
                if (cancel) {
                    withTimeout(5.seconds) { ready.await() }
                    assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive)
                    withTimeout(5.seconds) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                    assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
                    assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
                } else {
                    withTimeout(5.seconds) { job.join() }
                    assertNull(completed.await())
                    assertEquals(listOf("42"), result.await().stdout)
                    assertTrue(result.await().ok)
                    assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
                }
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Launch completed with a live interpreter")
                assertTrue(ownerJob.isActive, "Session completion cancelled its independent owner")
                assertTrue(unhandled.isEmpty(), "Unhandled worker failures: $unhandled")
                assertEquals(cancel, trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
                assertTrue(trace.events.last() is RuntimeSessionClosed)
            } finally {
                ownerJob.cancelAndJoin()
            }
        }
    }

    private fun CoroutineScope.launchFor(language: Language, block: suspend RuntimeScope.() -> Unit): Job =
        when (language) {
            Language.Custom -> launchRuntime(RuntimeSpecs.Bash.copy(name = "custom-launch"), block = block)
            Language.Sh -> launchSh { block() }
            Language.Py -> launchPy { block() }
            Language.Node -> launchNode { block() }
            Language.Ruby -> launchRuby { block() }
        }

    private enum class Language(val initialize: String, val read: String, val waiting: String) {
        Custom("kotlysh_launch=41", "printf '%s\\n' \"${'$'}((kotlysh_launch + 1))\"", "printf 'ready\\n'; sleep 30"),
        Sh("kotlysh_launch=41", "printf '%s\\n' \"${'$'}((kotlysh_launch + 1))\"", "printf 'ready\\n'; sleep 30"),
        Py("kotlysh_launch = 41", "print(kotlysh_launch + 1)", "import time; print('ready', flush=True); time.sleep(30)"),
        Node("globalThis.kotlysh_launch = 41", "console.log(kotlysh_launch + 1)",
            "console.log('ready'); new Promise(resolve => setTimeout(resolve, 30000));"),
        Ruby("kotlysh_launch = 41", "puts kotlysh_launch + 1", "puts 'ready'; sleep 30"),
    }
}
