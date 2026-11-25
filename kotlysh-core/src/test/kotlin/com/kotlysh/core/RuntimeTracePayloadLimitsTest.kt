package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeTracePayloadLimitsTest {
    @Test
    fun `default collector drops a single oversized caller payload`() {
        val buffer = RuntimeTraceBuffer()
        buffer.onEvent(RuntimeDispatchOutput("s", "custom", 1, 1, 0, RuntimeStream.Stdout, "x".repeat(1_100_000)))
        assertTrue(buffer.events.isEmpty())
        assertEquals(1L, buffer.droppedEventCount)
    }

    @Test
    fun `transport rejects oversized declared collections before reading their entries`() {
        val records = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(maxPendingCharacters = 256, writeLine = records::add)
        val oversized = object : AbstractList<String>() {
            override val size: Int get() = 1_000
            override fun get(index: Int): String = error("An inadmissible collection entry was read")
        }
        sink.onEvent(RuntimeSessionStarted("s", "custom", 1, 123, oversized))
        assertEquals(1L, sink.droppedEventCount)
        val notice = Json.parseToJsonElement(records.single().removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
        assertEquals("trace-dropped", notice.getValue("event").jsonPrimitive.content)
    }

    @Test
    fun `transport stops at invalid entries before later producer callbacks`() {
        for (lineage in listOf(false, true)) {
            for (invalid in if (lineage) listOf(null, " ") else listOf(null)) {
                var reads = 0
                val metadata = object : AbstractList<String?>() {
                    override val size: Int get() = 2
                    override fun get(index: Int): String? {
                        reads++
                        check(index == 0) { "Callbacks after invalid metadata must not run" }
                        return invalid
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val strings = metadata as List<String>
                val event = if (lineage) RuntimeDispatchStarted("s", "r", 1, 1, null, null, inputSessionIds = strings)
                    else RuntimeSessionStarted("s", "r", 1, 123, strings)
                val records = mutableListOf<String>()
                val sink = RuntimeJsonlTraceSink(writeLine = records::add)
                assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
                assertEquals(1, reads)
                assertTrue(records.isEmpty())
                sink.onEvent(output("valid", "ok"))
                assertEquals(1, records.size)
            }
        }
    }

    @Test
    fun `transport scalar validation runs before producer collection callbacks`() {
        val hostile = object : AbstractList<String>() {
            override val size: Int get() = error("Invalid header must not inspect metadata")
            override fun get(index: Int): String = error("Invalid header must not inspect metadata")
        }
        val sink = RuntimeJsonlTraceSink {}
        for (event in listOf(
            RuntimeSessionStarted("s", "r", 1, 0, hostile),
            RuntimeDispatchStarted("s", "r", 1, 0, null, null, inputSessionIds = hostile),
            RuntimeSessionStarted(" ", "r", 1, 123, hostile),
            RuntimeSessionStarted("s", "r", -1, 123, hostile),
        )) {
            assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
        }
    }

    @Test
    fun `collector character limit is positive and legacy constructors keep their default`() {
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { RuntimeTraceBuffer(maxCharacters = invalid) }
        }
        assertEquals(1_048_576, RuntimeTraceBuffer().maxCharacters)
        assertEquals(1_048_576, RuntimeTraceBuffer(1).maxCharacters)
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val legacy = RuntimeTraceBuffer::class.java.getConstructor(Integer.TYPE, Integer.TYPE, marker)
            .newInstance(123, 1, null)
        assertEquals(4_096, legacy.maxEvents)
        assertEquals(1_048_576, legacy.maxCharacters)
    }

    @Test
    fun `metadata capture stops at an oversized entry in either collection field`() {
        var reads = 0
        val metadata = object : AbstractList<String>() {
            override val size: Int get() = 2
            override fun get(index: Int): String {
                reads++
                check(index == 0) { "Metadata was inspected after the budget was exhausted" }
                return "x".repeat(1_000)
            }
        }
        val events = listOf(
            RuntimeSessionStarted("s", "r", 1, 123, metadata),
            RuntimeDispatchStarted("s", "r", 1, 1, null, null, inputSessionIds = metadata),
        )
        val buffer = RuntimeTraceBuffer(maxCharacters = 256)
        val sink = RuntimeJsonlTraceSink(maxPendingCharacters = 256) {}
        events.forEach { buffer.onEvent(it); sink.onEvent(it) }
        assertEquals(4, reads)
        assertEquals(2L, buffer.droppedEventCount)
        assertEquals(2L, sink.droppedEventCount)
    }

    @Test
    fun `oversized scalar metadata does not inspect producer collections`() {
        val hostile = object : AbstractList<String>() {
            override val size: Int get() = error("Rejected metadata was inspected")
            override fun get(index: Int): String = error("Rejected metadata was inspected")
        }
        val event = RuntimeDispatchStarted("s", "r", 1, 1, null, "x".repeat(1_000), inputSessionIds = hostile)
        val buffer = RuntimeTraceBuffer(maxCharacters = 256)
        val sink = RuntimeJsonlTraceSink(maxPendingCharacters = 256) {}
        buffer.onEvent(event)
        sink.onEvent(event)
        assertEquals(1L, buffer.droppedEventCount)
        assertEquals(1L, sink.droppedEventCount)
    }

    @Test
    fun `collector accepts exact aggregate budgets without truncating events`() {
        val buffer = RuntimeTraceBuffer(maxEvents = 100, maxCharacters = 8)
        buffer.onEvent(output("s", "x")) // One event + two identities + one text unit = 4.
        buffer.onEvent(output("t", "y"))
        buffer.onEvent(output("u", ""))
        assertEquals(listOf("s", "t"), buffer.events.map { it.sessionId })
        assertEquals(listOf("x", "y"), buffer.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        assertEquals(1L, buffer.droppedEventCount)
    }

    @Test
    fun `oversized publication does not prevent smaller later publications`() {
        val buffer = RuntimeTraceBuffer(maxCharacters = 5)
        buffer.onEvent(output("s", "too large"))
        buffer.onEvent(output("t", "ok"))
        assertEquals(listOf("t"), buffer.events.map { it.sessionId })
        assertEquals(1L, buffer.droppedEventCount)
    }

    @Test
    fun `empty collection entries consume quota before they can be retained`() {
        var reads = 0
        val emptyArguments = object : AbstractList<String>() {
            override val size: Int get() = 61
            override fun get(index: Int): String { reads++; return "" }
        }
        val exact = RuntimeTraceBuffer(maxCharacters = 64)
        exact.onEvent(RuntimeSessionStarted("s", "r", 1, 123, emptyArguments))
        assertEquals(61, assertIs<RuntimeSessionStarted>(exact.events.single()).command.size)
        assertEquals(61, reads)
        val small = RuntimeTraceBuffer(maxCharacters = 63)
        small.onEvent(RuntimeSessionStarted("s", "r", 1, 123, emptyArguments))
        assertTrue(small.events.isEmpty())
        assertEquals(61, reads, "Inadmissible entries should never be read")
        assertEquals(1L, small.droppedEventCount)
    }

    @Test
    fun `every scalar string field participates in the payload budget`() {
        val large = "x".repeat(100)
        val events = listOf(
            RuntimeSessionClosed(large, "r", 1, 123, false),
            RuntimeSessionClosed("s", large, 1, 123, false),
            RuntimeSessionStarted("s", "r", 1, 123, listOf(large)),
            RuntimeDispatchStarted("s", "r", 1, 1, large, null),
            RuntimeDispatchStarted("s", "r", 1, 1, null, large),
            RuntimeDispatchStarted("s", "r", 1, 1, null, null, large),
            RuntimeDispatchStarted("s", "r", 1, 1, null, null, inputSessionIds = listOf(large)),
            output("s", large),
            RuntimeDispatchCompleted("s", "r", 1, 1, 1, 0, false, false, false, false, 0, 0, large, null),
            RuntimeDispatchCompleted("s", "r", 1, 1, 1, 0, false, false, false, false, 0, 0, null, large),
            RuntimeDispatchFailed("s", "r", 1, 1, 1, large, null),
            RuntimeDispatchFailed("s", "r", 1, 1, 1, "Error", large),
        )
        val buffer = RuntimeTraceBuffer(maxCharacters = 64)
        events.forEach(buffer::onEvent)
        assertTrue(buffer.events.isEmpty())
        assertEquals(events.size.toLong(), buffer.droppedEventCount)
    }

    @Test
    fun `snapshot reentrancy rechecks character admission without holding the collector lock`() {
        val buffer = RuntimeTraceBuffer(maxCharacters = 6)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val metadata = object : AbstractList<String>() {
                override val size: Int get() = 1
                override fun get(index: Int): String {
                    worker.submit { buffer.onEvent(RuntimeSessionClosed("n", "r", 1, 123, false)) }
                        .get(5, TimeUnit.SECONDS)
                    return "x"
                }
            }
            buffer.onEvent(RuntimeSessionStarted("s", "r", 1, 123, metadata))
            assertEquals(listOf("n"), buffer.events.map { it.sessionId })
            assertEquals(1L, buffer.droppedEventCount)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent publishers account for every character rejection`() {
        val buffer = RuntimeTraceBuffer(maxCharacters = 96)
        val worker = Executors.newFixedThreadPool(8)
        try {
            (0 until 8).map { producer -> worker.submit {
                repeat(100) { buffer.onEvent(output("$producer:$it", "")) }
            } }.forEach { it.get(5, TimeUnit.SECONDS) }
            val events = buffer.events
            assertTrue(events.sumOf { 1 + it.sessionId.length + it.runtime.length } <= 96)
            assertEquals(800L, events.size + buffer.droppedEventCount)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun output(session: String, text: String) =
        RuntimeDispatchOutput(session, "r", 1, 1, 0, RuntimeStream.Stdout, text)
}
