package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Isolated("Captures the host stdout and stderr streams")
@Timeout(15)
class RuntimeJsonlTraceStderrTest {
    @Test
    fun `one stderr trace transport preserves separate native logs and physical record lines`(): Unit = runBlocking {
        val previousOut = System.out
        val previousErr = System.err
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err ->
                try {
                    System.setOut(out)
                    System.setErr(err)
                    print("host stdout without LF")
                    System.err.print("host stderr without LF")
                    withRuntimeTrace(RuntimeJsonlTraceSink { System.err.println("\n$it") }) {
                        sh {
                            val result = "printf 'foreign-out😀\\n'; printf 'foreign-err\\n' >&2"()
                            assertTrue(result.ok)
                            assertEquals(listOf("foreign-out😀"), result.stdout)
                            assertEquals(listOf("foreign-err"), result.stderr)
                        }
                    }
                } finally {
                    System.setOut(previousOut)
                    System.setErr(previousErr)
                }
            }
        }

        assertEquals("host stdout without LF", stdout.toString(Charsets.UTF_8))
        val lines = stderr.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).toList()
        assertEquals("host stderr without LF", lines.first())
        val records = lines.drop(1).map { line ->
            assertTrue(line.startsWith(RUNTIME_TRACE_JSONL_PREFIX))
            Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject
        }
        assertEquals(6, records.size)
        assertEquals("session-started", records.first().getValue("event").jsonPrimitive.content)
        assertEquals("session-closed", records.last().getValue("event").jsonPrimitive.content)
        assertTrue(records.all { it.getValue("protocolVersion").jsonPrimitive.content == "1" })
        val output = records.filter { it.getValue("event").jsonPrimitive.content == "dispatch-output" }
        assertEquals(setOf("stdout" to "foreign-out😀", "stderr" to "foreign-err"),
            output.map { it.getValue("stream").jsonPrimitive.content to it.getValue("text").jsonPrimitive.content }.toSet())
        assertEquals(setOf(0L, 1L), output.map { it.getValue("sequence").jsonPrimitive.long }.toSet())
        val completed = records.single { it.getValue("event").jsonPrimitive.content == "dispatch-completed" }
        assertEquals(1L, completed.getValue("stdoutLines").jsonPrimitive.long)
        assertEquals(1L, completed.getValue("stderrLines").jsonPrimitive.long)
        val pid = records.first().getValue("processId").jsonPrimitive.long
        assertEquals(pid, records.last().getValue("processId").jsonPrimitive.long)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }
}
