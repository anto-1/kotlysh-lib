package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeTraceIdentityTest {
    private fun events(session: String, runtime: String): List<RuntimeTraceEvent> = listOf(
        RuntimeSessionStarted(session, runtime, 1, 123, listOf("custom-command", "")),
        RuntimeDispatchStarted(session, runtime, 2, 1, null, null, inputSessionIds = listOf("source")),
        RuntimeDispatchOutput(session, runtime, 3, 1, 0, RuntimeStream.Stdout, ""),
        RuntimeDispatchCompleted(session, runtime, 4, 1, 2, 0, false, false, false, false, 1, 0),
        RuntimeDispatchFailed(session, runtime, 5, 2, 1, "CustomFailure", null),
        RuntimeSessionClosed(session, runtime, 6, 123, false),
    )

    @Test
    fun `blank identities cannot reach JSONL writers for any lifecycle event`() {
        for (blank in listOf("", " \t\r\n", "\u00a0\u2003")) {
            for ((session, runtime) in listOf(blank to "custom", "session" to blank)) {
                val written = mutableListOf<String>()
                val sink = RuntimeJsonlTraceSink(written::add)
                for (event in events(session, runtime)) {
                    assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
                    assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
                }
                assertTrue(written.isEmpty())
                sink.onEvent(events("valid", "custom").first())
                assertEquals(1, written.size)
            }
        }
    }

    @Test
    fun `nonblank identity whitespace is preserved exactly through JSONL`() {
        val session = " \towner:😀\n "
        val runtime = " custom runtime "
        val written = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(written::add)
        val events = events(session, runtime)
        events.forEach(sink::onEvent)
        for ((event, line) in events.zip(written)) {
            val json = Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
            assertEquals(session, json.getValue("sessionId").jsonPrimitive.content)
            assertEquals(runtime, json.getValue("runtime").jsonPrimitive.content)
            assertEquals(event.toTraceJson(), json)
        }
        assertEquals(events.size, written.size)
    }

    @Test
    fun `invalid reentrant publication preserves queued valid lines and later sink reuse`() {
        val written = mutableListOf<String>()
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            written += line
            if (first) {
                first = false
                sink.onEvent(events("queued", "custom").first())
                sink.onEvent(events("invalid", " ").first())
            }
        }
        assertFailsWith<IllegalArgumentException> { sink.onEvent(events("first", "custom").first()) }
        sink.onEvent(events("later", "custom").first())
        assertEquals(listOf("first", "queued", "later"), written.map {
            Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject.getValue("sessionId").jsonPrimitive.content
        })
    }
}
