package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeReaderFailureTest {
    @Test
    fun `stdout reader failure stops the owned tree and finishes workers`(): Unit = runBlocking {
        assertReaderFailure(RuntimeStream.Stdout)
    }

    @Test
    fun `stderr reader failure stops the owned tree and finishes workers`(): Unit = runBlocking {
        assertReaderFailure(RuntimeStream.Stderr)
    }

    @Test
    fun `reader failure remains primary when closure telemetry fails`(): Unit = runBlocking {
        assertReaderFailure(RuntimeStream.Stdout, closureFailure = ClosureFailure())
    }

    @Test
    fun `truncated response cannot replace a reader failure with a decoding error`(): Unit = runBlocking {
        assertReaderFailure(RuntimeStream.Stdout, response = Response.Truncated)
    }

    @Test
    fun `complete response without a newline cannot turn a failed reader into completion`(): Unit = runBlocking {
        assertReaderFailure(RuntimeStream.Stdout, response = Response.Complete)
    }

    @Test
    fun `a failed reader preserves decoded text without finishing a split UTF8 character`(): Unit = runBlocking {
        assertReaderFailure(RuntimeStream.Stdout, response = Response.PartialUtf8)
    }

    @Test
    fun `buffered output observer failure cannot replace the native reader failure`(): Unit = runBlocking {
        for (failure in listOf(PrefixFailure(), PrefixFatal())) {
            assertReaderFailure(RuntimeStream.Stdout, response = Response.PartialUtf8,
                prefixFailure = failure, closureFailure = ClosureFailure())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun assertReaderFailure(
        stream: RuntimeStream,
        response: Response = Response.None,
        closureFailure: Throwable? = null,
        prefixFailure: Throwable? = null,
    ) {
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink {
            trace.onEvent(it)
            if (it is RuntimeSessionClosed && closureFailure != null) throw closureFailure
        }, RuntimeTraceLimits()))
        val payload = Base64.getEncoder().encodeToString(
            """{"version":1,"kind":"value","value":"transport secret"}""".toByteArray(Charsets.UTF_8))
        var submittedSource: String? = null
        fun source(marker: String): String = buildString {
            appendLine("/bin/sleep 30 & owned=${'$'}!")
            appendLine("printf 'owned:%s\\n' \"${'$'}owned\"")
            if (response == Response.PartialUtf8) {
                appendLine("printf 'pending\\360\\237'")
            } else if (response != Response.None) {
                val encoded = if (response == Response.Truncated) payload.dropLast(4) else payload
                appendLine("printf '%s' ${shLiteral("visible${marker}:0:$encoded")}")
            }
            appendLine("printf 'ready\\n' >&2")
            appendLine("wait \"${'$'}owned\"")
        }.also { submittedSource = it }
        val spec = RuntimeSpec("reader-failure", RuntimeSpecs.Bash.command,
            wrap = { _, marker -> source(marker) },
            requestWrapper = { _, marker -> source(marker) })
        val runtime = ProcessRuntime(owner, spec)
        var process: Process? = null
        var descendant: OwnedProcessCleanup.Child? = null
        var closedPipe = false
        try {
            runtime.start()
            val child = runtime.readField<Process>("process")
            process = child
            var sawOutput = false
            var sawReady = false
            val output = RuntimeOutputSink { line ->
                if (line.stream == RuntimeStream.Stdout && line.text.startsWith("owned:")) {
                    val handle = ProcessHandle.of(line.text.removePrefix("owned:").toLong()).orElseThrow()
                    assertEquals(child.pid(), handle.parent().orElseThrow().pid())
                    descendant = OwnedProcessCleanup.Child(handle, handle.info().startInstant().orElseThrow())
                    sawOutput = true
                }
                if (line.stream == RuntimeStream.Stdout && line.text == "visible") sawOutput = true
                if (line.stream == RuntimeStream.Stderr && line.text == "ready") sawReady = true
                if (line.text == "pending" && prefixFailure != null) throw prefixFailure
                if (sawOutput && sawReady && !closedPipe) {
                    assertTrue(child.isAlive, "The I/O fault must occur while the owned root is live")
                    if (response != Response.None) {
                        // The ready write follows the entire response fragment.
                        // Wait for its bytes to leave the actual pipe; the same
                        // pump queues that chunk before its later read failure.
                        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
                        while (child.inputStream.available() > 0) {
                            check(System.nanoTime() < deadline) { "Reader did not consume the response fragment" }
                            Thread.sleep(1)
                        }
                    }
                    // Close the actual JDK pipe after both streams have been
                    // observed. The reader detects the fault; this callback succeeds.
                    (if (stream == RuntimeStream.Stdout) child.inputStream else child.errorStream).close()
                    closedPipe = true
                }
            }

            val failure = assertFailsWith<IOException> {
                if (response == Response.None || response == Response.PartialUtf8)
                    runtime.exec("fixture", timeout = 5.seconds, output = output)
                else runtime.request(buildJsonObject { put("op", "eval") }, timeout = 5.seconds, output = output)
            }

            assertTrue(closedPipe, "The native reader fault was not reached")
            assertFalse(child.isAlive, "Reader failure left its acquired root running")
            assertFalse(checkNotNull(descendant).stillSameProcess(), "Reader failure left its observed descendant running")
            assertTrue(ownerJob.isActive)
            assertTrue(runtime.readField<List<Job>>("ioJobs").all { it.isCompleted })
            assertTrue(runtime.readField<Channel<*>>("events").isClosedForSend)
            assertTrue(runtime.readField<Channel<*>>("requests").isClosedForSend)
            // Coroutine stack recovery can retain the native exception as the
            // caller exception's cause. Every later failure must remain exposed.
            val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
            val suppressed = causes.flatMap { it.suppressed.toList() }
            assertEquals(listOfNotNull(prefixFailure, closureFailure).toSet(), suppressed.toSet())
            assertEquals(listOfNotNull(prefixFailure, closureFailure).size, suppressed.size)
            if (System.getProperty("kotlinx.coroutines.stacktrace.recovery") == "false") {
                assertEquals(1, causes.size, "Disabled stack recovery still copied the native exception")
            }
            assertFailsWith<IllegalStateException> { runtime.exec("late request") }
            val failed = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
            assertEquals("java.io.IOException", failed.errorType)
            assertEquals(if (response == Response.None) 1 else 2, failed.stdoutLines)
            assertEquals(1, failed.stderrLines)
            val published = trace.events.filterIsInstance<RuntimeDispatchOutput>()
            assertTrue(published.none { "transport secret" in it.text || payload.take(12) in it.text })
            if (response == Response.PartialUtf8) {
                assertEquals("pending", published.last { it.stream == RuntimeStream.Stdout }.text)
                assertTrue(published.none { '\uFFFD' in it.text })
            }
            assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
            assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
            assertTrue(trace.events.last() is RuntimeSessionClosed)
            runtime.closeSuspending()
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
            sh { assertEquals(listOf("healthy"), "printf 'healthy\\n'"().stdout) }
            println("@@kotlysh-reader-failure " + buildJsonObject {
                put("stream", stream.name)
                put("fragment", response.name)
                put("prefixFailure", prefixFailure?.javaClass?.simpleName ?: "none")
                put("closureFailure", closureFailure?.javaClass?.simpleName ?: "none")
                put("source", checkNotNull(submittedSource))
                put("root", child.pid())
                put("descendant", checkNotNull(descendant).handle.pid())
                put("rootStopped", !child.isAlive)
                put("descendantStopped", !checkNotNull(descendant).stillSameProcess())
                put("exceptionCopies", causes.size)
                put("suppressedFailures", suppressed.size)
                put("stackRecoveryProperty", System.getProperty("kotlinx.coroutines.stacktrace.recovery", "default"))
            })
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                try {
                    process?.let { OwnedProcessCleanup.finish(it, listOfNotNull(descendant), graceful = false) }
                } finally {
                    ownerJob.cancelAndJoin()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ProcessRuntime.readField(name: String): T =
        ProcessRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private enum class Response { None, Truncated, Complete, PartialUtf8 }
    private class ClosureFailure : Error("Closure observer failed")
    private class PrefixFailure(val boundary: Int = 1) : IllegalStateException("Buffered output observer failed")
    private class PrefixFatal : Error("Fatal buffered output observer failed")
}
