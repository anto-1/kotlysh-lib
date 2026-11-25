package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Isolated("Changes the automatic tracing property and stdout")
@Timeout(15)
class RuntimeAutomaticTraceTest {
    @Test
    fun `ordinary CLI execution remains silent without automatic tracing`(): Unit = runBlocking {
        for (setting in listOf(null, "false")) {
            val text = capture(setting) {
                sh { assertEquals(listOf("ready"), "printf ready"().stdout) }
            }
            assertEquals("", text)
        }
    }

    @Test
    fun `opted in task emits protocol events while preserving separate result streams`(): Unit = runBlocking {
        val text = capture("true") {
            sh {
                val result = "printf visible; printf diagnostic >&2"()
                assertTrue(result.ok)
                assertEquals(listOf("visible"), result.stdout)
                assertEquals(listOf("diagnostic"), result.stderr)
            }
        }
        val records = records(text)
        assertEquals(6, records.size)
        assertEquals("session-started", records.first()["event"]?.jsonPrimitive?.content)
        assertEquals("session-closed", records.last()["event"]?.jsonPrimitive?.content)
        assertEquals(setOf("stdout" to "visible", "stderr" to "diagnostic"), records
            .filter { it["event"]?.jsonPrimitive?.content == "dispatch-output" }
            .map { it["stream"]!!.jsonPrimitive.content to it["text"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `unterminated ordinary output cannot consume automatic trace prefixes`(): Unit = runBlocking {
        val text = capture("true") {
            print("before session")
            sh {
                print("before dispatch")
                val result = "printf ready"(output = RuntimeOutputSink { print("before output") })
                assertTrue(result.ok)
                assertEquals(listOf("ready"), result.stdout)
                assertTrue(result.stderr.isEmpty())
            }
        }
        val lines = text.lineSequence().filter(String::isNotEmpty).toList()
        assertEquals(listOf("before session", "before dispatch", "before output"),
            lines.filterNot { it.startsWith(RUNTIME_TRACE_JSONL_PREFIX) })
        val records = records(lines.filter { it.startsWith(RUNTIME_TRACE_JSONL_PREFIX) }.joinToString("\n"))
        assertEquals(listOf("session-started", "dispatch-started", "dispatch-output", "dispatch-completed", "session-closed"),
            records.map { it["event"]!!.jsonPrimitive.content })
        assertEquals(1, records.map { it["sessionId"]!!.jsonPrimitive.content }.distinct().size)
    }

    @Test
    fun `explicit trace context overrides automatic output and limits`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val text = capture("true") {
            withRuntimeTrace(trace, RuntimeTraceLimits(maxScriptPreviewCharacters = 0)) {
                sh { assertTrue("printf ready"().ok) }
            }
        }
        assertEquals("", text)
        assertEquals(5, trace.events.size)
        assertEquals("", trace.events.filterIsInstance<RuntimeDispatchStarted>().single().scriptPreview)
    }

    @Test
    fun `automatic tracing serializes records across independent concurrent sessions`(): Unit = runBlocking {
        val text = capture("true") {
            coroutineScope {
                (1..8).map { async { sh { assertTrue("printf ready"().ok) } } }.awaitAll()
            }
        }
        val sessions = records(text).groupBy { it["sessionId"]!!.jsonPrimitive.content }
        assertEquals(8, sessions.size)
        for (session in sessions.values) {
            assertEquals(5, session.size)
            assertEquals("session-started", session.first()["event"]!!.jsonPrimitive.content)
            assertEquals("session-closed", session.last()["event"]!!.jsonPrimitive.content)
        }
    }

    private fun records(text: String): List<JsonObject> = text.lineSequence().filter(String::isNotEmpty).map { line ->
        assertTrue(line.startsWith(RUNTIME_TRACE_JSONL_PREFIX))
        Json.parseToJsonElement(line.removePrefix(RUNTIME_TRACE_JSONL_PREFIX)).jsonObject.also { record ->
            assertEquals("1", record["protocolVersion"]!!.jsonPrimitive.content)
        }
    }.toList()

    private suspend fun capture(setting: String?, block: suspend () -> Unit): String {
        val previousProperty = System.getProperty(RUNTIME_TRACE_JSONL_PROPERTY)
        val previousOut = System.out
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, Charsets.UTF_8)
        try {
            if (setting == null) System.clearProperty(RUNTIME_TRACE_JSONL_PROPERTY)
            else System.setProperty(RUNTIME_TRACE_JSONL_PROPERTY, setting)
            System.setOut(output)
            block()
        } finally {
            System.setOut(previousOut)
            if (previousProperty == null) System.clearProperty(RUNTIME_TRACE_JSONL_PROPERTY)
            else System.setProperty(RUNTIME_TRACE_JSONL_PROPERTY, previousProperty)
            output.close()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
