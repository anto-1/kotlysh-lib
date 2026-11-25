package com.kotlysh.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@Timeout(20)
class RuntimeJsonlTraceReentrancyTest {
    @Test
    fun `reentrant publications cannot splice physical records`() {
        val events = listOf(output("outer"), output("nested"), output("last"))
        val text = StringBuilder()
        var first = true
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            if (first) {
                first = false
                val split = line.length / 2
                text.append(line.take(split))
                sink.onEvent(events[1])
                sink.onEvent(events[2])
                text.append(line.drop(split)).append('\n')
            } else {
                text.append(line).append('\n')
            }
        }
        sink.onEvent(events.first())
        val records = text.toString().lineSequence().filter(String::isNotEmpty).toList()
        assertEquals(events.size, records.size)
        assertEquals(events.map { it.toTraceJson() }, records.map {
            Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX))
        })
    }

    @Test
    fun `ordinary writer failures drain queued records and preserve their first identity`() {
        val primary = IllegalStateException("outer writer")
        val secondary = IllegalArgumentException("nested writer")
        val written = mutableListOf<String>()
        var invocation = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            when (invocation++) {
                0 -> {
                    sink.onEvent(output("nested-failure"))
                    sink.onEvent(output("repeated-nested-failure"))
                    sink.onEvent(output("queued-success"))
                    throw primary
                }
                1, 2 -> throw secondary
                else -> written += line
            }
        }
        assertSame(primary, assertFailsWith<IllegalStateException> { sink.onEvent(output("outer")) })
        assertEquals(listOf(secondary), primary.suppressed.toList())
        sink.onEvent(output("recovered"))
        assertEquals(listOf(output("queued-success"), output("recovered")).map { it.toTraceJson() },
            written.map { Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)) })
        assertEquals(5, invocation)
    }

    @Test
    fun `a long reentrant chain uses one callback frame at a time`() {
        var count = 0
        var depth = 0
        var maximumDepth = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink {
            depth++
            maximumDepth = maxOf(maximumDepth, depth)
            try {
                if (++count < 10_000) sink.onEvent(output("nested-$count"))
            } finally {
                depth--
            }
        }
        sink.onEvent(output("outer"))
        assertEquals(10_000, count)
        assertEquals(1, maximumDepth)
    }

    @Test
    fun `repeated identical writer exceptions never self suppress or skip queued attempts`() {
        val failure = IllegalStateException("same writer failure")
        var count = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink {
            if (count++ == 0) {
                sink.onEvent(output("first-queued"))
                sink.onEvent(output("second-queued"))
            }
            throw failure
        }
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.onEvent(output("outer")) })
        assertEquals(3, count)
        assertEquals(emptyList(), failure.suppressed.toList())
    }

    @Test
    fun `queued fatal failures take precedence over ordinary failures and permit later publications`() {
        val ordinary = IllegalStateException("outer writer")
        val fatal = WriterError()
        var invocation = 0
        val written = mutableListOf<String>()
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            when (invocation++) {
                0 -> {
                    sink.onEvent(output("fatal"))
                    sink.onEvent(output("discarded"))
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
        assertEquals(3, invocation)
        assertEquals(listOf(output("recovered").toTraceJson()),
            written.map { Json.parseToJsonElement(it.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)) })
    }

    @Test
    fun `a fatal current writer discards its pending records without retrying them`() {
        val fatal = WriterError()
        var count = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink {
            if (count++ == 0) {
                sink.onEvent(output("discarded"))
                throw fatal
            }
        }
        assertSame(fatal, assertFailsWith<WriterError> { sink.onEvent(output("outer")) })
        assertEquals(1, count)
        sink.onEvent(output("recovered"))
        assertEquals(2, count)
    }

    @Test
    fun `direct cancellation exceptions retain their identity after queued delivery`() {
        val cancellation = CancellationException("writer cancelled")
        var count = 0
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink {
            if (count++ == 0) {
                sink.onEvent(output("queued"))
                throw cancellation
            }
        }
        assertSame(cancellation, assertFailsWith<CancellationException> { sink.onEvent(output("outer")) })
        assertEquals(2, count)
    }

    private fun output(session: String) = RuntimeDispatchOutput(session, "sh", 1, 1, 0, RuntimeStream.Stdout, "ready")

    private class WriterError : Error("fatal writer")
}
