package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Timeout(15)
class RuntimeFailureOutputCountsTest {
    @Test
    fun `closure from an output callback freezes the already captured counts`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        runCatching {
            withRuntimeTrace(trace) {
                sh {
                    "printf 'ready\\n'; sleep 30"(output = { runtimeContext.runtime.close() })
                }
            }
        }
        val events = trace.events
        assertEquals(listOf("ready"), events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
        assertEquals(JsonPrimitive(1), failed.toTraceJson()["stdoutLines"])
        assertEquals(JsonPrimitive(0), failed.toTraceJson()["stderrLines"])
        assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        val pid = events.filterIsInstance<RuntimeSessionStarted>().single().processId
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `callback failure records the captured line counts before owned cleanup`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val failure = IllegalStateException("consumer failed")
        var seen = 0
        val actual = runCatching {
            withRuntimeTrace(trace) {
                sh {
                    "printf 'ready\\n'; printf 'diagnostic\\n' >&2"(output = {
                        if (++seen == 2) throw failure
                    })
                }
            }
        }.exceptionOrNull()
        assertEquals(failure.message, assertIs<IllegalStateException>(actual).message)
        val events = trace.events
        val outputs = events.filterIsInstance<RuntimeDispatchOutput>()
        assertEquals(setOf(RuntimeStream.Stdout to "ready", RuntimeStream.Stderr to "diagnostic"),
            outputs.map { it.stream to it.text }.toSet())
        val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
        assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertTrue(events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        val pid = events.filterIsInstance<RuntimeSessionStarted>().single().processId
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(JsonPrimitive(1), failed.toTraceJson()["stdoutLines"])
        assertEquals(JsonPrimitive(1), failed.toTraceJson()["stderrLines"])
    }
}
