package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.security.MessageDigest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class ShellSourceBoundaryTest {
    @Test
    fun `single line Shell preserves significant trailing Unicode whitespace`(): Unit = runBlocking {
        for (suffix in listOf("\u00a0", "\u2003")) {
            compareWithNative("builtin printf 'stderr|%s' diagnostic >&2; builtin printf 'stdout|%s' tail$suffix")
        }
    }

    @Test
    fun `escaped trailing Shell blanks cannot consume completion bookkeeping`(): Unit = runBlocking {
        for (suffix in listOf("\\ ", "\\\t")) {
            compareWithNative("builtin printf 'stderr|%s' diagnostic >&2; builtin printf 'stdout|%s' tail$suffix")
        }
    }

    @Test
    fun `Shell common indentation does not trim the terminal argument`(): Unit = runBlocking {
        val source = "\n    builtin printf 'stderr|%s' diagnostic >&2\n    builtin printf 'first\\n'\n    builtin printf 'stdout|%s' tail\\ \n"
        compareWithNative(source)
    }

    @Test
    fun `direct Shell dispatch preserves terminal blank lines and last status`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        lateinit var process: ProcessHandle
        withRuntimeTrace(trace) {
            sh {
                process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                for (source in listOf("false  ", "false\n\n", "false\n# final comment  \n\n", "true\n\n")) {
                    val native = native(source)
                    val result = runtime.exec(source, timeout = 3.seconds)
                    assertEquals(native.exitCode, result.exitCode)
                    assertFalse(result.timedOut)
                    assertEquals(native.stdout, result.stdout)
                    assertEquals(native.stderr, result.stderr)
                    assertTrue(runtime.isOpen)
                }
                assertEquals(listOf("ready"), "builtin printf ready"(timeout = 3.seconds).stdout)
            }
        }
        assertFalse(process.isAlive)
        assertEquals(listOf(1, 1, 1, 0, 0), trace.events.filterIsInstance<RuntimeDispatchCompleted>().map { it.exitCode })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private suspend fun compareWithNative(source: String) {
        val normalized = source.normalizeKotlyshScript()
        val native = native(normalized)
        assertTrue(native.ok, native.stderrText)
        val trace = RuntimeTraceBuffer()
        lateinit var process: ProcessHandle
        withRuntimeTrace(trace) {
            sh {
                process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val result = source(timeout = 3.seconds)
                assertTrue(result.ok, "source=${source.toList()}: $result")
                assertEquals(native.stdout, result.stdout)
                assertEquals(native.stderr, result.stderr)
                assertFalse(result.timedOut)
                assertFalse(result.outputLimited)
                assertTrue(runtime.isOpen)
                assertEquals(listOf("ready"), "builtin printf ready"(timeout = 3.seconds).stdout)
            }
        }
        assertFalse(process.isAlive)
        val dispatch = trace.events.filterIsInstance<RuntimeDispatchStarted>().first()
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(normalized, dispatch.scriptPreview)
        assertEquals(expectedHash, dispatch.scriptSha256)
        val output = trace.events.filterIsInstance<RuntimeDispatchOutput>().filter { it.dispatchId == dispatch.dispatchId }
        assertEquals(native.stdout, output.filter { it.stream == RuntimeStream.Stdout }.map { it.text })
        assertEquals(native.stderr, output.filter { it.stream == RuntimeStream.Stderr }.map { it.text })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private suspend fun native(source: String): ExecResult =
        exec(listOf("/bin/bash", "--noprofile", "--norc", "-c", source), timeout = 3.seconds)
}
