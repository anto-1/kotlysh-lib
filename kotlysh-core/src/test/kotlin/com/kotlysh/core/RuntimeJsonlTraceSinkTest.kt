package com.kotlysh.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@Timeout(20)
class RuntimeJsonlTraceSinkTest {
    @Test
    fun `all event string fields preserve lone surrogates through a UTF8 writer`() {
        val text = "high:\uD800 low:\uDC00 pair:😀 literal:\\uD800 controls:\r\n\u0000"
        val session = "session:$text"
        val runtime = "runtime:$text"
        val events = listOf(
            RuntimeSessionStarted(session, runtime, 1, 1, listOf(text)),
            RuntimeDispatchStarted(session, runtime, 1, 1, null, text, inputSessionIds = listOf(text)),
            RuntimeDispatchOutput(session, runtime, 1, 1, 0, RuntimeStream.Stdout, text),
            RuntimeDispatchOutput(session, runtime, 1, 1, 1, RuntimeStream.Stderr, text),
            RuntimeDispatchCompleted(session, runtime, 1, 1, 1, 0, false, false, false, false, 1, 1),
            RuntimeDispatchFailed(session, runtime, 1, 2, 1, text, text),
            RuntimeSessionClosed(session, runtime, 1, 1, false),
        )
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { writer ->
            val sink = RuntimeJsonlTraceSink(writer::println)
            events.forEach(sink::onEvent)
        }
        val records = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).toList()

        assertEquals(events.size, records.size)
        for ((event, record) in events.zip(records)) {
            assertEquals(event.toTraceJson(), Json.parseToJsonElement(record.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)))
        }
    }

    @Test
    fun `valid Unicode pairs literal escapes and controls are unchanged on the wire`() {
        val text = "😀\\uD800\\uDC00\r\n\u0000"
        val event = output("unicode").copy(text = text)
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { writer -> RuntimeJsonlTraceSink(writer::println).onEvent(event) }

        val record = bytes.toString(Charsets.UTF_8).trimEnd('\n', '\r')
        assertTrue("😀" in record)
        assertEquals(event.toTraceJson(), Json.parseToJsonElement(record.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)))
    }

    @Test
    fun `a real dispatch failure diagnostic preserves lone surrogates through UTF8`(): Unit = runBlocking {
        val text = "diagnostic:\uD800 / \uDC00 / 😀"
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { writer ->
            val actual = runCatching {
                withRuntimeTrace(RuntimeJsonlTraceSink(writer::println)) {
                    sh { "printf 'ready\\n'"(output = { throw IllegalArgumentException(text) }) }
                }
            }.exceptionOrNull()
            assertTrue(actual is IllegalArgumentException)
        }
        val records = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty)
            .map { Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject }.toList()
        val failure = records.single { it.getValue("event").jsonPrimitive.content == "dispatch-failed" }
        assertEquals(text, failure.getValue("message").jsonPrimitive.content)
        assertTrue(records.single { it.getValue("event").jsonPrimitive.content == "session-closed" }
            .getValue("poisoned").jsonPrimitive.content == "true")
    }

    @Test
    fun `bounded command previews preserve complete Unicode pairs`() {
        assertEquals(listOf("…"), boundedCommand(listOf("😀tail"), 2))
        assertEquals(listOf("😀…"), boundedCommand(listOf("😀tail"), 3))
        assertEquals(listOf("A…"), boundedCommand(listOf("A😀tail"), 3))
        assertEquals(listOf("A😀"), boundedCommand(listOf("A😀"), 3))
        assertEquals(listOf("a", "…"), boundedCommand(listOf("a", "😀tail"), 3))
    }

    @Test
    fun `concurrent sessions cannot splice records through a non-thread-safe writer`() {
        val written = StringBuilder()
        val firstWrite = AtomicBoolean(true)
        val firstWriterEntered = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val secondCallFinished = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val sink = RuntimeJsonlTraceSink { line ->
            if (firstWrite.compareAndSet(true, false)) {
                val split = line.length / 2
                written.append(line.take(split))
                firstWriterEntered.countDown()
                check(releaseFirstWriter.await(5, TimeUnit.SECONDS)) { "First writer was not released" }
                written.append(line.drop(split)).append('\n')
            } else {
                written.append(line).append('\n')
            }
        }
        val first = Thread {
            try {
                sink.onEvent(output("first"))
            } catch (failure: Throwable) {
                failures += failure
            }
        }.apply { isDaemon = true }
        val second = Thread {
            try {
                sink.onEvent(output("second"))
            } catch (failure: Throwable) {
                failures += failure
            } finally {
                secondCallFinished.countDown()
            }
        }.apply { isDaemon = true }

        try {
            first.start()
            assertTrue(firstWriterEntered.await(5, TimeUnit.SECONDS))
            second.start()
            // Release the first writer only after the other publication either
            // finishes or waits for serialization. This forces interleaving in
            // an unprotected callback without relying on scheduling delays.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val waiting = setOf(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING)
            while (secondCallFinished.count > 0 && second.state !in waiting && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue(secondCallFinished.count == 0L || second.state in waiting)
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
            second.join(5_000)
        }

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(failures.isEmpty(), "Writer failed: $failures")
        val lines = written.toString().lineSequence().filter(String::isNotEmpty).toList()
        assertEquals(2, lines.size)
        val records = lines.map { line ->
            assertTrue(line.startsWith(RUNTIME_TRACE_JSONL_PREFIX))
            Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
        }
        assertEquals(listOf("first", "second"), records.map { it.getValue("sessionId").jsonPrimitive.content })
        assertTrue(records.all { it.getValue("protocolVersion").jsonPrimitive.content == "1" })
    }

    @Test
    fun `writer failure does not prevent later records`() {
        val lines = mutableListOf<String>()
        var failNextWrite = true
        val sink = RuntimeJsonlTraceSink { line ->
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("Transient writer failure")
            }
            lines += line
        }

        assertFailsWith<IllegalStateException> { sink.onEvent(output("failed")) }
        sink.onEvent(output("recovered"))

        val record = Json.parseToJsonElement(lines.single().removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
        assertEquals("recovered", record.getValue("sessionId").jsonPrimitive.content)
    }

    private fun output(sessionId: String): RuntimeDispatchOutput = RuntimeDispatchOutput(
        sessionId = sessionId,
        runtime = "sh",
        atEpochMillis = 1,
        dispatchId = 1,
        sequence = 0,
        stream = RuntimeStream.Stdout,
        text = "ready",
    )
}
