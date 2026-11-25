package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeTraceStreamOrderTest {
    @Test
    fun `concurrent repeated sessions retain every stream line with fresh dispatch sequences`() = runBlocking {
        val trace = RuntimeTraceBuffer()
        val owned = CopyOnWriteArrayList<ProcessHandle>()
        val sink = RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeSessionStarted) {
                val handle = ProcessHandle.of(event.processId).orElseThrow()
                owned += handle
                println("TRACE_STREAM_ORDER_PID pid=${handle.pid()} start=${handle.info().startInstant().orElseThrow()}")
            }
        }
        val shell = "for ((i=0; i<64; i++)); do printf 'out-%02d\\n' \"${'$'}i\"; printf 'err-%02d\\n' \"${'$'}i\" >&2; done"
        val python = "import sys\nfor i in range(64):\n    print(f'out-{i:02d}')\n    print(f'err-{i:02d}', file=sys.stderr)"
        val javascript = "for (let i=0; i<64; i++) { console.log('out-'+String(i).padStart(2,'0')); console.error('err-'+String(i).padStart(2,'0')); }"
        val results = withTimeout(20.seconds) {
            withRuntimeTrace(sink) {
                listOf(
                    async { sh { List(2) { shell() } } },
                    async { py { List(2) { python() } } },
                    async { node { List(2) { javascript() } } },
                ).awaitAll().flatten()
            }
        }
        val stdout = (0 until 64).map { "out-${it.toString().padStart(2, '0')}" }
        val stderr = (0 until 64).map { "err-${it.toString().padStart(2, '0')}" }
        assertEquals(6, results.size)
        results.forEach { result ->
            assertTrue(result.ok)
            assertEquals(stdout, result.stdout)
            assertEquals(stderr, result.stderr)
        }
        val events = trace.events
        val sessions = events.filterIsInstance<RuntimeSessionStarted>()
        assertEquals(setOf("sh", "py", "node"), sessions.map { it.runtime }.toSet())
        assertEquals(3, sessions.size)
        assertEquals(3, owned.size)
        assertTrue(owned.none(ProcessHandle::isAlive))
        assertEquals(768, events.filterIsInstance<RuntimeDispatchOutput>().size)
        for (session in sessions) {
            val own = events.filter { it.sessionId == session.sessionId }
            assertEquals(session, own.first())
            val closed = assertIs<RuntimeSessionClosed>(own.last())
            assertEquals(session.processId, closed.processId)
            assertFalse(closed.poisoned)
            val starts = own.filterIsInstance<RuntimeDispatchStarted>()
            assertEquals(listOf(1L, 2L), starts.map { it.dispatchId })
            for (start in starts) {
                val output = own.filterIsInstance<RuntimeDispatchOutput>().filter { it.dispatchId == start.dispatchId }
                val terminal = own.filterIsInstance<RuntimeDispatchCompleted>().single { it.dispatchId == start.dispatchId }
                val result = results.single { it.sessionId == session.sessionId && it.dispatchId == start.dispatchId }
                assertEquals((0 until 128).toList(), output.map { it.sequence })
                assertEquals(stdout, output.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
                assertEquals(stderr, output.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
                assertEquals(result.stdout.size, terminal.stdoutLines)
                assertEquals(result.stderr.size, terminal.stderrLines)
                assertEquals(64, terminal.stdoutLines)
                assertEquals(64, terminal.stderrLines)
                assertEquals(0, terminal.exitCode)
                assertFalse(terminal.timedOut || terminal.idleTimedOut || terminal.outputLimited || terminal.processExited)
                assertTrue(own.indexOf(start) < own.indexOf(output.first()))
                assertTrue(own.indexOf(output.last()) < own.indexOf(terminal))
            }
        }
    }
}
