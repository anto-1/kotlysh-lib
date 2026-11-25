package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class ExecObservedDescendantTest {
    @Test
    fun `ordinary root exit terminates previously observed descendants and preserves streams`(): Unit = runBlocking {
        withTree(true) { command, pids, acknowledged, remember ->
            var observed: List<Owned> = emptyList()
            val result = exec(command, timeout = 5.seconds, output = { line ->
                if (line.stream == RuntimeStream.Stdout) {
                    observed = readOwned(pids)
                    remember(observed)
                    Files.createFile(acknowledged)
                }
            })
            assertEquals(7, result.exitCode)
            assertFalse(result.timedOut)
            assertEquals(listOf("ready"), result.stdout)
            assertEquals(listOf("warning"), result.stderr)
            assertStopped(observed)
        }
    }

    @Test
    fun `output failure after root exit still terminates observed descendants`(): Unit = runBlocking {
        withTree(true) { command, pids, acknowledged, remember ->
            val original = SinkFailure(1)
            var observed: List<Owned> = emptyList()
            val actual = runCatching {
                exec(command, timeout = 5.seconds, output = { line ->
                    if (line.stream == RuntimeStream.Stdout) {
                        val owned = readOwned(pids)
                        observed = owned
                        remember(owned)
                        Files.createFile(acknowledged)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (owned.first().running() && System.nanoTime() < deadline) Thread.sleep(5)
                        assertFalse(owned.first().running(), "Fixture root did not exit")
                        throw original
                    }
                })
            }.exceptionOrNull()
            assertSame(original, actual)
            assertStopped(observed)
        }
    }

    @Test
    fun `deadline stops a signal ignoring three level tree`(): Unit = runBlocking {
        withTree(false) { command, pids, _, remember ->
            var observed: List<Owned> = emptyList()
            val result = exec(command, timeout = 1.seconds, output = { line ->
                if (line.stream == RuntimeStream.Stdout) {
                    observed = readOwned(pids)
                    remember(observed)
                }
            })
            assertTrue(result.timedOut)
            assertEquals(listOf("ready"), result.stdout)
            // Bash can also report its forcibly terminated background job.
            assertEquals("warning", result.stderr.firstOrNull())
            assertStopped(observed)
        }
    }

    @Test
    fun `output limit stops an observed signal ignoring tree without a deadline`(): Unit = runBlocking {
        withTree(false) { command, pids, _, remember ->
            var observed: List<Owned> = emptyList()
            val result = withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1)) {
                exec(command, timeout = null, output = {
                    observed = readOwned(pids)
                    remember(observed)
                })
            }
            assertTrue(result.outputLimited)
            assertFalse(result.timedOut)
            assertFalse(result.ok)
            assertEquals(1, result.stdout.size + result.stderr.size)
            assertStopped(observed)
        }
    }

    private suspend fun withTree(
        exitOnAcknowledgement: Boolean,
        block: suspend (List<String>, Path, Path, (List<Owned>) -> Unit) -> Unit,
    ) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"), "Requires Linux process identity checks and Bash BASHPID")
        val directory = Files.createTempDirectory("kotlysh-exec-observed-")
        val pids = directory.resolve("pids")
        val acknowledged = directory.resolve("acknowledged")
        val leaf = "trap '' TERM; printf '%s\\n' \"\$BASHPID\" >> ${shLiteral(pids.toString())}; exec sleep 30"
        val branch = "trap '' TERM; printf '%s\\n' \"\$BASHPID\" >> ${shLiteral(pids.toString())}; " +
            "/bin/bash --noprofile --norc -c ${shLiteral(leaf)} & child=\$!; wait \"\$child\""
        val ending = if (exitOnAcknowledgement) {
            "while [[ ! -f ${shLiteral(acknowledged.toString())} ]]; do sleep 0.01; done; exit 7"
        } else "wait \"\$child\""
        val script = "trap '' TERM; printf '%s\\n' \"\$BASHPID\" > ${shLiteral(pids.toString())}; " +
            "/bin/bash --noprofile --norc -c ${shLiteral(branch)} & child=\$!; " +
            "while [[ \$(wc -l < ${shLiteral(pids.toString())}) -lt 3 ]]; do sleep 0.01; done; " +
            "printf 'ready\\n'; printf 'warning\\n' >&2; $ending"
        var remembered: List<Owned> = emptyList()
        try {
            block(listOf("/bin/bash", "--noprofile", "--norc", "-c", script), pids, acknowledged) { remembered = it }
        } finally {
            remembered.asReversed().filter(Owned::running).forEach { it.handle.destroyForcibly() }
            Files.deleteIfExists(acknowledged)
            Files.deleteIfExists(pids)
            Files.deleteIfExists(directory)
        }
    }

    private fun readOwned(pids: Path): List<Owned> {
        val owned = readRecorded(pids)
        assertEquals(3, owned.size)
        assertEquals(owned[0].handle.pid(), owned[1].handle.parent().orElseThrow().pid())
        assertEquals(owned[1].handle.pid(), owned[2].handle.parent().orElseThrow().pid())
        return owned
    }

    private fun readRecorded(pids: Path): List<Owned> = Files.readAllLines(pids).map { value ->
        val handle = ProcessHandle.of(value.toLong()).orElseThrow()
        Owned(handle, handle.info().startInstant().orElseThrow())
    }

    private fun assertStopped(owned: List<Owned>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (owned.any(Owned::running) && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(owned.none(Owned::running), "Observed descendants survived the completed exec call")
    }

    private data class Owned(val handle: ProcessHandle, val started: Instant) {
        fun running(): Boolean {
            if (!handle.isAlive || handle.info().startInstant().orElse(null) != started) return false
            val status = Path.of("/proc", handle.pid().toString(), "stat")
            return runCatching { Files.readString(status).substringAfterLast(") ").first() != 'Z' }.getOrDefault(false)
        }
    }

    private class SinkFailure(val marker: Int) : RuntimeException("output sink failed")
}
