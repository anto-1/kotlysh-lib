package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeLifecycleTest {
    @Test
    fun `blocks return values and nested sessions keep independent state`(): Unit = runBlocking {
        val result = sh {
            "value=outer"()
            val answer = py { "print(6 * 7)"().stdout.first().toInt() }
            "printf '%s' \"\$value\""().stdout.first() to answer
        }
        assertEquals("outer" to 42, result)
    }

    @Test
    fun `session stays open until launched children finish`(): Unit = runBlocking {
        val results = mutableListOf<String>()
        sh {
            launch {
                delay(50)
                results += "printf finished"().stdout.first()
            }
        }
        assertEquals(listOf("finished"), results)
    }

    @Test
    fun `launch helper completes after its managed session and children`(): Unit = runBlocking {
        val result = CompletableDeferred<String>()

        val job = launchSh {
            "value=launch-helper"()
            launch {
                delay(50)
                result.complete("printf '%s' \"\$value\""().stdout.single())
            }
        }

        job.join()

        assertTrue(job.isCompleted)
        assertEquals("launch-helper", result.await())
    }

    @Test
    fun `concurrent requests in one session are serialized`(): Unit = runBlocking {
        val values = sh {
            "counter=0"()
            (1..8).map {
                async {
                    "counter=\$((counter + 1)); printf '%s' \"\$counter\""()
                        .stdout.first().toInt()
                }
            }.awaitAll()
        }
        assertEquals((1..8).toList(), values.sorted())
    }

    @Test
    fun `cancelling a queued request does not poison the running dispatch`(): Unit = runBlocking {
        sh {
            val started = CompletableDeferred<Unit>()
            val running = async {
                "printf 'ready\\n'; sleep 0.2; printf finished"(output = {
                    if (it.text == "ready") started.complete(Unit)
                })
            }
            started.await()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50.milliseconds) { "printf queued"() }
            }
            assertTrue(running.await().ok)
            assertEquals("usable", "printf usable"().stdout.first())
        }
    }

    @Test
    fun `cancelling an active dispatch closes the interpreter and prevents reuse`(): Unit = runBlocking {
        sh {
            val pid = runtime.processId!!
            println("Task-owned interpreter for cancellation: $pid")
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(150.milliseconds) { "sleep 10"() }
            }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertFailsWith<IllegalStateException> { "printf must-not-run"() }
        }
    }

    @Test
    fun `parent cancellation closes its owned interpreter`(): Unit = runBlocking {
        val ready = CompletableDeferred<Long>()
        val work = launch {
            sh {
                val pid = runtime.processId!!
                println("Task-owned interpreter for parent cancellation: $pid")
                "printf 'ready\\n'; sleep 10"(output = {
                    if (it.text == "ready") ready.complete(pid)
                })
            }
        }
        val pid = ready.await()
        work.cancelAndJoin()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `wall timeout preserves partial output and closes the session`(): Unit = runBlocking {
        sh {
            val pid = runtime.processId!!
            println("Task-owned interpreter for wall timeout: $pid")
            val result = "printf partial; sleep 10"(timeout = 200.milliseconds, idleTimeout = 2.seconds)
            assertTrue(result.timedOut)
            assertFalse(result.ok)
            assertEquals(listOf("partial"), result.stdout)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertFailsWith<IllegalStateException> { "printf must-not-run"() }
        }
    }

    @Test
    fun `unexpected process exit is distinct from a timeout`(): Unit = runBlocking {
        sh {
            val result = "printf final; exit 7"(timeout = 3.seconds)
            assertTrue(result.processExited)
            assertEquals(7, result.exitCode)
            assertEquals(listOf("final"), result.stdout)
            assertFalse(result.timedOut)
            assertFalse(result.ok)
            assertFailsWith<IllegalStateException> { "printf must-not-run"() }
        }
    }

    @Test
    fun `partial output refreshes the idle deadline before any newline`(): Unit = runBlocking {
        val result = sh {
            "for i in 1 2 3 4 5 6; do printf x; sleep 0.05; done"(
                timeout = 3.seconds, idleTimeout = 200.milliseconds,
            )
        }
        assertTrue(result.ok)
        assertEquals(listOf("xxxxxx"), result.stdout)
    }

    @Test
    fun `large simultaneous output and empty lines survive chunk boundaries`(): Unit = runBlocking {
        val result = py {
            "import sys; print('x' * 100000); print(); sys.stderr.write('y' * 100000)"()
        }
        assertTrue(result.ok)
        assertEquals(listOf("x".repeat(100000), ""), result.stdout)
        assertEquals(listOf("y".repeat(100000)), result.stderr)
    }

    @Test
    fun `deadline includes a blocked write to an interpreter that never reads stdin`(): Unit = runBlocking {
        val spec = RuntimeSpec("nonreading-fixture", listOf("/bin/sleep", "10")) { script, _ -> script }
        val result = runtime(spec) {
            println("Task-owned nonreading interpreter: ${runtime.processId}")
            "x".repeat(200000)(timeout = 200.milliseconds, idleTimeout = 2.seconds)
        }
        assertTrue(result.timedOut)
        assertFalse(result.ok)
    }

    @Test
    fun `failure in an output consumer closes the session`(): Unit = runBlocking {
        sh {
            val pid = runtime.processId!!
            println("Task-owned interpreter for output-consumer failure: $pid")
            assertFailsWith<IllegalArgumentException> {
                "printf 'ready\\n'; sleep 10"(output = { throw IllegalArgumentException("consumer failed") })
            }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertFailsWith<IllegalStateException> { "printf must-not-run"() }
        }
    }

    @Test
    fun `timeout also stops the exact child recorded from its owned shell`(): Unit = runBlocking {
        sh {
            val sessionPid = runtime.processId!!
            var child: ProcessHandle? = null
            val result = "sleep 10 & fixture=\$!; printf '%s\\n' \"\$fixture\"; wait \"\$fixture\""(
                timeout = 200.milliseconds,
                idleTimeout = 2.seconds,
                output = {
                    if (it.stream == RuntimeStream.Stdout) {
                        val handle = ProcessHandle.of(it.text.toLong()).orElseThrow()
                        assertEquals(sessionPid, handle.parent().orElseThrow().pid())
                        println("Task-owned child ${handle.pid()} verified under interpreter $sessionPid")
                        child = handle
                    }
                },
            )
            assertTrue(result.timedOut)
            val handle = checkNotNull(child)
            withTimeout(2.seconds) {
                while (handle.isAlive) delay(10)
            }
            assertFalse(handle.isAlive)
        }
    }
}
