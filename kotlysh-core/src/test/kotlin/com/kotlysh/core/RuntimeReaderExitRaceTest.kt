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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeReaderExitRaceTest {
    @Test
    fun `queued stdout failure survives a later normal root exit`(): Unit = runBlocking {
        assertRace(RuntimeStream.Stdout, Fragment.None)
    }

    @Test
    fun `queued stderr failure survives a later normal root exit`(): Unit = runBlocking {
        assertRace(RuntimeStream.Stderr, Fragment.None)
    }

    @Test
    fun `queued read failure cannot finalize a truncated response after root exit`(): Unit = runBlocking {
        assertRace(RuntimeStream.Stdout, Fragment.Truncated)
    }

    @Test
    fun `queued read failure cannot manufacture successful completion after root exit`(): Unit = runBlocking {
        assertRace(RuntimeStream.Stdout, Fragment.Complete)
    }

    @Test
    fun `genuine EOF still completes a response without final newlines`(): Unit = runBlocking {
        assertRace(RuntimeStream.Stdout, Fragment.Complete, fault = false)
    }

    @Test
    fun `a closed native pipe after root exit remains EOF at the reader boundary`(): Unit = runBlocking {
        val root = ProcessBuilder("/bin/true").start()
        try {
            assertTrue(root.waitFor(5, TimeUnit.SECONDS))
            root.inputStream.close()
            val captured = StringBuilder()
            readOwnedProcessText(root, root.inputStream) { captured.append(it) }
            assertEquals("", captured.toString())
        } finally {
            try {
                OwnedProcessCleanup.finish(root, emptyList(), graceful = false)
            } finally {
                listOf(root.outputStream, root.inputStream, root.errorStream).forEach { it.close() }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun assertRace(stream: RuntimeStream, fragment: Fragment, fault: Boolean = true) {
        val directory = Files.createTempDirectory("kotlysh-reader-exit-")
        val acknowledged = directory.resolve("acknowledged")
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val payload = Base64.getEncoder().encodeToString(
            """{"version":1,"kind":"value","value":"transport secret"}""".toByteArray(Charsets.UTF_8))
        var submitted: String? = null
        fun source(marker: String): String = buildString {
            appendLine("printf 'before-out\\n'")
            if (fragment != Fragment.None) {
                val encoded = if (fragment == Fragment.Truncated) payload.dropLast(4) else payload
                appendLine("printf '%s' ${shLiteral("visible${marker}:0:$encoded")}")
            }
            appendLine("printf 'ready\\n' >&2")
            // Only builtins: the root has no descendants that could be orphaned
            // by this controlled exit while dispatch remains in its callback.
            appendLine("while [ ! -e ${shLiteral(acknowledged.toString())} ]; do :; done")
            if (fragment != Fragment.None) appendLine("printf '%s' ${shLiteral("$marker:0")} >&2")
            appendLine("exit 0")
        }.also { submitted = it }
        val runtime = ProcessRuntime(owner, RuntimeSpec("reader-exit-race", RuntimeSpecs.Bash.command,
            wrap = { _, marker -> source(marker) }, requestWrapper = { _, marker -> source(marker) }))
        var process: Process? = null
        var orderedExit = false
        var queuedWhileLive = false
        try {
            runtime.start()
            val child = runtime.readField<Process>("process")
            process = child
            var sawOutput = false
            var sawReady = false
            val output = RuntimeOutputSink { line ->
                if (line.stream == RuntimeStream.Stdout && line.text == "before-out") sawOutput = true
                if (line.stream == RuntimeStream.Stderr && line.text == "ready") sawReady = true
                if (sawOutput && sawReady && !orderedExit) {
                    assertTrue(child.isAlive)
                    assertEquals(0L, child.descendants().use { it.count() })
                    val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
                    if (fragment != Fragment.None) {
                        // The ready write follows all stdout fragment bytes.
                        while (child.inputStream.available() > 0) {
                            check(System.nanoTime() < deadline) { "Response fragment was not read" }
                            Thread.sleep(1)
                        }
                    }
                    if (fault) {
                        (if (stream == RuntimeStream.Stdout) child.inputStream else child.errorStream).close()
                        // Start installs stdout/stderr pumps in stream order.
                        // Each pump completes only after queuing its End event.
                        val reader = runtime.readField<List<Job>>("ioJobs")[stream.ordinal]
                        while (!reader.isCompleted) {
                            check(System.nanoTime() < deadline) { "The failed reader did not finish" }
                            Thread.sleep(1)
                        }
                        assertFalse(reader.isCancelled)
                        assertTrue(child.isAlive, "Root exit preceded the queued read failure")
                        queuedWhileLive = true
                    }
                    Files.writeString(acknowledged, "exit")
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Native root did not acknowledge exit")
                    assertEquals(0, child.exitValue())
                    orderedExit = true
                }
            }
            suspend fun dispatch(): RuntimeResult = if (fragment == Fragment.None)
                runtime.exec("fixture", timeout = 10.seconds, output = output)
            else runtime.request(buildJsonObject { put("op", "eval") }, timeout = 10.seconds, output = output)

            if (fault) {
                assertFailsWith<IOException> { dispatch() }
                assertTrue(queuedWhileLive)
                val failed = trace.events.filterIsInstance<RuntimeDispatchFailed>().single()
                assertEquals("java.io.IOException", failed.errorType)
                assertTrue(failed.message?.contains("transport secret") != true)
                assertEquals(if (fragment == Fragment.None) 1 else 2, failed.stdoutLines)
                assertEquals(1, failed.stderrLines)
                assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
            } else {
                val result = dispatch()
                assertTrue(result.ok)
                assertEquals(listOf("before-out", "visible"), result.stdout)
                assertEquals(listOf("ready"), result.stderr)
                assertEquals("transport secret", result.expectResponse("value")["value"]?.jsonPrimitive?.content)
                assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
                assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
            }
            assertTrue(orderedExit)
            assertFalse(child.isAlive)
            runtime.closeSuspending()
            assertTrue(ownerJob.isActive)
            assertTrue(runtime.readField<List<Job>>("ioJobs").all { it.isCompleted })
            assertTrue(runtime.readField<Channel<*>>("events").isClosedForSend)
            assertTrue(runtime.readField<Channel<*>>("requests").isClosedForSend)
            assertEquals(fault, trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
            assertTrue(trace.events.last() is RuntimeSessionClosed)
            assertFailsWith<IllegalStateException> { runtime.exec("late request") }
            assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().none {
                "transport secret" in it.text || payload.take(12) in it.text
            })
            sh { assertEquals(listOf("healthy"), "printf 'healthy\\n'"().stdout) }
            println("@@kotlysh-reader-exit " + buildJsonObject {
                put("stream", stream.name)
                put("fragment", fragment.name)
                put("fault", fault)
                put("queuedWhileLive", queuedWhileLive)
                put("rootExitedBeforeDelivery", orderedExit)
                put("exitCode", child.exitValue())
                put("source", checkNotNull(submitted))
            })
        } finally {
            // Also stop the exact acquired root if a failed probe never reached
            // its acknowledgement; there are no native descendant fixtures.
            try {
                runtime.closeSuspending()
            } finally {
                try {
                    process?.let { OwnedProcessCleanup.finish(it, emptyList(), graceful = false) }
                } finally {
                    ownerJob.cancelAndJoin()
                    Files.deleteIfExists(acknowledged)
                    Files.deleteIfExists(directory)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ProcessRuntime.readField(name: String): T =
        ProcessRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private enum class Fragment { None, Truncated, Complete }
}
