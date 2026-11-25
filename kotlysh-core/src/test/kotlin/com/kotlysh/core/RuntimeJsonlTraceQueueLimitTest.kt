package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(20)
class RuntimeJsonlTraceQueueLimitTest {
    @Test
    fun `blocked writer bounds admission and reports all omitted records`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<String>()
        val pool = Executors.newSingleThreadExecutor()
        val sink = RuntimeJsonlTraceSink(maxPendingEvents = 2) { line ->
            if (written.isEmpty()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            written += line
        }
        try {
            val owner = pool.submit { sink.onEvent(output("active")) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(10_000) { sink.onEvent(output("$it")) }
            assertEquals(9_998L, sink.droppedEventCount)
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            val records = written.map(::decode)
            assertEquals(listOf("active", "0", "1"), records.filter {
                it.getValue("event").jsonPrimitive.content != "trace-dropped"
            }.map { it.getValue("sessionId").jsonPrimitive.content })
            assertEquals("9998", records.single {
                it.getValue("event").jsonPrimitive.content == "trace-dropped"
            }.getValue("droppedEvents").jsonPrimitive.content)
            sink.onEvent(output("recovered"))
            assertEquals("recovered", decode(written.last()).getValue("sessionId").jsonPrimitive.content)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `character capacity applies independently of the event count`() {
        val first = output("first")
        val lineSize = RUNTIME_TRACE_JSONL_PREFIX.length + first.toTraceJson().toString().length
        val written = mutableListOf<String>()
        var firstWrite = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink(maxPendingEvents = 100, maxPendingCharacters = lineSize) { line ->
            if (firstWrite) {
                firstWrite = false
                sink.onEvent(first)
                sink.onEvent(first)
            }
            written += line
        }
        sink.onEvent(first)
        assertEquals(1L, sink.droppedEventCount)
        assertEquals(2, written.map(::decode).count {
            it.getValue("event").jsonPrimitive.content == "dispatch-output"
        })
        assertEquals(1, written.map(::decode).count {
            it.getValue("event").jsonPrimitive.content == "trace-dropped"
        })
    }

    @Test
    fun `oversized standalone events produce a compact loss notice and recover`() {
        val written = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(maxPendingCharacters = 512, writeLine = written::add)
        sink.onEvent(output("oversized", "x".repeat(2_000)))
        assertEquals(1L, sink.droppedEventCount)
        assertEquals("trace-dropped", decode(written.single()).getValue("event").jsonPrimitive.content)
        sink.onEvent(output("recovered"))
        assertEquals("recovered", decode(written.last()).getValue("sessionId").jsonPrimitive.content)
    }

    @Test
    fun `full queue does not inspect rejected producer collections`() {
        val hostile = object : AbstractList<String>() {
            override val size: Int get() = error("Discarded command was inspected")
            override fun get(index: Int): String = error("Discarded command was inspected")
        }
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink(maxPendingEvents = 1) {
            if (first) {
                first = false
                sink.onEvent(output("queued"))
                sink.onEvent(RuntimeSessionStarted("discarded", "sh", 1, 123, hostile))
            }
        }
        sink.onEvent(output("active"))
        assertEquals(1L, sink.droppedEventCount)
    }

    @Test
    fun `limits require positive capacities`() {
        for (capacity in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { RuntimeJsonlTraceSink(maxPendingEvents = capacity) {} }
            assertFailsWith<IllegalArgumentException> { RuntimeJsonlTraceSink(maxPendingCharacters = capacity) {} }
        }
    }

    @Test
    fun `sustained distinct writer failures have bounded aggregation`() {
        val primary = IllegalStateException("first")
        var attempts = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink {
            if (++attempts < 1_000) sink.onEvent(output("next"))
            if (attempts == 1) throw primary
            throw IllegalStateException("failure $attempts")
        }
        assertSame(primary, assertFailsWith<IllegalStateException> { sink.onEvent(output("first")) })
        assertEquals(1_000, attempts)
        assertEquals(16, primary.suppressed.size)
        assertEquals(0L, sink.droppedEventCount)
    }

    private fun output(session: String, text: String = "ready") =
        RuntimeDispatchOutput(session, "sh", 1, 1, 0, RuntimeStream.Stdout, text)

    private fun decode(line: String) =
        Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
}
