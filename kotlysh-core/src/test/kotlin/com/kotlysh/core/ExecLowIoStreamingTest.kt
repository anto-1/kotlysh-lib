package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class ExecLowIoStreamingTest {
    @Test
    fun `stdout callback can release a live process with one ordinary IO permit`() = fixture("stdout")

    @Test
    fun `stderr callback can release a live process with one ordinary IO permit`() = fixture("stderr")

    @Test
    fun `stdout callback cancellation stops its tree with one ordinary IO permit`() = fixture("cancel-stdout")

    @Test
    fun `stderr callback cancellation stops its tree with one ordinary IO permit`() = fixture("cancel-stderr")

    private fun fixture(mode: String) {
        val directory = Files.createTempDirectory("kotlysh-exec-low-io-")
        val log = directory.resolve("fixture.log")
        val classpath = listOf(
            ExecLowIoStreamingFixture::class.java, ProcessRuntime::class.java,
            Unit::class.java, CoroutineScope::class.java,
            kotlinx.serialization.json.JsonObject::class.java,
            kotlinx.serialization.KSerializer::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val fork = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-ea", "-Dkotlinx.coroutines.io.parallelism=1", "-cp", classpath,
            ExecLowIoStreamingFixture::class.java.name, mode, directory.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val owned = mutableListOf<Pair<ProcessHandle, Instant>>()
        try {
            val identities = directory.resolve("pids")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(identities) && fork.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            if (Files.exists(identities)) {
                Files.readAllLines(identities).mapNotNull(String::toLongOrNull).forEach { pid ->
                    ProcessHandle.of(pid).orElse(null)?.let { handle ->
                        handle.info().startInstant().orElse(null)?.let { owned += handle to it }
                    }
                }
            }
            assertTrue(fork.waitFor(8, TimeUnit.SECONDS), "The $mode fixture stalled:\n${Files.readString(log)}")
            assertEquals(0, fork.exitValue(), "The $mode fixture failed:\n${Files.readString(log)}")
            assertTrue(Files.readString(log).contains("completed:$mode owned=2"))
            assertTrue(owned.none { it.first.isAlive }, "Fixture returned with an owned process alive")
        } finally {
            try {
                val descendants = if (fork.isAlive) OwnedProcessCleanup.captureDescendants(fork) else emptyList()
                OwnedProcessCleanup.finish(fork, descendants, graceful = false)
            } finally {
                owned.asReversed().forEach { (handle, started) ->
                    if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
                }
                listOf(fork.outputStream, fork.inputStream, fork.errorStream).forEach { runCatching { it.close() } }
                directory.toFile().deleteRecursively()
            }
        }
    }
}

/** Isolate the global IO limit and any negative-control native wait from the test worker. */
internal object ExecLowIoStreamingFixture {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val mode = args[0]
        val directory = Path.of(args[1])
        val identities = directory.resolve("pids")
        val release = directory.resolve("release")
        val stream = if (mode.endsWith("stderr")) RuntimeStream.Stderr else RuntimeStream.Stdout
        val redirect = if (stream == RuntimeStream.Stderr) " >&2" else ""
        val cancellation = CancellationException("controlled $stream streaming cancellation")
        val owned = mutableListOf<Pair<ProcessHandle, Instant>>()
        var callbacks = 0
        val script = "sleep 30 & child=\$!; printf '%s\\n%s\\n' \"\$\$\" \"\$child\" > ${shLiteral(identities.toString())}; " +
            "printf 'ready\\n'$redirect; while [ ! -e ${shLiteral(release.toString())} ]; do sleep 0.01; done; " +
            "kill \"\$child\"; wait \"\$child\" || true; printf 'finished\\n'; printf 'diagnostic\\n' >&2"
        try {
            val outcome = runCatching {
                withTimeout(3.seconds) {
                    exec(listOf("/bin/bash", "--noprofile", "--norc", "-c", script), timeout = null, output = { line ->
                        if (line.stream == stream && line.text == "ready") {
                            callbacks++
                            check(callbacks == 1)
                            Files.readAllLines(identities).forEach { value ->
                                val handle = ProcessHandle.of(value.toLong()).orElseThrow()
                                owned += handle to handle.info().startInstant().orElseThrow()
                            }
                            check(owned.size == 2 && owned.all { it.first.isAlive })
                            check(owned[1].first.parent().orElseThrow().pid() == owned[0].first.pid())
                            if (mode.startsWith("cancel-")) throw cancellation
                            Files.createFile(release)
                        }
                    })
                }
            }
            check(callbacks == 1) { "Streaming callback did not run while the root was live: $outcome" }
            check(owned.none { it.first.isAlive }) { "Consumer outcome left an owned process alive" }
            if (mode.startsWith("cancel-")) {
                val actual = outcome.exceptionOrNull()
                val recovered = if (actual?.javaClass == cancellation.javaClass && actual.cause === cancellation) actual.cause else actual
                check(recovered === cancellation) { "Consumer cancellation was replaced: $actual" }
            } else {
                val result = outcome.getOrThrow()
                check(result.ok && !result.timedOut)
                check(result.stdout == if (stream == RuntimeStream.Stdout) listOf("ready", "finished") else listOf("finished"))
                check(result.stderr == if (stream == RuntimeStream.Stderr) listOf("ready", "diagnostic") else listOf("diagnostic"))
            }
            val reused = exec(listOf("/bin/bash", "-c", "printf reusable; printf 'reuse diagnostic' >&2"))
            check(reused.ok && reused.stdout == listOf("reusable") && reused.stderr == listOf("reuse diagnostic"))
            println("completed:$mode owned=2")
        } finally {
            owned.asReversed().forEach { (handle, started) ->
                if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
            }
        }
    }
}
