package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Timeout(20)
class RuntimeCapturedCountBoundsTest {
    @Test
    fun `completed and failed sequences exactly cover captured stdout and stderr including empty lines`(): Unit = runBlocking {
        for (consumerFails in listOf(false, true)) {
            val trace = RuntimeTraceBuffer()
            var seen = 0
            val outcome = runCatching {
                withRuntimeTrace(trace) {
                    sh {
                        "printf 'first\\n\\n'; printf 'diagnostic\\n\\n' >&2"(output = {
                            if (++seen == 4 && consumerFails) throw IllegalStateException("expected sequence consumer failure")
                        })
                    }
                }
            }
            val events = trace.events
            val pid = events.filterIsInstance<RuntimeSessionStarted>().single().processId
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            val output = events.filterIsInstance<RuntimeDispatchOutput>()
            assertEquals(4, output.size)
            assertEquals(output.indices.toList(), output.map { it.sequence })
            assertEquals(listOf("first", ""), output.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
            assertEquals(listOf("diagnostic", ""), output.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
            for (line in output) {
                val json = line.toTraceJson()
                assertEquals(JsonPrimitive(line.text), json["text"])
                assertEquals(JsonPrimitive(if (line.stream == RuntimeStream.Stdout) "stdout" else "stderr"), json["stream"])
            }
            val terminal = events.single { it is RuntimeDispatchCompleted || it is RuntimeDispatchFailed }
            assertEquals(JsonPrimitive(2), terminal.toTraceJson()["stdoutLines"])
            assertEquals(JsonPrimitive(2), terminal.toTraceJson()["stderrLines"])
            if (consumerFails) {
                assertIs<RuntimeDispatchFailed>(terminal)
                assertEquals("expected sequence consumer failure", assertIs<IllegalStateException>(outcome.exceptionOrNull()).message)
            } else {
                assertIs<RuntimeDispatchCompleted>(terminal)
                assertTrue(outcome.getOrThrow().ok)
            }
            assertEquals(consumerFails, events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        }
    }

    @Test
    fun `completed and failed counts share the configured budget across both streams`(): Unit = runBlocking {
        for (consumerFails in listOf(false, true)) {
            val trace = RuntimeTraceBuffer()
            val outcome = runCatching {
                withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputLinesPerDispatch = 1)) {
                    sh {
                        "printf 'stdout\\n'; printf 'stderr\\n' >&2"(output = {
                            if (consumerFails) throw IllegalStateException("expected consumer failure")
                        })
                    }
                }
            }
            val events = trace.events
            val pid = events.filterIsInstance<RuntimeSessionStarted>().single().processId
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            val output = events.filterIsInstance<RuntimeDispatchOutput>()
            assertEquals(1, output.size)
            assertEquals(0, output.single().sequence)
            val terminal = events.single { it is RuntimeDispatchCompleted || it is RuntimeDispatchFailed }
            assertEquals(JsonPrimitive(output.count { it.stream == RuntimeStream.Stdout }), terminal.toTraceJson()["stdoutLines"])
            assertEquals(JsonPrimitive(output.count { it.stream == RuntimeStream.Stderr }), terminal.toTraceJson()["stderrLines"])
            if (consumerFails) {
                assertIs<RuntimeDispatchFailed>(terminal)
                assertEquals("expected consumer failure", assertIs<IllegalStateException>(outcome.exceptionOrNull()).message)
            } else {
                assertTrue(assertIs<RuntimeDispatchCompleted>(terminal).outputLimited)
                assertTrue(outcome.getOrThrow().outputLimited)
            }
            assertTrue(events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        }
    }
}
