package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeOutputCollectorContractTest {
    private val nativeOut = "out α🙃\r\n\r\n尾"
    private val nativeErr = "err café🙂\n\n終"
    private val stdout = listOf("out α🙃", "", "尾")
    private val stderr = listOf("err café🙂", "", "終")

    @Test
    fun `Bash collector preserves blank Unicode and partial lines across failed and healthy dispatches`(): Unit = runBlocking {
        val source = "counter=41; printf '%s' ${shLiteral(nativeOut)}; printf '%s' ${shLiteral(nativeErr)} >&2; false"
        val oracle = native(listOf("/bin/bash", "--noprofile", "--norc", "-c", source))
        assertEquals(1, oracle.exitCode)
        assertEquals(nativeOut, oracle.stdout)
        assertEquals(nativeErr, oracle.stderr)
        val buffer = RuntimeOutputBuffer()
        val trace = RuntimeTraceBuffer()
        var root: ProcessHandle? = null
        withRuntimeTrace(trace) {
            sh {
                root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val first = source(timeout = 5.seconds, output = buffer)
                assertEquals(1, first.exitCode)
                assertFalse(first.ok)
                assertTrue(runtime.isOpen)
                assertEquals(stdout, first.stdout)
                assertEquals(stderr, first.stderr)
                checkBuffer(buffer, "sh", stdout, stderr)
                val snapshot = buffer.lines
                val stdoutSnapshot = buffer.stdout
                val stderrSnapshot = buffer.stderr
                val second = "printf 'follow:%s' \"\$counter\"; printf again >&2"(timeout = 5.seconds, output = buffer)
                assertTrue(second.ok)
                assertEquals(listOf("follow:41"), second.stdout)
                assertEquals(listOf("again"), second.stderr)
                checkBuffer(buffer, "sh", stdout + "follow:41", stderr + "again")
                assertEquals(stdout, stdoutSnapshot)
                assertEquals(stderr, stderrSnapshot)
                assertEquals(6, snapshot.size)
                assertEquals(stdout, snapshot.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
                assertEquals(stderr, snapshot.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
            }
        }
        assertFalse(root!!.isAlive)
        assertHealthyTrace(trace, "sh", 2, stdout + "follow:41", stderr + "again")
        assertTrue(currentCoroutineContext().isActive)
        report("bash", source, oracle, buffer, root!!.pid())
    }

    @Test
    fun `Python typed output preserves collector snapshots and never publishes response transport`(): Unit = runBlocking {
        val source = "counter=41\nimport sys\nsys.stdout.write(${pythonLiteral(nativeOut)})\nsys.stderr.write(${pythonLiteral(nativeErr)})"
        val oracle = native(listOf("python3", "-u", "-c", source))
        assertEquals(0, oracle.exitCode)
        assertEquals(nativeOut, oracle.stdout)
        assertEquals(nativeErr, oracle.stderr)
        val expression = "(__import__('sys').stdout.write(output), __import__('sys').stderr.write(errors), counter + 1)[2]"
        val buffer = RuntimeOutputBuffer()
        val trace = RuntimeTraceBuffer()
        var root: ProcessHandle? = null
        withRuntimeTrace(trace) {
            py {
                root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                assertTrue("counter=41"(timeout = 5.seconds).ok)
                val evaluated = evaluate<Int>(expression, "output" to value(nativeOut), "errors" to value(nativeErr),
                    timeout = 5.seconds, output = buffer)
                assertEquals(42, evaluated.value)
                assertTrue(evaluated.execution.ok)
                assertEquals(stdout, evaluated.execution.stdout)
                assertEquals(stderr, evaluated.execution.stderr)
                checkBuffer(buffer, "py", stdout, stderr)
                val snapshot = buffer.lines
                val stdoutSnapshot = buffer.stdout
                val stderrSnapshot = buffer.stderr
                val second = "import sys; sys.stdout.write('follow:%s' % counter); sys.stderr.write('again')"(
                    timeout = 5.seconds, output = buffer)
                assertTrue(second.ok)
                assertEquals(listOf("follow:41"), second.stdout)
                assertEquals(listOf("again"), second.stderr)
                assertTrue(eval<Boolean>("'output' not in globals() and 'errors' not in globals()", timeout = 5.seconds))
                assertTrue(runtime.isOpen)
                checkBuffer(buffer, "py", stdout + "follow:41", stderr + "again")
                assertEquals(stdout, stdoutSnapshot)
                assertEquals(stderr, stderrSnapshot)
                assertEquals(6, snapshot.size)
                assertEquals(stdout, snapshot.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
                assertEquals(stderr, snapshot.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
            }
        }
        assertFalse(root!!.isAlive)
        assertHealthyTrace(trace, "py", 4, stdout + "follow:41", stderr + "again")
        assertTrue(currentCoroutineContext().isActive)
        report("python", source, oracle, buffer, root!!.pid(), expression)
    }

    @Test
    fun `concurrent exec writers retain live snapshots and complete both native streams`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kotlysh-output-collector")
        val ack = directory.resolve("all-ready")
        val buffer = RuntimeOutputBuffer()
        val roots = ConcurrentHashMap<String, ProcessHandle>()
        val firstSnapshot = AtomicReference<List<RuntimeOutputLine>>()
        val sink = RuntimeOutputSink { line ->
            if (line.text.startsWith("ready-out:")) {
                val fields = line.text.split(':')
                val handle = ProcessHandle.of(fields[2].substringBefore(' ').toLong()).orElseThrow()
                assertEquals(ProcessHandle.current().pid(), handle.parent().orElseThrow().pid())
                roots[fields[1]] = handle
            }
            buffer.onLine(line)
            val snapshot = buffer.lines
            if (snapshot.size == 4 && firstSnapshot.compareAndSet(null, snapshot)) {
                assertEquals(2, roots.size)
                assertTrue(roots.values.all { it.isAlive && it.descendants().use { children -> children.count() == 0L } })
                Files.writeString(ack, "continue")
            }
        }
        fun program(label: String) = """
            printf 'ready-out:$label:%s α🙃\n' "${'$'}BASHPID"
            printf 'ready-err:$label café🙂\n' >&2
            while [ ! -e ${shLiteral(ack.toString())} ]; do :; done
            for ((i=0;i<500;i++)); do
                printf 'out:$label:%s α🙃\n' "${'$'}i"
                printf 'err:$label:%s café🙂\n' "${'$'}i" >&2
            done
            printf 'last-out:$label'
            printf 'last-err:$label' >&2
        """.trimIndent()
        val sources = listOf("left", "right").map(::program)
        try {
            val results = sources.map { source -> async {
                exec(listOf("/bin/bash", "--noprofile", "--norc", "-c", source), timeout = 10.seconds, output = sink)
            } }.awaitAll()
            for ((index, label) in listOf("left", "right").withIndex()) {
                val result = results[index]
                assertTrue(result.ok)
                val expectedOut = listOf("ready-out:$label:${roots.getValue(label).pid()} α🙃") +
                    (0 until 500).map { "out:$label:$it α🙃" } + "last-out:$label"
                val expectedErr = listOf("ready-err:$label café🙂") +
                    (0 until 500).map { "err:$label:$it café🙂" } + "last-err:$label"
                assertEquals(expectedOut, result.stdout)
                assertEquals(expectedErr, result.stderr)
                assertEquals(expectedOut, buffer.stdout.filter { ":$label" in it })
                assertEquals(expectedErr, buffer.stderr.filter { ":$label" in it })
                assertFalse(roots.getValue(label).isAlive)
            }
            val snapshot = assertNotNull(firstSnapshot.get())
            assertEquals(4, snapshot.size)
            assertTrue(snapshot.all { it.text.startsWith("ready-") })
            assertEquals(2008, buffer.lines.size)
            assertTrue(buffer.lines.all { it.runtime == "exec" })
            assertEquals(buffer.stdout.joinToString("\n"), buffer.stdoutText)
            assertEquals(buffer.stderr.joinToString("\n"), buffer.stderrText)
            assertTrue(currentCoroutineContext().isActive)
            println("@@kotlysh-output-collector " + buildJsonObject {
                put("case", "concurrent-exec"); put("programLeft", sources[0]); put("programRight", sources[1])
                put("roots", roots.size); put("allRootsStopped", roots.values.none { it.isAlive })
                put("snapshotLines", snapshot.size); put("capturedLines", buffer.lines.size)
                put("stdoutLines", buffer.stdout.size); put("stderrLines", buffer.stderr.size)
            })
        } finally {
            for (root in roots.values) if (root.isAlive) {
                root.destroyForcibly()
                root.onExit().get(5, TimeUnit.SECONDS)
            }
            directory.toFile().deleteRecursively()
        }
    }

    private fun checkBuffer(buffer: RuntimeOutputBuffer, runtime: String, stdout: List<String>, stderr: List<String>) {
        assertEquals(stdout, buffer.stdout)
        assertEquals(stderr, buffer.stderr)
        assertEquals(stdout.joinToString("\n"), buffer.stdoutText)
        assertEquals(stderr.joinToString("\n"), buffer.stderrText)
        assertTrue(buffer.lines.all { it.runtime == runtime })
        assertEquals(stdout.size + stderr.size, buffer.lines.size)
    }

    private fun assertHealthyTrace(trace: RuntimeTraceBuffer, runtime: String, dispatches: Int,
        stdout: List<String>, stderr: List<String>) {
        val output = trace.events.filterIsInstance<RuntimeDispatchOutput>()
        assertEquals(stdout, output.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
        assertEquals(stderr, output.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        assertTrue(output.all { it.runtime == runtime })
        assertEquals(dispatches, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private data class NativeOutput(val pid: Long, val exitCode: Int, val stdout: String, val stderr: String)

    /** Tiny, bounded oracle programs use only Bash builtins or Python's standard streams. */
    private fun native(command: List<String>): NativeOutput {
        val process = ProcessBuilder(command).start()
        try {
            process.outputStream.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Native output oracle did not exit")
            return NativeOutput(process.pid(), process.exitValue(),
                process.inputStream.readBytes().toString(Charsets.UTF_8), process.errorStream.readBytes().toString(Charsets.UTF_8))
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
        }
    }

    private fun report(case: String, source: String, oracle: NativeOutput, buffer: RuntimeOutputBuffer,
        runtimePid: Long, expression: String? = null) {
        println("@@kotlysh-output-collector " + buildJsonObject {
            put("case", case); put("source", source); put("oraclePid", oracle.pid); put("runtimePid", runtimePid)
            put("oracleExitCode", oracle.exitCode); put("oracleStdout", oracle.stdout); put("oracleStderr", oracle.stderr)
            put("stdoutText", buffer.stdoutText); put("stderrText", buffer.stderrText)
            put("capturedLines", buffer.lines.size); expression?.let { put("expression", it) }
        })
    }
}
