package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(15)
class ExecConsumerCancellationTest {
    @Test
    fun `stdout consumer cancellation stops exec without a configured deadline`(): Unit = runBlocking {
        assertConsumerCancellation(RuntimeStream.Stdout)
    }

    @Test
    fun `stderr consumer cancellation stops exec without a configured deadline`(): Unit = runBlocking {
        assertConsumerCancellation(RuntimeStream.Stderr)
    }

    private suspend fun assertConsumerCancellation(stream: RuntimeStream) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"), "Requires Linux process identity checks")
        val directory = Files.createTempDirectory("kotlysh-exec-sink-cancel-")
        val pids = directory.resolve("pids")
        val original = CancellationException("controlled $stream consumer cancellation")
        var owned: List<Owned> = emptyList()
        val redirect = if (stream == RuntimeStream.Stderr) " >&2" else ""
        val script = "sleep 30 & child=\$!; printf '%s\\n%s\\n' \"\$\$\" \"\$child\" > ${shLiteral(pids.toString())}; " +
            "printf 'ready\\n'$redirect; wait \"\$child\""
        try {
            val failure = runCatching {
                // This guard exposes a reader-local cancellation that otherwise
                // leaves exec waiting forever for its live root process.
                withTimeout(3.seconds) {
                    exec(listOf("/bin/bash", "--noprofile", "--norc", "-c", script), timeout = null, output = { line ->
                        if (line.stream == stream) {
                            assertEquals("ready", line.text)
                            owned = Files.readAllLines(pids).map { value ->
                                val handle = ProcessHandle.of(value.toLong()).orElseThrow()
                                Owned(handle, handle.info().startInstant().orElseThrow())
                            }
                            assertEquals(2, owned.size)
                            assertEquals(owned.first().handle.pid(), owned.last().handle.parent().orElseThrow().pid())
                            throw original
                        }
                    })
                }
            }.exceptionOrNull()
            val recovered = if (failure?.javaClass == original.javaClass && failure.cause === original) failure.cause else failure
            assertSame(original, recovered)
            assertTrue(owned.none(Owned::running), "Consumer cancellation left its owned tree running")
            val subsequent = exec(listOf("/bin/bash", "-c", "printf 'recovered\\n'; printf 'diagnostic\\n' >&2"))
            assertTrue(subsequent.ok)
            assertEquals(listOf("recovered"), subsequent.stdout)
            assertEquals(listOf("diagnostic"), subsequent.stderr)
        } finally {
            owned.asReversed().filter(Owned::running).forEach { it.handle.destroyForcibly() }
            Files.deleteIfExists(pids)
            Files.deleteIfExists(directory)
        }
    }

    private data class Owned(val handle: ProcessHandle, val started: Instant) {
        fun running(): Boolean {
            if (!handle.isAlive || handle.info().startInstant().orElse(null) != started) return false
            return runCatching {
                Files.readString(Path.of("/proc", handle.pid().toString(), "stat")).substringAfterLast(") ").first() != 'Z'
            }.getOrDefault(false)
        }
    }
}
