package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(15)
class RuntimeCommandPreviewTest {
    @Test
    fun `empty arguments cannot bypass preview bounds or change launched arguments`(): Unit = runBlocking {
        val command = listOf("/bin/bash", "--noprofile", "--norc", "-s", "--") +
            List(5_000) { "" } + "tail:😀"
        for (budget in listOf(64, 1_024, 8_192)) {
            val trace = RuntimeTraceBuffer()
            val jsonl = RuntimeJsonlTraceSink(::println)
            val sink = RuntimeTraceSink { event ->
                trace.onEvent(event)
                jsonl.onEvent(event)
            }
            val job = SupervisorJob()
            val owner = CoroutineScope(job + Dispatchers.IO +
                RuntimeTraceContext(sink, RuntimeTraceLimits(maxCommandCharacters = budget)))
            val runtime = ProcessRuntime(owner, RuntimeSpec("sh", command, RuntimeSpecs.Bash.wrap))
            try {
                runtime.start()
                val result = runtime.exec("printf '%s\\n' \"\$#\" \"\$1\" \"\${!#}\"")
                assertTrue(result.ok)
                assertEquals(listOf("5001", "", "tail:😀"), result.stdout)
                assertTrue(runtime.exec("true").ok)

                val started = trace.events.filterIsInstance<RuntimeSessionStarted>().single()
                val preview = started.command
                assertTrue(preview.sumOf { it.length.coerceAtLeast(1) } <= budget,
                    "Budget $budget retained ${preview.size} arguments")
                assertEquals(command.take(preview.size), preview)
                if (budget == 8_192) {
                    assertEquals(command, preview)
                    assertFalse(started.commandTruncated)
                    assertNull(started.toTraceJson()["commandTruncated"])
                } else {
                    assertTrue(preview.size < command.size)
                    assertTrue(started.commandTruncated)
                    assertEquals("true", started.toTraceJson()["commandTruncated"]?.jsonPrimitive?.content)
                }
                assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
            } finally {
                runtime.closeSuspending()
                job.cancelAndJoin()
            }
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
        }
    }

    @Test
    fun `existing constructor references and copies preserve command preview facts`() {
        val constructor: (String, String, Long, Long, List<String>) -> RuntimeSessionStarted = ::RuntimeSessionStarted
        val started = constructor("legacy", "sh", 1, 123, listOf("bash"))
        assertFalse(started.commandTruncated)
        assertNull(started.toTraceJson()["commandTruncated"])
        val clipped = started.copy(commandTruncated = true)
        assertTrue(clipped.copy(runtime = "custom").commandTruncated)
        assertEquals(started.command, clipped.component5())
    }
}
