package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeAdversarialTest {
    @Test
    fun `unexpected native read failures from a live root propagate`(): Unit = runBlocking {
        for (failAvailability in listOf(true, false)) {
            val failure = IOException("unexpected pipe failure")
            val input = object : InputStream() {
                override fun available(): Int = if (failAvailability) throw failure else 1
                override fun read(): Int = throw failure
            }
            val root = ProcessBuilder("/bin/sleep", "30").start()
            try {
                val actual = assertFailsWith<IOException> {
                    readOwnedProcessText(root, input) { error("Failed pipe emitted output") }
                }
                assertSame(failure, actual)
            } finally {
                root.destroyForcibly()
                root.waitFor()
                input.close()
            }
        }
    }

    @Test
    fun `intentional native closure flushes a partial UTF8 character`(): Unit = runBlocking {
        for (failAvailability in listOf(true, false)) {
            var bytesRead = 0
            val input = object : InputStream() {
                override fun available(): Int {
                    if (failAvailability && bytesRead > 0) throw IOException("closed during termination")
                    return 1
                }
                override fun read(): Int {
                    if (bytesRead++ == 0) return 0xe2
                    throw IOException("closed during termination")
                }
            }
            val root = ProcessBuilder("/bin/sleep", "30").start()
            val actual = StringBuilder()
            try {
                readOwnedProcessText(root, input, isTerminating = { true }) { actual.append(it) }
                assertEquals("\uFFFD", actual.toString())
            } finally {
                root.destroyForcibly()
                root.waitFor()
                input.close()
            }
        }
    }

    @Test
    fun `consumer IOException is never treated as native EOF during termination`(): Unit = runBlocking {
        val root = ProcessBuilder("/bin/true").start()
        root.waitFor()
        val failure = IOException("consumer failed")

        val actual = assertFailsWith<IOException> {
            readOwnedProcessText(root, "ready".byteInputStream(), isTerminating = { true }) { throw failure }
        }

        assertSame(failure, actual)
    }

    @Test
    fun `exited interpreter does not wait for pipes inherited by its former child`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kotlysh-owned-pipe-")
        val acknowledged = directory.resolve("acknowledged")
        var child: OwnedProcessCleanup.Child? = null
        try {
            sh {
                val rootPid = checkNotNull(runtime.processId)
                val started = System.nanoTime()
                val result = (
                    "sleep 3 & printf '%s\\n' \"\$!\"; " +
                        "while [ ! -e ${shLiteral(acknowledged.toString())} ]; do sleep 0.01; done; exit 7"
                    )(
                    timeout = 300.milliseconds,
                    idleTimeout = 2.seconds,
                    output = { line ->
                        if (line.stream == RuntimeStream.Stdout) {
                            val handle = ProcessHandle.of(line.text.toLong()).orElseThrow()
                            assertEquals(rootPid, handle.parent().orElseThrow().pid())
                            child = OwnedProcessCleanup.Child(handle, handle.info().startInstant().orElseThrow())
                            Files.writeString(acknowledged, "ready")
                        }
                    },
                )

                assertTrue(result.processExited)
                assertEquals(7, result.exitCode)
                assertFalse(result.timedOut)
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "Orphan pipe delayed dispatch completion")
            }
        } finally {
            // Only the identity proven under this fixture's live interpreter is cleaned.
            child?.let { owned ->
                if (owned.stillSameProcess()) owned.handle.destroyForcibly()
                owned.handle.onExit().get(5, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(acknowledged)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `owned output decoder preserves split UTF8 CRLF and replacement semantics`(): Unit = runBlocking {
        val bytes = "é😀漢\r\nline".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xff.toByte(), 0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 0xf0.toByte(), 0x9f.toByte())
        val expected = bytes.inputStream().reader(Charsets.UTF_8).readText()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= available(), "Reader requested bytes that were not available")
                return super.read(target, offset, minOf(length, 1))
            }
        }
        val root = ProcessBuilder("/bin/true").start()
        root.waitFor()
        val actual = StringBuilder()

        readOwnedProcessText(root, input) { actual.append(it) }

        assertEquals(expected, actual.toString())
        assertTrue(actual.contains("\r\n"))
        assertTrue(actual.contains('\uFFFD'))
    }

    @Test
    fun `owned output reader excludes continuously arriving bytes after root exit`(): Unit = runBlocking {
        val input = object : InputStream() {
            override fun available(): Int = 1
            override fun read(): Int = 'x'.code
        }
        val root = ProcessBuilder("/bin/true").start()
        root.waitFor()
        val actual = StringBuilder()

        withTimeout(1.seconds) {
            readOwnedProcessText(root, input) { actual.append(it) }
        }

        assertEquals("x", actual.toString())
    }

    @Test
    fun `owned output reader is cancellable while a live process is silent`(): Unit = runBlocking {
        var closed = false
        val input = object : InputStream() {
            override fun available(): Int = 0
            override fun read(): Int = error("An unavailable pipe must not be read")
            override fun close() { closed = true }
        }
        val root = ProcessBuilder("/bin/sleep", "30").start()
        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50.milliseconds) {
                    readOwnedProcessText(root, input) { error("Silent fixture emitted output") }
                }
            }
            assertFalse(closed, "The caller must retain live-root pipes until process cleanup")
        } finally {
            root.destroyForcibly()
            root.waitFor()
            input.close()
        }
        assertTrue(closed)
    }

    @Test
    fun `output caps never split supplementary characters in lines or partial output`(): Unit = runBlocking {
        for (newline in listOf("\\n", "")) {
            val trace = RuntimeTraceBuffer()
            val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 2)) {
                sh { "printf 'A😀B$newline'"() }
            }

            assertEquals(listOf("A"), result.stdout)
            assertTrue(result.outputLimited)
            assertFalse(result.ok)
            assertEquals(listOf("A"), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        }

        val exact = withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 3)) {
            sh { "printf 'A😀'"() }
        }
        assertEquals(listOf("A😀"), exact.stdout)
        assertTrue(exact.ok)

        val noCompleteCharacter = withRuntimeTrace(
            RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 1),
        ) {
            sh { "printf '😀B'; sleep 30"() }
        }
        assertTrue(noCompleteCharacter.outputLimited)
        assertTrue(noCompleteCharacter.stdout.isEmpty())
    }

    @Test
    fun `source previews never end with half a supplementary character`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()

        withRuntimeTrace(trace, RuntimeTraceLimits(maxScriptPreviewCharacters = 3)) {
            sh { "# 😀\nprintf ready"() }
        }

        assertEquals("# ", trace.events.filterIsInstance<RuntimeDispatchStarted>().single().scriptPreview)
    }

    @Test
    fun `a deadline preserves logs before an unfinished response without leaking its payload`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val payload = payload("""{"version":1,"kind":"value","value":"transport secret"}""")
        val spec = framedShell { marker ->
            "printf '%s' 'visible${marker}:0:$payload'; printf 'ready\\n' >&2; sleep 30\n"
        }

        val result = withRuntimeTrace(trace) {
            runtime(spec) {
                runtime.request(request(), timeout = 300.milliseconds, idleTimeout = 2.seconds)
            }
        }

        assertTrue(result.timedOut)
        assertFalse(result.ok)
        assertEquals(listOf("visible"), result.stdout)
        assertEquals(listOf("ready"), result.stderr)
        assertEquals(null, result.response)
        assertEquals(
            listOf("ready", "visible"),
            trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text },
        )
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `EOF completes valid response frames even without a final newline`(): Unit = runBlocking {
        val payload = payload("""{"version":1,"kind":"value","value":"transport secret"}""")
        val spec = framedShell { marker ->
            "printf '%s' 'visible${marker}:0:$payload'; printf '%s' 'diagnostic${marker}:0' >&2; exit 0\n"
        }

        val result = runtime(spec) { runtime.request(request()) }

        assertTrue(result.ok)
        assertFalse(result.processExited)
        assertEquals(listOf("visible"), result.stdout)
        assertEquals(listOf("diagnostic"), result.stderr)
        assertEquals("transport secret", result.expectResponse("value")["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `truncated response at EOF fails without becoming captured output`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val payload = payload("""{"version":1,"kind":"value","value":"transport secret"""")
        val spec = framedShell { marker ->
            "printf '%s' 'visible${marker}:0:$payload'; printf '%s\\n' '${marker}:0' >&2; exit 0\n"
        }

        assertFailsWith<SerializationException> {
            withRuntimeTrace(trace) {
                runtime(spec) { runtime.request(request()) }
            }
        }

        assertEquals(listOf("visible"), trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `marker shaped user text with an invalid status survives deadline flushing`(): Unit = runBlocking {
        var expected = ""
        val spec = framedShell { marker ->
            expected = "visible${marker}:not-a-status"
            "printf '%s' '$expected'; sleep 30\n"
        }

        val result = runtime(spec) { "ignored"(timeout = 300.milliseconds, idleTimeout = 2.seconds) }

        assertTrue(result.timedOut)
        assertEquals(listOf(expected), result.stdout)
    }

    @Test
    fun `invalid marker shaped text consumes log budget before its newline`(): Unit = runBlocking {
        val spec = framedShell { marker ->
            "printf '%s' '${marker}:not-a-status'; sleep 30\n"
        }

        val result = withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 8)) {
            runtime(spec) { "ignored"(timeout = 2.seconds) }
        }

        assertTrue(result.outputLimited)
        assertFalse(result.timedOut)
        assertEquals(listOf("__KOTLYS"), result.stdout)
    }

    @Test
    fun `invalid marker shaped text before the actual frame remains user output`(): Unit = runBlocking {
        var expected = ""
        val spec = framedShell { marker ->
            expected = "${marker}:not-a-status"
            "printf '%s' '$expected'; printf '%s\\n' '${marker}:0'; printf '%s\\n' '${marker}:0' >&2\n"
        }

        val result = runtime(spec) { "ignored"(timeout = 2.seconds) }

        assertTrue(result.ok)
        assertEquals(listOf(expected), result.stdout)
    }

    @Test
    fun `closing during process acquisition cannot leave a late owned interpreter`(): Unit = runBlocking {
        val commandRead = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        val command = object : AbstractList<String>() {
            private val actual = listOf("/bin/sleep", "30")
            override val size: Int get() = actual.size

            override fun get(index: Int): String {
                if (blockOnce.compareAndSet(true, false)) {
                    commandRead.countDown()
                    check(releaseCommand.await(5, TimeUnit.SECONDS)) { "Process acquisition was not released" }
                }
                return actual[index]
            }
        }
        val trace = RuntimeTraceBuffer()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(
            ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()) +
                CoroutineExceptionHandler { _, error -> failures += error },
        )
        val session = ProcessRuntime(owner, RuntimeSpec("slow-start", command) { script, _ -> script })

        try {
            val starting = async(Dispatchers.IO) { session.start() }
            assertTrue(withContext(Dispatchers.IO) { commandRead.await(5, TimeUnit.SECONDS) })
            val closed = CompletableDeferred<Unit>()
            val closing = async(Dispatchers.IO) {
                session.closeSuspending()
                closed.complete(Unit)
            }
            // Give a close that misses the in-flight acquisition a chance to finish.
            withTimeoutOrNull(100.milliseconds) { closed.await() }
            releaseCommand.countDown()
            withTimeout(5.seconds) {
                starting.await()
                closing.await()
            }

            val pid = checkNotNull(session.processId)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertEquals(2, trace.events.size)
            assertIs<RuntimeSessionStarted>(trace.events[0])
            assertIs<RuntimeSessionClosed>(trace.events[1])
            assertTrue(failures.isEmpty(), failures.toString())
        } finally {
            releaseCommand.countDown()
            // Also clean the exact owned handle if this regression is run on broken code.
            session.processId?.let { pid ->
                ProcessHandle.of(pid).ifPresent { handle ->
                    if (handle.isAlive) handle.destroyForcibly()
                    handle.onExit().get(5, TimeUnit.SECONDS)
                }
            }
            session.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    private fun request() = buildJsonObject { put("op", "eval") }

    private fun payload(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun framedShell(frame: (String) -> String): RuntimeSpec = RuntimeSpec(
        "adversarial-shell",
        RuntimeSpecs.Bash.command,
        wrap = { _, marker -> frame(marker) },
        requestWrapper = { _, marker -> frame(marker) },
    )
}
