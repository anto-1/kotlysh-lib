package com.kotlysh.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class ExecDeadlineCollectionTest {
    @Test
    fun `mandatory cleanup after collected completion preserves the original process result`() = fixture(lateCleanup = true)

    @Test
    fun `cleanup within the process budget preserves separate output and exit status`() = fixture(lateCleanup = false)

    private fun fixture(lateCleanup: Boolean): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"), "Requires Linux observed-child cleanup behavior")
        val directory = Files.createTempDirectory("kotlysh-exec-collection-deadline-")
        val script = directory.resolve("tool.py")
        Files.writeString(script, TOOL)
        val begun = AtomicLong()
        var owned = emptyList<Pair<ProcessHandle, Instant>>()
        val work = async(Dispatchers.Default) {
            begun.set(System.nanoTime())
            exec(listOf("python3", "-u", script.toString(), "root", directory.toString()), timeout = 2.seconds,
                output = { if (it.stream == RuntimeStream.Stdout) Files.writeString(directory.resolve("observed"), "observed") })
        }
        try {
            awaitFile(directory.resolve("observed"))
            owned = Files.readAllLines(directory.resolve("pids")).map { value ->
                val process = ProcessHandle.of(value.toLong()).orElseThrow()
                process to process.info().startInstant().orElseThrow()
            }
            assertEquals(2, owned.size)
            assertTrue(owned.all { it.first.isAlive })
            assertEquals(owned[0].first.pid(), owned[1].first.parent().orElseThrow().pid())
            val releaseAt = begun.get() + TimeUnit.MILLISECONDS.toNanos(if (lateCleanup) 1_900 else 200)
            while (System.nanoTime() < releaseAt) Thread.sleep(1)
            Files.createFile(directory.resolve("exit-root"))
            awaitFile(directory.resolve("cleaning-child"))
            assertFalse(owned[0].first.isAlive)
            assertTrue(System.nanoTime() - begun.get() < TimeUnit.SECONDS.toNanos(2))
            assertTrue(owned[1].first.isAlive)
            if (!lateCleanup) Files.createFile(directory.resolve("finish-cleanup"))
            val result = work.await()
            assertTrue(owned.none { it.first.isAlive }, "Exec returned before mandatory child cleanup")
            if (lateCleanup) assertTrue(System.nanoTime() - begun.get() >= TimeUnit.SECONDS.toNanos(2))
            // The core deadline covers the process wait and output collection.
            // Noncancellable cleanup cannot retime their already completed facts.
            assertEquals(7, result.exitCode)
            assertFalse(result.timedOut)
            assertEquals(listOf("source"), result.stdout)
            assertEquals(listOf("diagnostic"), result.stderr)
        } finally {
            Files.writeString(directory.resolve("finish-cleanup"), "finish")
            owned.asReversed().forEach { (process, started) ->
                if (process.isAlive && process.info().startInstant().orElse(null) == started) process.destroyForcibly()
            }
            work.cancel()
            work.join()
            directory.toFile().deleteRecursively()
        }
    }

    private fun awaitFile(path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(Files.exists(path), "Fixture did not reach ${path.fileName}")
    }

    companion object {
        private val TOOL = """
            import os, pathlib, signal, subprocess, sys, time
            directory = pathlib.Path(sys.argv[2])
            if sys.argv[1] == 'child':
                os.close(0)
                os.close(1)
                os.close(2)
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
                print('source', flush=True)
                print('diagnostic', file=sys.stderr, flush=True)
                while not (directory / 'exit-root').exists():
                    time.sleep(0.001)
                sys.exit(7)
        """.trimIndent()
    }
}
