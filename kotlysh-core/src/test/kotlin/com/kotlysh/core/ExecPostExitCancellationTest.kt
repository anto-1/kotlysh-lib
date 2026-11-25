package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class ExecPostExitCancellationTest {
    @Test
    fun `parent cancellation during post-exit child cleanup reaches the exec caller`() = fixture(cancel = true)

    @Test
    fun `post-exit child cleanup without cancellation preserves the exec result`() = fixture(cancel = false)

    private fun fixture(cancel: Boolean): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"), "Requires Linux observed-child cleanup behavior")
        val directory = Files.createTempDirectory("kotlysh-exec-post-exit-")
        val script = directory.resolve("tool.py")
        Files.writeString(script, TOOL)
        val outcome = AtomicReference<Result<ExecResult>>()
        val cancellation = CancellationException("post-exit cleanup cancellation")
        var owned = emptyList<Pair<ProcessHandle, Instant>>()
        val work = async(Dispatchers.Default) {
            outcome.set(runCatching {
                exec(listOf("python3", "-u", script.toString(), "root", directory.toString()), timeout = 5.seconds,
                    output = { line ->
                        if (line.stream == RuntimeStream.Stdout && line.text == "ready") {
                            // Output delivery observes the child while the root is still alive.
                            Files.createFile(directory.resolve("observed"))
                        }
                    })
            })
        }
        try {
            awaitFile(directory.resolve("observed"))
            owned = Files.readAllLines(directory.resolve("pids")).map { value ->
                val handle = ProcessHandle.of(value.toLong()).orElseThrow()
                handle to handle.info().startInstant().orElseThrow()
            }
            assertEquals(2, owned.size)
            assertTrue(owned.all { it.first.isAlive })
            assertEquals(owned[0].first.pid(), owned[1].first.parent().orElseThrow().pid())
            Files.createFile(directory.resolve("exit-root"))
            awaitFile(directory.resolve("cleaning-child"))
            assertFalse(owned[0].first.isAlive, "Cancellation must arrive after root exit")
            assertTrue(owned[1].first.isAlive, "Cancellation must arrive during child cleanup")
            if (cancel) work.cancel(cancellation)
            Files.createFile(directory.resolve("finish-cleanup"))
            runCatching { work.await() }
            val actual = checkNotNull(outcome.get())
            if (cancel) {
                val error = actual.exceptionOrNull()
                val recovered = if (error?.javaClass == cancellation.javaClass && error.cause === cancellation) error.cause else error
                assertSame(cancellation, recovered)
            } else {
                val result = actual.getOrThrow()
                assertEquals(7, result.exitCode)
                assertFalse(result.timedOut)
                assertEquals(listOf("ready"), result.stdout)
                assertEquals(listOf("diagnostic"), result.stderr)
            }
            assertTrue(owned.none { it.first.isAlive }, "Exec returned before child cleanup")
            val reused = exec(listOf("python3", "-c", "print('reusable')"))
            assertTrue(reused.ok)
            assertEquals(listOf("reusable"), reused.stdout)
        } finally {
            Files.writeString(directory.resolve("finish-cleanup"), "finish")
            owned.asReversed().forEach { (handle, started) ->
                if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
            }
            work.cancel()
            work.join()
            directory.toFile().deleteRecursively()
        }
    }

    private fun awaitFile(path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(Files.exists(path), "Exec fixture did not reach ${path.fileName}")
    }

    companion object {
        private val TOOL = """
            import os, pathlib, signal, subprocess, sys, time
            directory = pathlib.Path(sys.argv[2])
            if sys.argv[1] == 'child':
                def terminate(signum, frame):
                    (directory / 'cleaning-child').write_text('cleaning')
                    while not (directory / 'finish-cleanup').exists():
                        time.sleep(0.001)
                    sys.exit(0)
                signal.signal(signal.SIGTERM, terminate)
                (directory / 'child-ready').write_text('ready')
                while True:
                    time.sleep(0.01)
            else:
                child = subprocess.Popen([sys.executable, '-u', __file__, 'child', str(directory)])
                (directory / 'pids').write_text(str(os.getpid()) + '\n' + str(child.pid) + '\n')
                while not (directory / 'child-ready').exists():
                    time.sleep(0.001)
                print('ready', flush=True)
                print('diagnostic', file=sys.stderr, flush=True)
                while not (directory / 'exit-root').exists():
                    time.sleep(0.001)
                sys.exit(7)
        """.trimIndent()
    }
}
