package com.kotlysh.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeTraceBufferLimitTest {
    private fun event(id: String) = RuntimeSessionClosed(id, "custom", 1, 123, false)

    @Test
    fun `retention requires a positive capacity`() {
        for (capacity in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { RuntimeTraceBuffer(capacity) }
        }
    }

    @Test
    fun `default constructor bounds retained events and counts every omission`() {
        val buffer = RuntimeTraceBuffer()
        repeat(5_000) { buffer.onEvent(event("$it")) }
        assertEquals(4_096, buffer.maxEvents)
        assertEquals((0 until 4_096).map(Int::toString), buffer.events.map { it.sessionId })
        assertEquals(904L, buffer.droppedEventCount)
    }

    @Test
    fun `a bounded collector owns its retained prefix and old snapshots`() {
        val buffer = RuntimeTraceBuffer(maxEvents = 2)
        val command = mutableListOf("custom-command")
        val owners = mutableListOf("source")
        buffer.onEvent(RuntimeSessionStarted("s", "custom", 1, 123, command))
        val before = buffer.events
        buffer.onEvent(RuntimeDispatchStarted("s", "custom", 2, 1, null, null, "copy", owners))
        command.clear()
        owners.clear()
        repeat(3) { buffer.onEvent(event("dropped-$it")) }

        assertEquals(1, before.size)
        assertEquals(2, buffer.events.size)
        assertEquals(3L, buffer.droppedEventCount)
        assertEquals(listOf("custom-command"), assertIs<RuntimeSessionStarted>(before.single()).command)
        assertEquals(listOf("source"), assertIs<RuntimeDispatchStarted>(buffer.events.last()).inputSessionIds)
        assertFailsWith<UnsupportedOperationException> { (buffer.events as MutableList<RuntimeTraceEvent>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (assertIs<RuntimeDispatchStarted>(buffer.events.last()).inputSessionIds as MutableList<String>).clear()
        }
    }

    @Test
    fun `full collector does not inspect discarded producer collections`() {
        val buffer = RuntimeTraceBuffer(maxEvents = 1)
        buffer.onEvent(event("retained"))
        val hostile = object : AbstractList<String>() {
            override val size: Int get() = error("Discarded metadata was inspected")
            override fun get(index: Int): String = error("Discarded metadata was copied")
        }
        buffer.onEvent(RuntimeSessionStarted("dropped", "custom", 1, 123, hostile))
        buffer.onEvent(RuntimeDispatchStarted("dropped", "custom", 2, 1, null, null, "copy", hostile))
        assertEquals(listOf("retained"), buffer.events.map { it.sessionId })
        assertEquals(2L, buffer.droppedEventCount)
    }

    @Test
    fun `snapshot callbacks cannot overfill the collector`() {
        val buffer = RuntimeTraceBuffer(maxEvents = 1)
        var published = false
        val command = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String {
                if (!published) {
                    published = true
                    buffer.onEvent(event("nested"))
                }
                return "custom-command"
            }
        }
        buffer.onEvent(RuntimeSessionStarted("outer", "custom", 1, 123, command))
        assertTrue(published)
        assertEquals(listOf("nested"), buffer.events.map { it.sessionId })
        assertEquals(1L, buffer.droppedEventCount)
    }

    @Test
    fun `producer snapshot callbacks can wait for another publisher without holding the collector lock`() {
        val buffer = RuntimeTraceBuffer(maxEvents = 1)
        val worker = Executors.newSingleThreadExecutor()
        var published = false
        try {
            val command = object : AbstractList<String>() {
                override val size: Int get() = 1
                override fun get(index: Int): String {
                    if (!published) {
                        published = true
                        worker.submit { buffer.onEvent(event("other-thread")) }.get(10, TimeUnit.SECONDS)
                    }
                    return "custom-command"
                }
            }
            buffer.onEvent(RuntimeSessionStarted("outer", "custom", 1, 123, command))
            assertTrue(published)
            assertEquals(listOf("other-thread"), buffer.events.map { it.sessionId })
            assertEquals(1L, buffer.droppedEventCount)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent publishers retain capacity and account for every drop`() {
        val buffer = RuntimeTraceBuffer(maxEvents = 64)
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 8).map { producer ->
                workers.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(100) { buffer.onEvent(event("$producer-$it")) }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(64, buffer.events.size)
            assertEquals(64, buffer.events.map { it.sessionId }.toSet().size)
            assertEquals(736L, buffer.droppedEventCount)
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
