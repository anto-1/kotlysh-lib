package com.kotlysh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeSessionTraceTest {
    @Test
    fun closureFinishesPendingOutputBeforeItsTerminalEvent() {
        val events = mutableListOf<RuntimeTraceEvent>()
        val ledger = ledger(events)
        val id = ledger.startDispatch("echo value", "exec", emptyList(), System.nanoTime(), 1)
        ledger.enqueue(RuntimeDispatchOutput("session", "test", 2, id, 1, RuntimeStream.Stdout, "value"))
        ledger.beginClose()
        ledger.enqueue(RuntimeDispatchOutput("session", "test", 3, id, 2, RuntimeStream.Stdout, "late"))
        ledger.enqueue(RuntimeSessionClosed("session", "test", 4, null, false))
        ledger.drain()

        assertEquals(4, events.size)
        assertEquals("value", assertIs<RuntimeDispatchOutput>(events[1]).text)
        assertEquals(1, assertIs<RuntimeDispatchFailed>(events[2]).stdoutLines)
        assertIs<RuntimeSessionClosed>(events[3])
        assertFailsWith<IllegalStateException> {
            ledger.startDispatch("late", "exec", emptyList(), System.nanoTime(), 5)
        }
    }

    @Test
    fun queuedTimeoutRemainsTheDispatchTerminalFactWhenClosureFollows() {
        val events = mutableListOf<RuntimeTraceEvent>()
        val ledger = ledger(events)
        val timeout = ledger.expiredBeforeSubmission("echo value", "exec", System.nanoTime(), 1)
        assertTrue(events.isEmpty())
        ledger.beginClose()
        ledger.enqueue(RuntimeSessionClosed("session", "test", 2, null, false))
        ledger.drain()

        assertEquals(3, events.size)
        val failure = assertIs<RuntimeDispatchFailed>(events[1])
        assertEquals(timeout.dispatchId, failure.dispatchId)
        assertEquals("kotlinx.coroutines.TimeoutCancellationException", failure.errorType)
        assertEquals(0, failure.stdoutLines)
        assertIs<RuntimeSessionClosed>(events[2])
    }

    @Test
    fun observerClosureIsDeliveredAfterTheCurrentCallbackReturns() {
        val delivery = mutableListOf<String>()
        lateinit var ledger: RuntimeSessionTrace
        val sink = RuntimeTraceSink { event ->
            delivery += event.javaClass.simpleName
            if (event is RuntimeDispatchStarted) {
                ledger.beginClose()
                ledger.publish(RuntimeSessionClosed("session", "test", 2, null, false))
                delivery += "callback returned"
            }
        }
        ledger = RuntimeSessionTrace("session", "test", RuntimeTraceContext(sink, RuntimeTraceLimits()))
        ledger.startDispatch("echo value", "exec", emptyList(), System.nanoTime(), 1)

        assertEquals(listOf("RuntimeDispatchStarted", "callback returned", "RuntimeDispatchFailed", "RuntimeSessionClosed"), delivery)
    }

    private fun ledger(events: MutableList<RuntimeTraceEvent>) = RuntimeSessionTrace(
        "session", "test", RuntimeTraceContext(RuntimeTraceSink { events += it }, RuntimeTraceLimits()),
    )
}
