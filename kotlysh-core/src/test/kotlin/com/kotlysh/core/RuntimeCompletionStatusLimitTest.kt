package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(40)
class RuntimeCompletionStatusLimitTest {
    private val statusLimit = 8 * 1024 * 1024
    private val envelope = """{"version":1,"kind":"value","value":42}"""

    @Test
    fun `unfinished stdout statuses fail before a deadline`(): Unit = runBlocking {
        assertStatusLimited(RuntimeStream.Stdout, finished = false)
    }

    @Test
    fun `unfinished stderr statuses fail before a deadline`(): Unit = runBlocking {
        assertStatusLimited(RuntimeStream.Stderr, finished = false)
    }

    @Test
    fun `untraced overlong statuses also poison and stop their owned session`(): Unit = runBlocking {
        val observed = mutableListOf<RuntimeOutputLine>()
        runtime(adapter(RuntimeStream.Stdout, count = statusLimit + 1, suffix = "")) {
            val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
            val failure = assertFailsWith<IllegalStateException> {
                "ignored"(timeout = 3.seconds, output = observed::add)
            }
            assertTrue(failure.message.orEmpty().contains("completion status"), failure.message)
            assertFalse(process.isAlive)
            assertFalse(runtime.isOpen)
            assertEquals(listOf("visible"), observed.map { it.text })
            assertEquals(listOf(RuntimeStream.Stdout), observed.map { it.stream })
        }
    }

    @Test
    fun `overlong completed statuses cannot publish completion`(): Unit = runBlocking {
        for (stream in RuntimeStream.entries) assertStatusLimited(stream, finished = true)
    }

    @Test
    fun `exact status limits preserve signs line endings and reusable sessions`(): Unit = runBlocking {
        for (stream in RuntimeStream.entries) {
            for (suffix in listOf("\\n", "\\r\\n")) {
                val spec = adapter(stream, count = statusLimit - 1, sign = "+", suffix = suffix)
                val trace = RuntimeTraceBuffer()
                withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 7)) {
                    runtime(spec) {
                        repeat(2) {
                            val result = "ignored"(timeout = 10.seconds)
                            assertTrue(result.ok, "$stream/$suffix: $result")
                            assertEquals(listOf("visible"), if (stream == RuntimeStream.Stdout) result.stdout else result.stderr)
                            assertTrue(runtime.isOpen)
                        }
                    }
                }
                assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
                assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
            }
        }
    }

    @Test
    fun `exact status limits stay independent of a small decoded payload budget`(): Unit = runBlocking {
        val payload = Base64.getEncoder().encodeToString(envelope.toByteArray())
        val spec = adapter(RuntimeStream.Stdout, count = statusLimit, suffix = ":$payload\\n", prefix = "")
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = envelope.toByteArray().size)) {
            runtime(spec) {
                val structured = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                val result = structured.evaluateJson("ignored")
                assertEquals(JsonPrimitive(42), result.value)
                assertTrue(result.execution.ok)
                assertTrue(result.execution.stdout.isEmpty())
                assertTrue(result.execution.stderr.isEmpty())
            }
        }
    }

    @Test
    fun `an invalid status remains ordinary bounded output`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 512)) {
            runtime(adapter(RuntimeStream.Stdout, count = statusLimit + 1, sign = "invalid", suffix = "")) {
                "ignored"(timeout = 3.seconds)
            }
        }
        assertTrue(result.outputLimited)
        assertFalse(result.timedOut)
        assertEquals(512, result.stdout.single().length)
        assertTrue(result.stdout.single().startsWith("visible__KOTLYSH_"))
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
    }

    private suspend fun assertStatusLimited(stream: RuntimeStream, finished: Boolean) {
        val trace = RuntimeTraceBuffer()
        val spec = adapter(stream, count = statusLimit + 1, suffix = if (finished) "\\n" else "")
        withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 7)) {
            runtime(spec) {
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val failure = assertFailsWith<IllegalStateException> { "ignored"(timeout = 3.seconds) }
                assertTrue(failure.message.orEmpty().contains("completion status"), failure.message)
                assertTrue(failure.message.orEmpty().contains(statusLimit.toString()), failure.message)
                assertFalse(process.isAlive)
                assertFalse(runtime.isOpen)
            }
        }
        val output = trace.events.filterIsInstance<RuntimeDispatchOutput>().single()
        assertEquals(stream, output.stream)
        assertEquals("visible", output.text)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private fun adapter(
        stream: RuntimeStream,
        count: Int,
        sign: String = "",
        suffix: String,
        prefix: String = "visible",
    ): RuntimeSpec {
        val frame: (String) -> String = { marker ->
            val other = if (stream == RuntimeStream.Stdout) "stderr" else "stdout"
            "python3 -u -c 'import sys,time; " +
                "sys.$other.write(\"$marker:0\\n\"); " +
                "sys.${stream.name.lowercase()}.write(\"$prefix$marker:$sign\" + \"0\" * $count + \"$suffix\"); " +
                "${if (suffix.isEmpty()) "time.sleep(30); " else ""}'\n"
        }
        return RuntimeSpec("status-limit", RuntimeSpecs.Bash.command,
            wrap = { _, marker -> frame(marker) }, requestWrapper = { _, marker -> frame(marker) })
    }
}
