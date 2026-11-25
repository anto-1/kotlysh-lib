package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Timeout(15)
class ExecLifecycleTest {
    @Test
    fun `deadline terminates owned descendants and preserves output`(): Unit = runBlocking {
        var handles: List<ProcessHandle> = emptyList()
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = exec(shell(waitingTree), timeout = 400.milliseconds, output = {
                handles = treeHandles(it)
            })
            assertTrue(result.timedOut)
            assertEquals(null, result.exitCode)
            assertEquals(1, result.stdout.size)
            assertEquals(2, handles.size)
            assertTrue(started.elapsedNow() < 2.seconds, "cleanup took ${started.elapsedNow()}")
            assertStopped(handles)
        } finally {
            cleanup(handles)
        }
    }

    @Test
    fun `parent cancellation interrupts exec without a configured deadline`(): Unit = runBlocking {
        val ready = CompletableDeferred<List<ProcessHandle>>()
        var handles: List<ProcessHandle> = emptyList()
        val work = async(Dispatchers.IO) {
            exec(shell(waitingTree), timeout = null, output = { ready.complete(treeHandles(it)) })
        }
        try {
            handles = withTimeout(5.seconds) { ready.await() }
            val started = TimeSource.Monotonic.markNow()
            withTimeout(2.seconds) { work.cancelAndJoin() }
            assertTrue(started.elapsedNow() < 2.seconds)
            assertStopped(handles)
        } finally {
            cleanup(handles)
            work.cancelAndJoin()
        }
    }

    @Test
    fun `consumer failure propagates and terminates the owned tree`(): Unit = runBlocking {
        var handles: List<ProcessHandle> = emptyList()
        try {
            val failure = assertFailsWith<IllegalStateException> {
                exec(shell(waitingTree), timeout = null, output = {
                    handles = treeHandles(it)
                    throw IllegalStateException("consumer failed")
                })
            }
            assertEquals("consumer failed", failure.message)
            assertEquals(2, handles.size)
            assertStopped(handles)
        } finally {
            cleanup(handles)
        }
    }

    @Test
    fun `consumer IOException propagates instead of becoming stream EOF`(): Unit = runBlocking {
        val failure = assertFailsWith<java.io.IOException> {
            exec(shell("printf 'ready\\n'; exit 0"), output = { throw java.io.IOException("sink failed") })
        }
        assertEquals("sink failed", failure.message)
    }

    @Test
    fun `deadline retains final unterminated output on both streams`(): Unit = runBlocking {
        val result = exec(shell("printf partial; printf diagnostic >&2; sleep 10"), timeout = 200.milliseconds)
        assertTrue(result.timedOut)
        assertEquals(listOf("partial"), result.stdout)
        assertEquals(listOf("diagnostic"), result.stderr)
    }

    @Test
    fun `nonpositive deadlines are rejected before spawning a command`(): Unit = runBlocking {
        for (timeout in listOf(Duration.ZERO, (-1).milliseconds)) {
            assertFailsWith<IllegalArgumentException> {
                exec(listOf("/definitely/not/a/kotlysh-command"), timeout = timeout)
            }
        }
    }

    @Test
    fun `line capture preserves CR CRLF empty lines and Unicode without final newline`(): Unit = runBlocking {
        val result = exec(shell("printf 'α\\r\\n\\nβ\\r😀'; printf 'err\\r\\ntail' >&2; exit 7"))
        assertEquals(listOf("α", "", "β", "😀"), result.stdout)
        assertEquals(listOf("err", "tail"), result.stderr)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `output callbacks are serialized across simultaneous streams`(): Unit = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val count = AtomicInteger()
        val result = exec(shell(
            "(for ((i=0;i<40;i++)); do printf 'out:%s\\n' \"\$i\"; done) & " +
                "for ((i=0;i<40;i++)); do printf 'err:%s\\n' \"\$i\" >&2; done; wait",
        ), output = {
            val concurrent = active.incrementAndGet()
            maximum.accumulateAndGet(concurrent, ::maxOf)
            try {
                Thread.sleep(1)
                count.incrementAndGet()
            } finally {
                active.decrementAndGet()
            }
        })
        assertTrue(result.ok)
        assertEquals(40, result.stdout.size)
        assertEquals(40, result.stderr.size)
        assertEquals(80, count.get())
        assertEquals(1, maximum.get())
    }

    @Test
    fun `root exit stops waiting for inherited descendant pipes`(): Unit = runBlocking {
        var handles: List<ProcessHandle> = emptyList()
        val directory = Files.createTempDirectory("kotlysh-exec-exit-")
        val acknowledged = directory.resolve("acknowledged")
        try {
            val started = TimeSource.Monotonic.markNow()
            val result = exec(shell(
                "sleep 10 & child=\$!; printf '%s:%s\\n' \"\$\$\" \"\$child\"; " +
                    "while [[ ! -e ${shLiteral(acknowledged.toString())} ]]; do sleep 0.01; done; exit 7",
            ), timeout = 3.seconds, output = {
                handles = treeHandles(it)
                Files.createFile(acknowledged)
            })
            assertEquals(7, result.exitCode)
            assertFalse(result.timedOut)
            assertEquals(2, handles.size)
            assertTrue(started.elapsedNow() < 2.seconds, "inherited pipes took ${started.elapsedNow()}")
            assertFalse(handles.first().isAlive)
            // Reader completion does not depend on the inherited child pipe;
            // cleanup remains restricted to previously observed identities.
        } finally {
            cleanup(handles)
            Files.deleteIfExists(acknowledged)
            Files.deleteIfExists(directory)
        }
    }

    private fun shell(script: String) = listOf("/bin/bash", "--noprofile", "--norc", "-c", script)

    private fun treeHandles(line: RuntimeOutputLine): List<ProcessHandle> {
        assertEquals(RuntimeStream.Stdout, line.stream)
        val handles = line.text.split(':').map { ProcessHandle.of(it.toLong()).orElseThrow() }
        assertEquals(2, handles.size)
        assertEquals(handles.first().pid(), handles.last().parent().orElseThrow().pid())
        return handles
    }

    private suspend fun assertStopped(handles: List<ProcessHandle>) {
        withTimeout(2.seconds) { while (handles.any { it.isAlive }) delay(10) }
        assertTrue(handles.none { it.isAlive })
    }

    private fun cleanup(handles: List<ProcessHandle>) {
        handles.asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    private val waitingTree =
        "sleep 10 & child=\$!; printf '%s:%s\\n' \"\$\$\" \"\$child\"; wait \"\$child\""
}
