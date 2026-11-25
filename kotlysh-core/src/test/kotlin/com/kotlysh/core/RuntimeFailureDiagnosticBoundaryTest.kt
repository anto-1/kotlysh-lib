package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CancellationException
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeFailureDiagnosticBoundaryTest {
    @Test
    fun `large callback diagnostics are bounded in trace events and wire without changing the exception`(): Unit = runBlocking {
        val message = "Callback failed: " + "x".repeat(8_100_000)
        for (stream in RuntimeStream.entries) {
            verifyFailure(DiagnosticFailure(message, 1), message.take(4_096), stream)
        }
    }

    @Test
    fun `cancellation and fatal diagnostics preserve unicode boundaries and owned cleanup`(): Unit = runBlocking {
        val crossing = "x".repeat(4_095) + "😀" + "tail"
        val fitting = "x".repeat(4_094) + "😀" + "tail"
        for (stream in RuntimeStream.entries) {
            verifyFailure(DiagnosticCancellation(crossing, 2), "x".repeat(4_095), stream)
            verifyFailure(DiagnosticError(fitting, 3), fitting.take(4_096), stream)
        }
    }

    private suspend fun verifyFailure(original: Throwable, expected: String, stream: RuntimeStream) {
        val message = checkNotNull(original.message)
        val trace = RuntimeTraceBuffer()
        val records = mutableListOf<String>()
        val jsonl = RuntimeJsonlTraceSink(records::add)
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        try {
            withRuntimeTrace(RuntimeTraceSink { event -> trace.onEvent(event); jsonl.onEvent(event) }) {
                sh {
                    root = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
                    val setup = "sleep 30 & printf 'owned:%s\\n' \"\$!\""(output = { line ->
                        if (line.text.startsWith("owned:")) {
                            child = capture(ProcessHandle.of(line.text.removePrefix("owned:").toLong()).orElseThrow())
                        }
                    })
                    assertTrue(setup.ok)
                    assertTrue(checkNotNull(child).handle.isAlive)
                    val script = if (stream == RuntimeStream.Stdout) "printf ready" else "printf ready >&2"

                    val actual = runCatching { script(timeout = 5.seconds, output = { throw original }) }.exceptionOrNull()

                    assertSame(original, actual)
                    assertSame(message, original.message)
                    assertFalse(checkNotNull(root).handle.isAlive, "Root must stop before the failure returns")
                    assertFalse(checkNotNull(child).handle.isAlive, "Owned child must stop before the failure returns")
                    assertFalse(runtime.isOpen)
                }
            }
            val failure = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
            assertTrue(checkNotNull(failure.message).length <= 4_096, "Diagnostic length: ${failure.message?.length}")
            assertEquals(expected, failure.message)
            assertEquals(original::class.qualifiedName, failure.errorType)
            assertEquals(expected, failure.toTraceJson()["message"]?.jsonPrimitive?.content)
            assertTrue(records.all { it.length < 30_000 }, "All records must fit far below the IDE trace-line cap")
            val closed = trace.events.filterIsInstance<RuntimeSessionClosed>().single()
            assertTrue(closed.poisoned)
            assertTrue(trace.events.indexOf(failure) < trace.events.indexOf(closed))
            assertEquals(closed, trace.events.last())
        } finally {
            child?.stopIfOwned()
            root?.stopIfOwned()
        }
    }

    private fun capture(handle: ProcessHandle): CapturedProcess =
        CapturedProcess(handle, handle.info().startInstant().orElseThrow())

    private data class CapturedProcess(val handle: ProcessHandle, val started: java.time.Instant) {
        fun stopIfOwned() {
            if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
        }
    }

    private class DiagnosticFailure(message: String, @Suppress("unused") val token: Int) : IllegalStateException(message)
    private class DiagnosticCancellation(message: String, @Suppress("unused") val token: Int) : CancellationException(message)
    private class DiagnosticError(message: String, @Suppress("unused") val token: Int) : AssertionError(message)
}
