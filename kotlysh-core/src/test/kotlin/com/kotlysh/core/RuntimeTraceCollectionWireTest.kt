package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTraceCollectionWireTest {
    @Test
    fun `JSONL preserves complete contributed commands for consumers with their own preview limits`() {
        val values = listOf("node", "x".repeat(5_000), "pair:😀 lone:\uD800") + List(2_000) { "" }
        val event = RuntimeSessionStarted("collection", "node", 1, 123, values)
        val json = wire(event)
        assertEquals(values, json.getValue("command").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("collection", json.getValue("sessionId").jsonPrimitive.content)
        assertEquals("1", json.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals(event.toTraceJson(), json)
    }

    @Test
    fun `JSONL preserves full lineage identities without turning prefixes into foreign owners`() {
        val values = listOf("same" + "a".repeat(5_000), "same" + "b".repeat(5_000), "pair:😀 lone:\uDC00") + List(2_000) { "owner-$it" }
        val event = RuntimeDispatchStarted("collection", "node", 1, 7, null, null, "store", values)
        val json = wire(event)
        assertEquals(values, json.getValue("inputSessionIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("7", json.getValue("dispatchId").jsonPrimitive.content)
        assertEquals("store", json.getValue("operation").jsonPrimitive.content)
        assertEquals(event.toTraceJson(), json)
    }

    private fun wire(event: RuntimeTraceEvent) = buildList {
        RuntimeJsonlTraceSink(::add).onEvent(event)
    }.single().toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        .removePrefix(RUNTIME_TRACE_JSONL_PREFIX).let { Json.parseToJsonElement(it).jsonObject }
}
