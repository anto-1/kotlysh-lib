package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import kotlin.test.*

@Timeout(15)
class ExecUnicodeBoundaryTest {
    private val malformed = listOf("\uD800", "\uDC00", "\uDC00\uD800", "😀\uD800")

    @Test
    fun `persistent runtime arguments are rejected before launch and startup tracing`(): Unit = runBlocking {
        val root = Files.createTempDirectory("kotlysh-runtime-unicode-argument")
        try {
            val marker = root.resolve("launched")
            for (value in malformed) {
                for (command in listOf(
                    listOf("/bin/bash", "-c", "printf launched > \"\$1\"; cat", "_", marker.toString(), value),
                    listOf("missing-$value"),
                )) {
                    val trace = RuntimeTraceBuffer()
                    withRuntimeTrace(trace) {
                        assertFailsWith<IllegalArgumentException> {
                            runtime(RuntimeSpecs.Bash.copy(command = command)) { error("Malformed command reached the body") }
                        }
                    }
                    assertFalse(Files.exists(marker), "Malformed interpreter command started a child")
                    assertTrue(trace.events.filterIsInstance<RuntimeSessionStarted>().isEmpty())
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed command arguments fail before a native child can start`(): Unit = runBlocking {
        val root = Files.createTempDirectory("kotlysh-exec-unicode-argument")
        try {
            val marker = root.resolve("launched")
            for (value in malformed) {
                assertFailsWith<IllegalArgumentException> {
                    exec(listOf("/bin/bash", "-c", "printf launched > \"\$1\"; printf '%s' \"\$2\"", "_", marker.toString(), value))
                }
                assertFalse(Files.exists(marker), "Malformed argument started a child")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed environment names and values fail before a native child can start`(): Unit = runBlocking {
        val root = Files.createTempDirectory("kotlysh-exec-unicode-environment")
        try {
            val marker = root.resolve("launched")
            for (value in malformed) {
                for (environment in listOf(mapOf("KOTLYSH_BAD" to value), mapOf("KOTLYSH_BAD_$value" to "value"))) {
                    assertFailsWith<IllegalArgumentException> {
                        exec(listOf("/bin/bash", "-c", "printf launched > \"\$1\"", "_", marker.toString()), env = environment)
                    }
                    assertFalse(Files.exists(marker), "Malformed environment started a child")
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed executable names are rejected as arguments before native lookup`(): Unit = runBlocking {
        for (value in malformed) {
            val failure = assertFailsWith<IllegalArgumentException> { exec(listOf("missing-$value")) }
            assertTrue(failure.message!!.contains("unpaired UTF-16 surrogate"))
        }
    }

    @Test
    fun `valid Unicode arguments environment and literal replacement characters stay exact`(): Unit = runBlocking {
        val text = "café Ελληνικά 日本語 😀\n\uFFFD"
        val name = "KOTLYSH_UNICODE_café"
        val command = listOf("python3", "-c", "import os,sys,json; print(json.dumps([sys.argv[1], os.environ[sys.argv[2]]], ensure_ascii=False))", text, name)
        val result = exec(command, env = mapOf(name to text, "PYTHONIOENCODING" to "utf-8"))
        assertTrue(result.ok, result.stderrText)
        assertEquals(command, result.command)
        assertEquals(JsonArray(listOf(JsonPrimitive(text), JsonPrimitive(text))), Json.parseToJsonElement(result.stdoutText))
    }
}
