package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RuntimeTraceTimestampWireTest {
    @Test
    fun `JSONL preserves coarse backward and extreme epoch timestamps without repairing event order`() {
        for ((start, end) in listOf(0L to 0L, 10L to 5L, (Long.MAX_VALUE - 1) to Long.MAX_VALUE, Long.MAX_VALUE to Long.MAX_VALUE)) {
            val buffer = RuntimeTraceBuffer()
            buffer.onEvent(RuntimeSessionStarted("clock", "sh", start, 123, listOf("bash")))
            buffer.onEvent(RuntimeSessionClosed("clock", "sh", end, 123, false))
            val lines = mutableListOf<String>()
            val sink = RuntimeJsonlTraceSink(lines::add)
            buffer.events.forEach(sink::onEvent)
            val records = lines.map { Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject }
            assertEquals(listOf("session-started", "session-closed"), records.map { it.getValue("event").jsonPrimitive.content })
            assertEquals(listOf(start, end), records.map { it.getValue("atEpochMillis").jsonPrimitive.long })
            assertEquals(listOf(1L, 1L), records.map { it.getValue("protocolVersion").jsonPrimitive.long })
            assertFalse(records.any { it.getValue("atEpochMillis").jsonPrimitive.isString })
        }
    }
}
