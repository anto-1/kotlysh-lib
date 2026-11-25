package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(25)
class RuntimeControlCharacterTest {
    @Test
    fun `long Unicode exec output preserves decoded lines and separate callbacks`(): Unit = runBlocking {
        val text = "α😀\uFFFD".repeat(1_500)
        val stdout = "$text\r\n\r\nleft\rright\u0000\t\r\n尾😀"
        val stderr = "diagnostic\r\n$text\r\nend"
        val output = mutableListOf<RuntimeOutputLine>()
        val result = exec(nativeOutput(stdout, stderr), output = { output += it })
        assertTrue(result.ok)
        assertEquals(listOf(text, "", "left", "right\u0000\t", "尾😀"), result.stdout)
        assertEquals(listOf("diagnostic", text, "end"), result.stderr)
        for (stream in RuntimeStream.entries) {
            assertEquals(if (stream == RuntimeStream.Stdout) result.stdout else result.stderr,
                output.filter { it.stream == stream }.map { it.text })
        }
    }

    @Test
    fun `structured control characters survive bindings copies output framing and reuse`(): Unit = runBlocking {
        val text = "leading\r\nmid\rtrail\n\t\u0000\u001b\u2028\u2029😀\uFFFD"
        val expectedLines = listOf("leading", "mid\rtrail", "\t\u0000\u001b\u2028\u2029😀\uFFFD")
        val trace = RuntimeTraceBuffer()
        val wire = mutableListOf<String>()
        val jsonl = RuntimeJsonlTraceSink(wire::add)
        withRuntimeTrace(RuntimeTraceSink { trace.onEvent(it); jsonl.onEvent(it) }) {
            py {
                assertEquals(text, eval<String>("(print(payload, end=''), payload)[1]", "payload" to value(text)))
                store(text) { original ->
                    node {
                        copy(original) { copied ->
                            assertEquals(text, read<String>(copied))
                            assertEquals(text, eval<String>("(process.stderr.write(payload), payload)", "payload" to copied))
                        }
                        assertEquals(42, eval<Int>("42"))
                    }
                    assertEquals(text, read<String>(original))
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(expectedLines, trace.events.filterIsInstance<RuntimeDispatchOutput>()
            .filter { it.stream == RuntimeStream.Stdout }.map { it.text })
        assertEquals(expectedLines, trace.events.filterIsInstance<RuntimeDispatchOutput>()
            .filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().all { !it.poisoned })
        val copied = trace.events.filterIsInstance<RuntimeDispatchStarted>().single {
            it.inputSessionIds.any { source -> source != it.sessionId }
        }
        assertEquals("store", copied.operation)
        // These actual producer records are replayed through the packaged IDE
        // consumer by the release verification harness. LF alone frames JSONL;
        // Unicode line/paragraph separators are ordinary JSON string data.
        assertTrue(wire.all { '\n' !in it && '\r' !in it && '\u0000' !in it })
        wire.forEach(::println)
    }

    @Test
    fun `persistent logs preserve embedded CR and NUL while normalizing CRLF`(): Unit = runBlocking {
        val stdout = "out\rdata\u0000😀\r\nlast"
        val stderr = "err\rdata\u0000雪\r\nend"
        val out = Base64.getEncoder().encodeToString(stdout.toByteArray(Charsets.UTF_8))
        val err = Base64.getEncoder().encodeToString(stderr.toByteArray(Charsets.UTF_8))
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            val source = if (spec == RuntimeSpecs.Python) {
                "import sys,base64; sys.stdout.buffer.write(base64.b64decode('$out')); sys.stderr.buffer.write(base64.b64decode('$err'))"
            } else {
                "process.stdout.write(Buffer.from('$out','base64')); process.stderr.write(Buffer.from('$err','base64')); void 0"
            }
            runtime(spec) {
                repeat(2) {
                    val result = source()
                    assertTrue(result.ok)
                    assertFalse(result.outputLimited)
                    assertEquals(listOf("out\rdata\u0000😀", "last"), result.stdout)
                    assertEquals(listOf("err\rdata\u0000雪", "end"), result.stderr)
                    assertTrue(runtime.isOpen)
                }
            }
        }
    }

    private fun nativeOutput(stdout: String, stderr: String): List<String> {
        val out = Base64.getEncoder().encodeToString(stdout.toByteArray(Charsets.UTF_8))
        val err = Base64.getEncoder().encodeToString(stderr.toByteArray(Charsets.UTF_8))
        return listOf("python3", "-c", "import sys,base64; sys.stdout.buffer.write(base64.b64decode('$out')); sys.stderr.buffer.write(base64.b64decode('$err'))")
    }
}
