package com.kotlysh.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Timeout(20)
class RuntimeTraceTest {
    @Test
    fun `trace keeps session dispatch status and streams distinct`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val script = "printf 'out\\n'; printf 'err\\n' >&2"
        lateinit var result: RuntimeResult

        withRuntimeTrace(trace) {
            result = sh { script() }
        }

        val started = trace.events.filterIsInstance<RuntimeSessionStarted>().single()
        val dispatch = trace.events.filterIsInstance<RuntimeDispatchStarted>().single()
        val output = trace.events.filterIsInstance<RuntimeDispatchOutput>()
        val completed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single()
        val closed = trace.events.filterIsInstance<RuntimeSessionClosed>().single()

        assertEquals(started.sessionId, result.sessionId)
        assertEquals(dispatch.sessionId, result.sessionId)
        assertEquals(dispatch.dispatchId, result.dispatchId)
        assertEquals(sha256(script), dispatch.scriptSha256)
        assertEquals(script, dispatch.scriptPreview)
        assertEquals("exec", dispatch.operation)
        assertTrue(dispatch.inputSessionIds.isEmpty())
        assertEquals(listOf(0, 1), output.map(RuntimeDispatchOutput::sequence).sorted())
        assertEquals(listOf("out"), output.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
        assertEquals(listOf("err"), output.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        assertEquals(0, completed.exitCode)
        assertEquals(1, completed.stdoutLines)
        assertEquals(1, completed.stderrLines)
        assertFalse(completed.outputLimited)
        assertEquals(result.elapsedMillis, completed.elapsedMillis)
        assertTrue(result.elapsedMillis >= 0)
        assertEquals(result.sessionId, closed.sessionId)
        assertFalse(closed.poisoned)
        assertTrue(trace.events.indexOf(completed) < trace.events.indexOf(closed))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (started.command as MutableList<String>) += "mutated"
        }
    }

    @Test
    fun `trace limits bound captured output and close the session`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val result = withRuntimeTrace(
            trace,
            RuntimeTraceLimits(
                maxOutputLinesPerDispatch = 2,
                maxOutputCharactersPerDispatch = 100,
            ),
        ) {
            sh { "printf 'one\\ntwo\\nthree\\n'"() }
        }

