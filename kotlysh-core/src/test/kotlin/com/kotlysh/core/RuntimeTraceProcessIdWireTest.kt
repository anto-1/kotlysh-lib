package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeTraceProcessIdWireTest {
    @Test
    fun `nonpositive process ids fail before JSONL publication and leave the sink reusable`() {
        for (pid in listOf(0L, -1L, Long.MIN_VALUE)) {
            val lines = mutableListOf<String>()
            val sink = RuntimeJsonlTraceSink(lines::add)
            val events = listOf(
                RuntimeSessionStarted("invalid", "sh", 1, pid, listOf("bash")),
                RuntimeSessionClosed("invalid", "sh", 2, pid, false),
            )
            for (event in events) {
                assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
                assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
            }
            assertTrue(lines.isEmpty())
            sink.onEvent(RuntimeSessionStarted("valid", "sh", 3, 1, listOf("bash")))
            assertEquals(1L, Json.parseToJsonElement(lines.single().removePrefix(RUNTIME_TRACE_JSONL_PREFIX))
                .jsonObject.getValue("processId").jsonPrimitive.long)
        }
    }

    @Test
    fun `invalid process id is rejected before reading a producer owned command list`() {
        val command = object : AbstractList<String>() {
            override val size: Int get() = error("Invalid event must not read command size")
            override fun get(index: Int): String = error("Invalid event must not read arguments")
        }
        val event = RuntimeSessionStarted("invalid", "sh", 1, 0, command)
        assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
    }

    @Test
    fun `invalid reentrant process id does not drop queued valid records`() {
        val lines = mutableListOf<String>()
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            lines += line
            if (first) {
                first = false
                sink.onEvent(RuntimeSessionStarted("queued", "sh", 2, Long.MAX_VALUE, listOf("bash")))
                sink.onEvent(RuntimeSessionClosed("invalid", "sh", 3, -1, false))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            sink.onEvent(RuntimeSessionStarted("first", "sh", 1, 1, listOf("bash")))
        }
        sink.onEvent(RuntimeSessionClosed("later", "sh", 4, null, false))
        assertEquals(listOf("first", "queued", "later"), lines.map {
            Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX))
                .jsonObject.getValue("sessionId").jsonPrimitive.content
        })
    }

    @Test
    fun `session process ids remain exact optional numeric facts on the wire`() {
        val buffer = RuntimeTraceBuffer()
        for (pid in listOf(1L, Long.MAX_VALUE)) {
            buffer.onEvent(RuntimeSessionStarted("session-$pid", "sh", 10, pid, listOf("bash")))
            buffer.onEvent(RuntimeSessionClosed("session-$pid", "sh", 20, pid, false))
        }
        buffer.onEvent(RuntimeSessionClosed("unknown", "sh", 30, null, false))
        val lines = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(lines::add)
        buffer.events.forEach(sink::onEvent)
        val records = lines.map { Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject }
        assertEquals(listOf(1L, 1L, Long.MAX_VALUE, Long.MAX_VALUE), records.take(4).map { it.getValue("processId").jsonPrimitive.long })
        assertFalse(records.take(4).any { it.getValue("processId").jsonPrimitive.isString })
        assertEquals(JsonNull, records.last().getValue("processId"))
        assertEquals(listOf("session-started", "session-closed", "session-started", "session-closed", "session-closed"), records.map { it.getValue("event").jsonPrimitive.content })
    }
}
