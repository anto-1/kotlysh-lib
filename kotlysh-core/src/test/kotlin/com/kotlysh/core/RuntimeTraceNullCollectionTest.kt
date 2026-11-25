package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeTraceNullCollectionTest {
    @Test
    fun `null contributed commands fail before writer calls and leave the sink reusable`() {
        for (values in listOf(listOf(null), listOf(null, "tail"), listOf("command", null), listOf("command", null, "tail"))) {
            val lines = mutableListOf<String>()
            val sink = RuntimeJsonlTraceSink(lines::add)
            val event = started("invalid", values.asContributedStrings())
            assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
            assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
            assertTrue(lines.isEmpty())
            sink.onEvent(started("valid", listOf("command", "", "pair:😀 lone:\uD800")))
            assertEquals(listOf("valid"), lines.map(::sessionId))
        }
    }

    @Test
    fun `null contributed lineage uses the same validation boundary without writer calls`() {
        val lines = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(lines::add)
        val event = RuntimeDispatchStarted("invalid", "sh", 1, 7, null, null, "store",
            listOf("owner", null, "tail").asContributedStrings())
        assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
        assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `invalid reentrant commands preserve already queued valid records and later sink reuse`() {
        val lines = mutableListOf<String>()
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            lines += line
            if (first) {
                first = false
                sink.onEvent(started("queued", listOf("command")))
                sink.onEvent(started("invalid", listOf("command", null).asContributedStrings()))
            }
        }
        assertFailsWith<IllegalArgumentException> { sink.onEvent(started("first", listOf("command"))) }
        sink.onEvent(started("later", listOf("command")))
        assertEquals(listOf("first", "queued", "later"), lines.map(::sessionId))
    }

    @Test
    fun `null command validation stops before later producer callbacks and captures valid items once`() {
        val reads = mutableListOf<Int>()
        val invalid = object : AbstractList<String?>() {
            override val size: Int get() = 3
            override fun get(index: Int): String? {
                reads += index
                return when (index) {
                    0 -> "command"
                    1 -> null
                    else -> throw AssertionError("Invalid command must not read later callbacks")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { started("invalid", invalid.asContributedStrings()).toTraceJson() }
        assertEquals(listOf(0, 1), reads)
        val counts = IntArray(2)
        val valid = object : AbstractList<String?>() {
            override val size: Int get() = 2
            override fun get(index: Int): String? = if (counts[index]++ == 0) "item-$index" else null
        }
        val json = started("valid", valid.asContributedStrings()).toTraceJson()
        assertEquals("[\"item-0\",\"item-1\"]", json.getValue("command").toString())
        assertEquals(listOf(1, 1), counts.toList())
    }

    // Java List<String> permits null entries; the wire boundary must validate them.
    @Suppress("UNCHECKED_CAST")
    private fun List<String?>.asContributedStrings(): List<String> = this as List<String>

    private fun started(id: String, command: List<String>) = RuntimeSessionStarted(id, "sh", 1, 123, command)
    private fun sessionId(line: String) = Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX))
        .jsonObject.getValue("sessionId").jsonPrimitive.content
}
