package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RuntimeTraceSessionOrderTest {
    @Test
    fun `concurrent built-in sessions publish their command before any dispatch facts`() = runBlocking {
        val trace = RuntimeTraceBuffer()
        withTimeout(20.seconds) {
            withRuntimeTrace(trace) {
                listOf(
                    async { sh { "printf 'ready\\n'"() } },
                    async { py { "print('ready')"() } },
                    async { node { "console.log('ready');"() } },
                ).awaitAll().forEach { result ->
                    assertTrue(result.ok)
                    assertEquals(listOf("ready"), result.stdout)
                    assertTrue(result.stderr.isEmpty())
                }
            }
        }
        val events = trace.events
        val sessions = events.filterIsInstance<RuntimeSessionStarted>()
        assertEquals(setOf("sh", "py", "node"), sessions.map { it.runtime }.toSet())
        assertEquals(3, sessions.size)
        for (session in sessions) {
            val own = events.filter { it.sessionId == session.sessionId }
            assertEquals(session, own.first())
            assertTrue(session.command.isNotEmpty())
            assertIs<RuntimeDispatchStarted>(own[1])
            assertIs<RuntimeDispatchOutput>(own[2])
            val completed = assertIs<RuntimeDispatchCompleted>(own[3])
            val closed = assertIs<RuntimeSessionClosed>(own[4])
            assertEquals(5, own.size)
            assertEquals(0, completed.exitCode)
            assertFalse(closed.poisoned)
            assertEquals(session.processId, closed.processId)
            assertFalse(ProcessHandle.of(session.processId).map { it.isAlive }.orElse(false))
        }
    }
}
