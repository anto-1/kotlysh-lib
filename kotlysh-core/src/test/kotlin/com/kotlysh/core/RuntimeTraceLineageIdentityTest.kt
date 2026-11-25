package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeTraceLineageIdentityTest {
    @Test
    fun `blank lineage identities fail before publication and leave the sink reusable`() {
        for (blank in listOf("", " \t\r\n", "\u00a0\u2003")) {
            for (ids in listOf(listOf(blank), listOf(blank, "owner"), listOf("owner", blank))) {
                val lines = mutableListOf<String>()
                val sink = RuntimeJsonlTraceSink(lines::add)
                val event = started(ids)
                assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
                assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
                assertTrue(lines.isEmpty())
                sink.onEvent(started(listOf("owner")))
                assertEquals(listOf("owner"), lineage(lines.single()))
            }
        }
    }

    @Test
    fun `empty lineage and exact nonblank identities preserve valid command arguments`() {
        val ids = listOf(" \towner:😀\n ", "owner:\uD800", "owner:\uDC00")
        val lines = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(lines::add)
        sink.onEvent(started(emptyList()))
        sink.onEvent(started(ids))
        sink.onEvent(RuntimeSessionStarted("live", "sh", 1, 1, listOf("bash", "")))
        assertEquals(emptyList(), lineage(lines[0]))
        assertEquals(ids, lineage(lines[1]))
        assertEquals(listOf("bash", ""), json(lines[2]).getValue("command").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `each producer lineage element is captured and validated in one read`() {
        val reads = IntArray(2)
        val ids = object : AbstractList<String>() {
            override val size: Int get() = 2
            override fun get(index: Int): String = if (reads[index]++ == 0) "owner-$index" else ""
        }
        assertEquals(listOf("owner-0", "owner-1"), started(ids).toTraceJson()
            .getValue("inputSessionIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(1, 1), reads.toList())
    }

    @Test
    fun `invalid reentrant lineage does not drop queued valid records`() {
        val lines = mutableListOf<String>()
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            lines += line
            if (first) {
                first = false
                sink.onEvent(started(listOf("queued")))
                sink.onEvent(started(listOf("")))
            }
        }
        assertFailsWith<IllegalArgumentException> { sink.onEvent(started(listOf("first"))) }
        sink.onEvent(started(listOf("later")))
        assertEquals(listOf(listOf("first"), listOf("queued"), listOf("later")), lines.map(::lineage))
    }

    @Test
    fun `blank lineage is rejected before reading later producer callbacks`() {
        val reads = mutableListOf<Int>()
        val ids = object : AbstractList<String>() {
            override val size: Int get() = 3
            override fun get(index: Int): String {
                reads += index
                return when (index) {
                    0 -> "owner"
                    1 -> " "
                    else -> error("Invalid lineage must not read later elements")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { started(ids).toTraceJson() }
        assertEquals(listOf(0, 1), reads)
    }

    private fun started(ids: List<String>) = RuntimeDispatchStarted("live", "sh", 1, 7, null, null, "store", ids)
    private fun json(line: String) = Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
    private fun lineage(line: String) = json(line).getValue("inputSessionIds").jsonArray.map { it.jsonPrimitive.content }
}
