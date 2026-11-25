package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimePartialLineLimitTest {
    @Test
    fun `Shell stops when an unfinished log line exceeds the line budget`(): Unit = runBlocking {
        assertPartialLineLimit(RuntimeSpecs.Bash)
    }

    @Test
    fun `Python stops when an unfinished log line exceeds the line budget`(): Unit = runBlocking {
        assertPartialLineLimit(RuntimeSpecs.Python)
    }

    @Test
    fun `Node stops when an unfinished log line exceeds the line budget`(): Unit = runBlocking {
        assertPartialLineLimit(RuntimeSpecs.Node)
    }

    @Test
    fun `Ruby stops when an unfinished log line exceeds the line budget`(): Unit = runBlocking {
        assertPartialLineLimit(RuntimeSpecs.Ruby)
    }

    @Test
    fun `exact line budgets admit built in completion and reset for the next dispatch`(): Unit = runBlocking {
        for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node, RuntimeSpecs.Ruby)) {
            for (stream in RuntimeStream.entries) {
                withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputLinesPerDispatch = 1)) {
                    runtime(spec) {
                        for (text in listOf("accepted", "next")) {
                            val result = script(spec, stream, "$text\\n", pause = false)(timeout = 3.seconds)
                            assertTrue(result.ok, "${spec.name}/$stream: $result")
                            assertEquals(listOf(text), if (stream == RuntimeStream.Stdout) result.stdout else result.stderr)
                            assertTrue(runtime.isOpen)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `all built in runtimes enforce unfinished line limits without tracing`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1)) {
            for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node, RuntimeSpecs.Ruby)) {
                for (stream in RuntimeStream.entries) {
                    runtime(spec) {
                        val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        val result = script(spec, stream, "accepted\\nexcess", pause = true)(timeout = 3.seconds)
                        assertTrue(result.outputLimited, "${spec.name}/$stream: $result")
                        assertFalse(result.timedOut)
                        assertFalse(result.idleTimedOut)
                        assertEquals(listOf("accepted"), if (stream == RuntimeStream.Stdout) result.stdout else result.stderr)
                        assertFalse(runtime.isOpen)
                        assertFalse(process.isAlive)
                    }
                }
            }
        }
    }

    @Test
    fun `fragmented completion prefixes stay outside an exhausted line budget`(): Unit = runBlocking {
        for (split in listOf(1, 9, 20)) {
            val spec = RuntimeSpec("fragmented-line-limit", RuntimeSpecs.Bash.command) { script, marker ->
                buildString {
                    appendLine(script)
                    for (redirect in listOf("", " >&2")) {
                        appendLine("printf '%s' '${marker.take(split)}'$redirect")
                        appendLine("sleep 0.05")
                        appendLine("printf '%s\\n' '${marker.drop(split)}:0'$redirect")
                    }
                }
            }
            val result = withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputLinesPerDispatch = 1)) {
                runtime(spec) { "printf 'accepted\\n'"(timeout = 3.seconds) }
            }
            assertTrue(result.ok, "Marker split at $split: $result")
            assertEquals(listOf("accepted"), result.stdout)
            assertTrue(result.stderr.isEmpty())
        }
    }

    @Test
    fun `an unfinished line in the other stream consumes the same dispatch line budget`(): Unit = runBlocking {
        for (first in RuntimeStream.entries) {
            val directory = Files.createTempDirectory("kotlysh-line-limit-")
            val ready = directory.resolve("accepted")
            val firstRedirect = if (first == RuntimeStream.Stderr) " >&2" else ""
            val nextRedirect = if (first == RuntimeStream.Stdout) " >&2" else ""
            try {
                val result = withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputLinesPerDispatch = 1)) {
                    sh {
                        val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        val result = """
                            printf 'accepted\n'$firstRedirect
                            while [ ! -e ${shLiteral(ready.toString())} ]; do sleep 0.01; done
                            printf excess$nextRedirect
                            sleep 30
                        """(timeout = 3.seconds, output = { line ->
                            assertEquals(first, line.stream)
                            assertEquals("accepted", line.text)
                            Files.writeString(ready, "observed")
                        })
                        assertFalse(process.isAlive)
                        result
                    }
                }
                assertTrue(result.outputLimited)
                assertFalse(result.timedOut)
                assertFalse(result.idleTimedOut)
                assertEquals(listOf("accepted"), if (first == RuntimeStream.Stdout) result.stdout else result.stderr)
                assertTrue((if (first == RuntimeStream.Stdout) result.stderr else result.stdout).isEmpty())
            } finally {
                Files.deleteIfExists(ready)
                Files.deleteIfExists(directory)
            }
        }
    }

    private suspend fun assertPartialLineLimit(spec: RuntimeSpec) {
        for (stream in RuntimeStream.entries) {
            val trace = RuntimeTraceBuffer()
            val observed = mutableListOf<RuntimeOutputLine>()
            val result = withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputLinesPerDispatch = 1)) {
                runtime(spec) {
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val result = script(spec, stream, "accepted\\nexcess", pause = true)(
                        timeout = 3.seconds,
                        output = observed::add,
                    )
                    assertFalse(process.isAlive, "The owned interpreter must stop before returning")
                    assertFalse(runtime.isOpen)
                    result
                }
            }
            assertTrue(result.outputLimited, "${spec.name}/$stream waited for a deadline instead of enforcing its line budget: $result")
            assertFalse(result.timedOut)
            assertFalse(result.idleTimedOut)
            assertFalse(result.ok)
            assertEquals(listOf("accepted"), if (stream == RuntimeStream.Stdout) result.stdout else result.stderr)
            assertTrue((if (stream == RuntimeStream.Stdout) result.stderr else result.stdout).isEmpty())
            assertEquals(listOf("accepted"), observed.map { it.text })
            assertEquals(listOf(stream), observed.map { it.stream })
            val completed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single()
            assertTrue(completed.outputLimited)
            assertFalse(completed.timedOut)
            assertFalse(completed.idleTimedOut)
            assertEquals(1, completed.stdoutLines + completed.stderrLines)
            assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        }
    }

    private fun script(spec: RuntimeSpec, stream: RuntimeStream, text: String, pause: Boolean): String = when (spec.name) {
        "sh" -> "printf '$text'${if (stream == RuntimeStream.Stderr) " >&2" else ""}${if (pause) "; sleep 30" else ""}"
        "py" -> "import sys, time; sys.${stream.name.lowercase()}.write('$text'); sys.${stream.name.lowercase()}.flush()${if (pause) "; time.sleep(30)" else ""}"
        "node" -> "process.${stream.name.lowercase()}.write('$text');${if (pause) " new Promise(resolve => setTimeout(resolve, 30000));" else " void 0;"}"
        "ruby" -> "${stream.name.uppercase()}.write(\"$text\"); ${stream.name.uppercase()}.flush${if (pause) "; sleep(30)" else ""}"
        else -> error("Unexpected runtime: ${spec.name}")
    }
}
