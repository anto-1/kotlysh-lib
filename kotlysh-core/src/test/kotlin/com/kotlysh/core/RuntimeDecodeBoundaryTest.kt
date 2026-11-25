package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeDecodeBoundaryTest {
    @Test
    fun `large custom decoder diagnostics are bounded without changing the cause or successful trace`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val message = "decoder detail: " + "x".repeat(8_100_000)
                val original = IllegalArgumentException(message)
                val actual = assertIs<RuntimeDecodeException>(decodeFailure(original))
                val detail = assertNotNull(actual.message)
                assertTrue(detail.length <= 4_096, "Diagnostic length: ${detail.length}")
                val prefix = "${runtime.name} returned a value incompatible with the requested Kotlin type: decoder detail: "
                assertEquals(prefix + "x".repeat(4_095 - prefix.length) + "…", detail)
                assertSame(original, actual.cause)
                assertSame(message, original.message)
                assertTrue(actual.execution.ok)
                assertEquals(listOf("decode-log"), actual.execution.stdout)
                assertEquals(listOf("decode-warning"), actual.execution.stderr)
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(4, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    @Test
    fun `decoder diagnostic truncation preserves Unicode boundaries and complete short diagnostics`(): Unit = runBlocking {
        eachRuntime {
            val prefix = "${runtime.name} returned a value incompatible with the requested Kotlin type: "
            for ((message, expected) in listOf(
                "x".repeat(4_094 - prefix.length) + "😀tail" to prefix + "x".repeat(4_094 - prefix.length) + "…",
                "x".repeat(4_093 - prefix.length) + "😀tail" to prefix + "x".repeat(4_093 - prefix.length) + "😀…",
                "x".repeat(4_094 - prefix.length) + "😀" to prefix + "x".repeat(4_094 - prefix.length) + "😀",
                "<html>literal</html>\r\n\u0000 lone:\uD800 / \uDC00" to prefix + "<html>literal</html>\r\n\u0000 lone:\uD800 / \uDC00",
            )) {
                val original = IllegalArgumentException(message)
                val actual = assertIs<RuntimeDecodeException>(decodeFailure(original))
                assertTrue(actual.message!!.length <= 4_096)
                assertEquals(expected, actual.message)
                assertSame(original, actual.cause)
                assertSame(message, original.message)
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `the complete decode wrapper is bounded even with a large runtime name without skipping critical getters`() {
        val name = "runtime:" + "x".repeat(8_100_000)
        val execution = RuntimeResult(name, emptyList(), emptyList(), 0)
        val original = IllegalArgumentException("decoder detail")
        val actual = RuntimeDecodeException(execution, original)
        assertTrue(actual.message!!.length <= 4_096)
        assertEquals(name.take(4_095) + "…", actual.message)
        assertSame(execution, actual.execution)
        assertSame(original, actual.cause)
        val cancelled = CancellationException("diagnostic getter cancelled")
        val hostile = object : Exception() {
            override val message: String? get() = throw cancelled
        }
        assertSame(cancelled, assertFailsWith<CancellationException> { RuntimeDecodeException(execution, hostile) })
    }

    @Test
    fun `a hostile decoder diagnostic preserves its original cause and successful execution`(): Unit = runBlocking {
        eachRuntime {
            val diagnostic = IllegalStateException("diagnostic getter failed")
            val original = object : Exception("decoder failed") {
                override val message: String? get() = throw diagnostic
            }
            val actual = assertIs<RuntimeDecodeException>(decodeFailure(original))

            assertSame(original, actual.cause)
            assertTrue(actual.execution.ok)
            assertEquals(listOf("decode-log"), actual.execution.stdout)
            assertEquals(listOf("decode-warning"), actual.execution.stderr)
            assertEquals("${runtime.name} returned a value incompatible with the requested Kotlin type", actual.message)
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `ordinary decoder diagnostics retain useful messages and usable sessions`(): Unit = runBlocking {
        eachRuntime {
            for (original in listOf(IllegalArgumentException("custom decoder detail"), IllegalArgumentException())) {
                val actual = assertIs<RuntimeDecodeException>(decodeFailure(original))

                assertSame(original, actual.cause)
                assertTrue(actual.execution.ok)
                assertEquals(listOf("decode-log"), actual.execution.stdout)
                assertEquals(listOf("decode-warning"), actual.execution.stderr)
                val prefix = "${runtime.name} returned a value incompatible with the requested Kotlin type"
                assertEquals(prefix + (original.message?.let { ": $it" } ?: ""), actual.message)
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `cancellation from custom decoders or diagnostic getters remains cancellation`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val cancellation = CancellationException("decoder cancellation")
                val hostile = object : Exception("decoder failed") {
                    override val message: String? get() = throw cancellation
                }
                for (original in listOf(cancellation, hostile)) {
                    assertSame(cancellation, decodeFailure(original))
                    assertEquals(42, eval<Int>("42"))
                }
            }
        }
        assertSuccessfulDecoderTrace(trace)
    }

    @Test
    fun `fatal custom decoder and diagnostic getter errors propagate unchanged`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val fatal = OutOfMemoryError("simulated decoder error")
                val hostile = object : Exception("decoder failed") {
                    override val message: String? get() = throw fatal
                }
                for (original in listOf(fatal, hostile)) {
                    assertSame(fatal, decodeFailure(original))
                    assertEquals(42, eval<Int>("42"))
                }
            }
        }
        assertSuccessfulDecoderTrace(trace)
    }

    private fun assertSuccessfulDecoderTrace(trace: RuntimeTraceBuffer) {
        val starts = trace.events.filterIsInstance<RuntimeDispatchStarted>()
        val completed = trace.events.filterIsInstance<RuntimeDispatchCompleted>()
        assertEquals(8, starts.size)
        assertEquals(starts.map { it.sessionId to it.dispatchId }, completed.map { it.sessionId to it.dispatchId })
        assertTrue(completed.all { it.exitCode == 0 && it.errorType == null && it.message == null })
        assertTrue(completed.none { it.timedOut || it.idleTimedOut || it.outputLimited || it.processExited })
        val output = trace.events.filterIsInstance<RuntimeDispatchOutput>()
            .groupBy { it.sessionId to it.dispatchId }
        for ((index, start) in starts.withIndex()) {
            val lines = output[start.sessionId to start.dispatchId].orEmpty()
            assertEquals(if (index % 2 == 0) listOf("decode-log") else emptyList(),
                lines.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
            assertEquals(if (index % 2 == 0) listOf("decode-warning") else emptyList(),
                lines.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        val closed = trace.events.filterIsInstance<RuntimeSessionClosed>()
        assertEquals(2, closed.size)
        assertTrue(closed.none { it.poisoned })
    }

    private suspend fun StructuredRuntimeScope.decodeFailure(original: Throwable): Throwable? {
        val token = UUID.randomUUID().toString()
        RuntimeDecodeBoundarySerializer.failures[token] = original
        val expression = if (runtime.name == "py") {
            "(__import__('sys').stderr.write('decode-warning\\n'), print('decode-log', flush=True), ${pythonLiteral(token)})[-1]"
        } else {
            "(console.error('decode-warning'), console.log('decode-log'), ${jsLiteral(token)})"
        }
        return try {
            runCatching { evaluate<RuntimeDecodeBoundaryValue>(expression, timeout = 5.seconds) }.exceptionOrNull()
        } finally {
            RuntimeDecodeBoundarySerializer.failures.remove(token)
        }
    }

    private suspend fun eachRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }
}

@Serializable(with = RuntimeDecodeBoundarySerializer::class)
internal class RuntimeDecodeBoundaryValue {
    companion object {
        // Exercise reified decoding without adding a compiler plugin to core tests.
        fun serializer(): KSerializer<RuntimeDecodeBoundaryValue> = RuntimeDecodeBoundarySerializer
    }
}

internal object RuntimeDecodeBoundarySerializer : KSerializer<RuntimeDecodeBoundaryValue> {
    val failures = ConcurrentHashMap<String, Throwable>()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RuntimeDecodeBoundaryValue", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: RuntimeDecodeBoundaryValue): Unit = error("Decode-only test serializer")
    override fun deserialize(decoder: Decoder): RuntimeDecodeBoundaryValue = throw failures.getValue(decoder.decodeString())
}
