package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CancellationException
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeOutputTraceBoundaryTest {
    @Test
    fun `ordinary consumer failure retains its triggering line in every native stream`(): Unit = runBlocking {
        eachStream { assertConsumerFailure(ConsumerFailure(1)) }
    }

    @Test
    fun `fatal consumer failure retains its triggering line in every native stream`(): Unit = runBlocking {
        eachStream { assertConsumerFailure(ObserverError(1)) }
    }

    @Test
    fun `consumer cancellation retains its triggering line in every native stream`(): Unit = runBlocking {
        eachStream { assertConsumerFailure(ConsumerCancellation(1)) }
    }

    @Test
    fun `a failing consumer only publishes the prefix accepted by the output budget`(): Unit = runBlocking {
        eachStream { assertConsumerFailure(ConsumerFailure(1), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 3), "rea") }
    }

    @Test
    fun `consumer closure retains accepted output before terminal facts`(): Unit = runBlocking {
        assertConsumerFailure(ConsumerFailure(1), closeInConsumer = true)
    }

    @Test
    fun `a secondary fatal output trace observer cannot replace the consumer failure`(): Unit = runBlocking {
        val original = ConsumerFailure(1)
        val telemetry = ObserverError(2)
        assertConsumerFailure(original, traceFailure = telemetry)
        assertEquals(listOf(telemetry), original.suppressed.toList())
    }

    @Test
    fun `repeating the same consumer and trace error never self suppresses`(): Unit = runBlocking {
        val failure = ObserverError(1)
        assertConsumerFailure(failure, traceFailure = failure)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun `a fatal output trace observer without a consumer failure remains observable`(): Unit = runBlocking {
        val error = ObserverError(1)
        val trace = RuntimeTraceBuffer()
        var callbacks = 0
        withRuntimeTrace(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeDispatchOutput) throw error
        }) {
            sh {
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val actual = runCatching { script(RuntimeSpecs.Bash, RuntimeStream.Stdout)(output = { callbacks++ }) }.exceptionOrNull()
                assertSame(error, actual)
                assertEquals(1, callbacks)
                assertFalse(process.isAlive)
                assertFalse(runtime.isOpen)
            }
        }
        assertEquals(listOf("ready"), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
    }

    @Test
    fun `untraced consumer failures still skip hostile diagnostic getters`(): Unit = runBlocking {
        val failure = object : IllegalStateException() {
            override val message: String get() = error("Diagnostic must not be read")
        }
        sh {
            val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
            val actual = runCatching { script(RuntimeSpecs.Bash, RuntimeStream.Stdout)(output = { throw failure }) }.exceptionOrNull()
            assertSame(failure, actual)
            assertFalse(process.isAlive)
        }
    }

    private suspend fun assertConsumerFailure(
        original: Throwable,
        limits: RuntimeTraceLimits = RuntimeTraceLimits(),
        accepted: String = "ready",
        spec: RuntimeSpec = RuntimeSpecs.Bash,
        stream: RuntimeStream = RuntimeStream.Stdout,
        closeInConsumer: Boolean = false,
        traceFailure: Throwable? = null,
    ) {
        val trace = RuntimeTraceBuffer()
        var callbacks = 0
        withRuntimeTrace(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeDispatchOutput && traceFailure != null) throw traceFailure
        }, limits) {
            runtime(spec) {
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val actual = runCatching {
                    script(spec, stream)(timeout = 5.seconds, output = { line ->
                        callbacks++
                        assertEquals(stream, line.stream, "${spec.name} emitted: ${line.text}")
                        assertEquals(accepted, line.text)
                        if (closeInConsumer) runtime.close()
                        throw original
                    })
                }.exceptionOrNull()
                assertSame(original, actual)
                assertEquals(1, callbacks)
                assertFalse(runtime.isOpen)
                assertFalse(process.isAlive, "Native cleanup must finish before the caller catches the failure")
                assertFailsWith<IllegalStateException> { "next"() }
            }
        }
        val events = trace.events
        val output = events.filterIsInstance<RuntimeDispatchOutput>().single()
        assertEquals(stream, output.stream)
        assertEquals(accepted, output.text)
        assertEquals(0, output.sequence)
        assertEquals(1, events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertEquals(1, events.filterIsInstance<RuntimeSessionClosed>().size)
        assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertTrue(events.indexOf(output) < events.indexOf(events.filterIsInstance<RuntimeDispatchFailed>().single()))
        assertTrue(events.last() is RuntimeSessionClosed)
    }

    private suspend fun eachStream(block: suspend StreamCase.() -> Unit) {
        for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            for (stream in RuntimeStream.entries) StreamCase(spec, stream).block()
        }
    }

    private inner class StreamCase(val spec: RuntimeSpec, val stream: RuntimeStream) {
        suspend fun assertConsumerFailure(original: Throwable, limits: RuntimeTraceLimits = RuntimeTraceLimits(), accepted: String = "ready") =
            this@RuntimeOutputTraceBoundaryTest.assertConsumerFailure(original, limits, accepted, spec, stream)
    }

    private fun script(spec: RuntimeSpec, stream: RuntimeStream): String = when (spec.name) {
        "sh" -> "printf 'ready\\n'${if (stream == RuntimeStream.Stderr) " >&2" else ""}; sleep 30"
        "py" -> "import sys, time; print('ready', file=sys.${stream.name.lowercase()}, flush=True); time.sleep(30)"
        "node" -> "process.${stream.name.lowercase()}.write('ready\\n'); new Promise(resolve => setTimeout(resolve, 30000));"
        else -> error("Unexpected native runtime")
    }

    // Extra fields prevent coroutine stack recovery from copying these failures.
    private class ConsumerFailure(val id: Int) : IllegalStateException("Consumer failed")
    private class ConsumerCancellation(val id: Int) : CancellationException("Consumer cancelled")
    private class ObserverError(val id: Int) : Error("Output telemetry failed")
}
