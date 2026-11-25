package com.kotlysh.core

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(20)
class ExecLaunchAdmissionTest {
    @Test
    fun `already cancelled exec does not acquire a process`(): Unit = runBlocking {
        verifyRejectedLaunch { cancellation, job ->
            job.cancel(cancellation)
            Launch()
        }
    }

    @Test
    fun `cancellation during environment capture prevents process acquisition`(): Unit = runBlocking {
        var captures = 0
        verifyRejectedLaunch { cancellation, job ->
            Launch(environment = object : LinkedHashMap<String, String>() {
                override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
                    get() {
                        captures++
                        job.cancel(cancellation)
                        return super.entries
                    }
            }.apply { put("KOTLYSH_ADMISSION", "controlled") })
        }
        assertEquals(1, captures)
    }

    @Test
    fun `cancellation during command capture prevents process acquisition without rereading the caller`(): Unit = runBlocking {
        var captures = 0
        verifyRejectedLaunch { cancellation, job ->
            Launch(command = { initial ->
                object : java.util.ArrayList<String>(initial) {
                    private fun cancelCapture() {
                        captures++
                        job.cancel(cancellation)
                    }

                    override fun get(index: Int): String = super.get(index).also { cancelCapture() }
                    override fun toArray(): Array<Any?> = super.toArray().also { cancelCapture() }
                    override fun <T> toArray(array: Array<T>): Array<T> = super.toArray(array).also { cancelCapture() }
                }
            })
        }
        assertEquals(1, captures)
    }

    @Test
    fun `cancellation during working directory capture prevents process acquisition`(): Unit = runBlocking {
        var captures = 0
        verifyRejectedLaunch { cancellation, job ->
            val directory = Path.of(".")
            Launch(directory = object : Path by directory {
                override fun toFile(): java.io.File {
                    captures++
                    job.cancel(cancellation)
                    return directory.toFile()
                }
            })
        }
        assertEquals(1, captures)
    }

    private suspend fun verifyRejectedLaunch(prepare: (CancellationException, Job) -> Launch) {
        assumeTrue(isUnix(), "Requires Unix Bash launch-admission fixtures")
        val rejected = "kotlysh-rejected-${UUID.randomUUID()}"
        val healthy = "kotlysh-healthy-${UUID.randomUUID()}"
        val destination = Files.createTempFile("kotlysh-exec-admission-", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable("jdk.ProcessStart").withoutStackTrace()
                recording.start()
                val original = CancellationException("controlled launch admission cancellation")
                val failure = runCatching {
                    coroutineScope {
                        val launch = prepare(original, coroutineContext.job)
                        exec(launch.command(command(rejected)), cwd = launch.directory, env = launch.environment, timeout = null)
                    }
                }.exceptionOrNull()
                val recovered = if (failure?.javaClass == original.javaClass && failure.cause === original) failure.cause else failure
                assertSame(original, recovered)
                // A healthy command in the same recording proves both reuse and
                // that ProcessStart events actually observed this test's launches.
                val result = exec(command(healthy))
                assertTrue(result.ok)
                assertEquals(listOf("healthy"), result.stdout)
                assertEquals(listOf("diagnostic"), result.stderr)
                recording.stop()
                recording.dump(destination)
            }
            val starts = RecordingFile.readAllEvents(destination)
                .filter { it.eventType.name == "jdk.ProcessStart" }
                .map { it.getString("command") }
            assertEquals(1, starts.count { healthy in it }, "The positive process-start control was not observed")
            assertEquals(0, starts.count { rejected in it }, "Cancelled work acquired a native process before cleanup")
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    private fun command(marker: String) = listOf(
        "/bin/bash", "--noprofile", "--norc", "-c", "printf 'healthy\\n'; printf 'diagnostic\\n' >&2", marker,
    )

    private fun isUnix() = java.io.File.separatorChar == '/'

    private data class Launch(
        val directory: Path? = null,
        val environment: Map<String, String> = emptyMap(),
        val command: (List<String>) -> List<String> = { it },
    )
}
