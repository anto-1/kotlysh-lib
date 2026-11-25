package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(20)
class RuntimeJsonlTraceCooperationTest {
    @Test
    fun `writer can await another publisher without holding the queue lock`() = withPool { pool ->
        val events = listOf(output("outer"), output("nested"), output("worker"))
        val written = StringBuilder()
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            if (first) {
                first = false
                val split = line.length / 2
                written.append(line.take(split))
                sink.onEvent(events[1])
                pool.submit { sink.onEvent(events[2]) }.get(2, TimeUnit.SECONDS)
                written.append(line.drop(split)).append('\n')
            } else written.append(line).append('\n')
        }
        sink.onEvent(events.first())
        assertEquals(events.map { it.toTraceJson() }, written.lineSequence().filter(String::isNotEmpty).map {
            Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX))
        }.toList())
    }

    @Test
    fun `concurrent admission returns while an ordinary failure drains to its owner`() = withPool { pool ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val primary = IllegalStateException("active writer")
        val written = mutableListOf<String>()
        var first = true
        val sink = RuntimeJsonlTraceSink { line ->
            if (first) {
                first = false
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw primary
            }
            written += line
        }
        val owner = pool.submit { sink.onEvent(output("outer")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.submit { sink.onEvent(output("queued")) }.get(2, TimeUnit.SECONDS)
            assertTrue(written.isEmpty(), "Queued callback must wait for the active writer")
        } finally {
            release.countDown()
        }
        assertSame(primary, assertFailsWith<ExecutionException> { owner.get(5, TimeUnit.SECONDS) }.cause)
        sink.onEvent(output("recovered"))
        assertEquals(listOf("queued", "recovered"), written.map(::session))
    }

    @Test
    fun `concurrently queued fatal writer errors discard pending records and allow reuse`() = withPool { pool ->
        val ordinary = IllegalStateException("active writer")
        val fatal = WriterError()
        val written = mutableListOf<String>()
        var invocation = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            when (invocation++) {
                0 -> {
                    pool.submit {
                        sink.onEvent(output("fatal"))
                        sink.onEvent(output("discarded"))
                    }.get(2, TimeUnit.SECONDS)
                    throw ordinary
                }
                1 -> throw fatal
                else -> written += line
            }
        }
        assertSame(fatal, assertFailsWith<WriterError> { sink.onEvent(output("outer")) })
        assertEquals(listOf(ordinary), fatal.suppressed.toList())
        assertEquals(2, invocation)
        sink.onEvent(output("recovered"))
        assertEquals(listOf("recovered"), written.map(::session))
        assertEquals(3, invocation)
    }

    @Test
    fun `contended drain ownership preserves complete records and per producer order`() = withPool(4) { pool ->
        val barrier = CyclicBarrier(4)
        val activeWriters = AtomicInteger()
        val written = StringBuilder()
        val sink = RuntimeJsonlTraceSink { line ->
            assertEquals(1, activeWriters.incrementAndGet())
            try { written.append(line).append('\n') }
            finally { activeWriters.decrementAndGet() }
        }
        val publishers = (0 until 4).map { producer ->
            pool.submit {
                repeat(100) { index ->
                    barrier.await(5, TimeUnit.SECONDS)
                    sink.onEvent(output("$producer:$index"))
                }
            }
        }
        publishers.forEach { it.get(10, TimeUnit.SECONDS) }
        val sessions = written.lineSequence().filter(String::isNotEmpty).map(::session).toList()
        assertEquals(400, sessions.size)
        assertEquals(400, sessions.toSet().size)
        for (producer in 0 until 4) {
            assertEquals((0 until 100).map { "$producer:$it" }, sessions.filter { it.startsWith("$producer:") })
        }
        assertEquals(0, activeWriters.get())
    }

    private fun output(session: String) = RuntimeDispatchOutput(session, "sh", 1, 1, 0, RuntimeStream.Stdout, "ready")
    private fun session(line: String) = Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX))
        .jsonObject.getValue("sessionId").jsonPrimitive.content

    private fun <T> withPool(size: Int = 2, block: (ExecutorService) -> T): T {
        val pool = Executors.newFixedThreadPool(size)
        try { return block(pool) }
        finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Owned publisher threads did not stop")
        }
    }

    private class WriterError : Error("fatal writer")
}