        assertEquals(listOf("one", "two"), result.stdout)
        assertTrue(result.outputLimited)
        assertFalse(result.ok)
        // The shell may have exited cleanly before the reader enforces the
        // third-line limit. Output limiting is the authoritative failure bit.
        assertTrue(result.exitCode == null || result.exitCode == 0)
        val completed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single()
        assertTrue(completed.outputLimited)
        assertEquals(2, completed.stdoutLines)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchOutput>().size)
    }

    @Test
    fun `empty lines cannot exceed the trace line limit`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputLinesPerDispatch = 2)) {
            sh { "printf '\\n\\n\\n'"() }
        }

        assertEquals(listOf("", ""), result.stdout)
        assertTrue(result.outputLimited)
        assertFalse(result.ok)
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchOutput>().size)
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().single().stdoutLines)
    }

    @Test
    fun `empty lines do not consume the character budget`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5)) {
            sh { "printf 'ready\\n\\n'"() }
        }

        assertTrue(result.ok)
        assertEquals(listOf("ready", ""), result.stdout)
        assertEquals(listOf("ready", ""), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
    }

    @Test
    fun `fragmented completion markers do not count against the output limit`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val spec = RuntimeSpec("fragmented-shell", RuntimeSpecs.Bash.command) { script, marker ->
            val split = marker.length / 2
            buildString {
                appendLine(script)
                appendLine("printf '%s' '${marker.take(split)}'")
                appendLine("sleep 0.05")
                appendLine("printf '%s\\n' '${marker.drop(split)}:0'")
                appendLine("printf '%s\\n' '$marker:0' >&2")
            }
        }

        val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 5)) {
            runtime(spec) { "printf ready"() }
        }

        assertTrue(result.ok)
        assertEquals(listOf("ready"), result.stdout)
        assertTrue(result.stderr.isEmpty())
        assertEquals(listOf("ready"), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `large typed Python values are independent of trace log limits`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()

        withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 8)) {
            py {
                val result = evaluate<String>("'x' * 20000")
                assertEquals("x".repeat(20000), result.value)
                assertTrue(result.execution.ok)
                assertTrue(result.execution.stdout.isEmpty())
                assertTrue(result.execution.stderr.isEmpty())
                assertEquals(42, eval<Int>("6 * 7"))
            }
        }

        assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().all { !it.outputLimited })
    }

    @Test
    fun `large typed Node values preserve bounded logs`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()

        withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 8)) {
            node {
                val result = evaluate<String>("(process.stdout.write('out!'), process.stderr.write('err!'), 'x'.repeat(20000))")
                assertEquals("x".repeat(20000), result.value)
                assertTrue(result.execution.ok)
                assertEquals(listOf("out!"), result.execution.stdout)
                assertEquals(listOf("err!"), result.execution.stderr)
                assertEquals(42, eval<Int>("6 * 7"))
            }
        }

        val output = trace.events.filterIsInstance<RuntimeDispatchOutput>()
        assertEquals(listOf("out!"), output.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
        assertEquals(listOf("err!"), output.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().all { !it.outputLimited })
    }

    @Test
    fun `character limit still bounds output without a newline`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 8)) {
            sh { "printf 'x%.0s' {1..10000}; sleep 10"() }
        }

        assertEquals(listOf("x".repeat(8)), result.stdout)
        assertTrue(result.outputLimited)
        assertFalse(result.ok)
        assertEquals(listOf("x".repeat(8)), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `structured requests carry their expression identity`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()

        val answer = withRuntimeTrace(trace) {
            py { eval<Int>("6 * 7") }
        }

        assertEquals(42, answer)
        val dispatch = trace.events.filterIsInstance<RuntimeDispatchStarted>().single()
        assertEquals("6 * 7", dispatch.scriptPreview)
        assertEquals(sha256("6 * 7"), dispatch.scriptSha256)
        assertEquals("py", dispatch.runtime)
        assertEquals("eval", dispatch.operation)
    }

    @Test
    fun `explicit copy traces its source session without treating logs as values`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()

        val answer = withRuntimeTrace(trace) {
            py {
                hold("{'answer': 42}") { pythonValue ->
                    node {
                        copy(pythonValue) { copied ->
                            eval<Int>("value.answer", "value" to copied)
                        }
                    }
                }
            }
        }

        assertEquals(42, answer)
        val sessions = trace.events.filterIsInstance<RuntimeSessionStarted>().associateBy { it.runtime }
        val pythonSession = sessions.getValue("py").sessionId
        val nodeSession = sessions.getValue("node").sessionId
        val copiedStore = trace.events.filterIsInstance<RuntimeDispatchStarted>().single {
            it.sessionId == nodeSession && it.operation == "store"
        }
        assertEquals(listOf(pythonSession), copiedStore.inputSessionIds)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().isEmpty())
    }

    @Test
    fun `cancelled dispatch emits failure before poisoned session close`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        var processId: Long? = null

        assertFailsWith<TimeoutCancellationException> {
            withRuntimeTrace(trace) {
                sh {
                    processId = runtime.processId
                    withTimeout(100.milliseconds) { "sleep 10"() }
                }
            }
        }

        val failed = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
        val closed = trace.events.filterIsInstance<RuntimeSessionClosed>().single()
        assertTrue(failed.errorType.contains("TimeoutCancellationException"))
        assertTrue(closed.poisoned)
        assertTrue(trace.events.indexOf(failed) < trace.events.indexOf(closed))
        val pid = assertNotNull(processId)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `independent sessions receive distinct identities`(): Unit = runBlocking {
        val first = sh { "printf first"() }
        val second = sh { "printf second"() }

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(1, first.dispatchId)
        assertEquals(1, second.dispatchId)
        assertIs<Long>(first.elapsedMillis)
    }

    @Test
    fun `jsonl sink preserves event identity status and stream`() {
        val lines = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(lines::add)

        sink.onEvent(RuntimeDispatchOutput(
            sessionId = "session-1",
            runtime = "sh",
            atEpochMillis = 123,
            dispatchId = 7,
            sequence = 2,
            stream = RuntimeStream.Stderr,
            text = "failed but structured",
        ))

        val line = lines.single()
        assertTrue(line.startsWith(RUNTIME_TRACE_JSONL_PREFIX))
        val json = Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
        assertEquals("dispatch-output", json.getValue("event").jsonPrimitive.content)
        assertEquals("session-1", json.getValue("sessionId").jsonPrimitive.content)
        assertEquals(7, json.getValue("dispatchId").jsonPrimitive.content.toLong())
        assertEquals("stderr", json.getValue("stream").jsonPrimitive.content)
        assertEquals("failed but structured", json.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `command previews are immutable and strictly bounded`() {
        val command = boundedCommand(listOf("node", "-e", "x".repeat(1_000)), 12)

        assertTrue(command.sumOf(String::length) <= 12)
        assertTrue(command.last().endsWith("…"))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (command as MutableList<String>) += "mutated"
        }
    }

    @Test
    fun `trace sink failure cannot change runtime execution`() = runBlocking {
        val result = withRuntimeTrace(RuntimeTraceSink { throw IllegalStateException("observer failed") }) {
            sh { "printf ready"() }
        }

        assertTrue(result.ok)
        assertEquals("ready", result.stdoutText)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
