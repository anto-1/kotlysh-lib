package com.kotlysh.core

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeTraceNumericBoundaryTest {
    @Test
    fun `invalid numeric fields fail before serialization or writer delivery`() {
        val lines = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(lines::add)
        val invalid = invalidEvents()
        assertEquals(50, invalid.size)
        for (event in invalid) {
            assertFailsWith<IllegalArgumentException>(event.toString()) { event.toTraceJson() }
            assertFailsWith<IllegalArgumentException>(event.toString()) { sink.onEvent(event) }
            assertTrue(lines.isEmpty(), "Invalid event reached the writer: $event")
        }
        val healthy = closed()
        sink.onEvent(healthy)
        assertEquals(listOf(RUNTIME_TRACE_JSONL_PREFIX + healthy.toTraceJson()), lines)
    }

    @Test
    fun `invalid timestamp and dispatch identity fail before collection callbacks`() {
        var reads = 0
        val hostile = object : AbstractList<String>() {
            override val size: Int get() { reads++; error("Collection must not be inspected") }
            override fun get(index: Int): String { reads++; error("Collection must not be inspected") }
        }
        val invalid = listOf(
            started().copy(atEpochMillis = -1, command = hostile),
            dispatch().copy(dispatchId = 0, inputSessionIds = hostile),
            dispatch().copy(atEpochMillis = Long.MIN_VALUE, inputSessionIds = hostile),
        )
        val sink = RuntimeJsonlTraceSink { error("Writer must not run") }
        for (event in invalid) {
            assertFailsWith<IllegalArgumentException> { event.toTraceJson() }
            assertFailsWith<IllegalArgumentException> { sink.onEvent(event) }
        }
        assertEquals(0, reads)
    }

    @Test
    fun `invalid reentrant numbers never enter the publication queue`() {
        val lines = mutableListOf<String>()
        val first = closed()
        val next = first.copy(sessionId = "next")
        lateinit var sink: RuntimeJsonlTraceSink
        sink = RuntimeJsonlTraceSink { line ->
            lines += line
            if (lines.size == 1) {
                assertFailsWith<IllegalArgumentException> { sink.onEvent(completed().copy(stdoutLines = Int.MAX_VALUE, stderrLines = 1)) }
                sink.onEvent(next)
            }
        }
        sink.onEvent(first)
        assertEquals(listOf(first, next).map { RUNTIME_TRACE_JSONL_PREFIX + it.toTraceJson() }, lines)
    }

    @Test
    fun `nonnegative boundaries optional counts and signed exit codes remain exact`() {
        val boundaries = listOf(
            started().copy(atEpochMillis = 0),
            dispatch().copy(atEpochMillis = Long.MAX_VALUE, dispatchId = Long.MAX_VALUE),
            output().copy(sequence = Int.MAX_VALUE, text = ""),
            completed().copy(elapsedMillis = Long.MAX_VALUE, exitCode = Int.MIN_VALUE, stdoutLines = Int.MAX_VALUE, stderrLines = 0),
            completed().copy(exitCode = Int.MAX_VALUE, stdoutLines = 0, stderrLines = Int.MAX_VALUE),
            failed().copy(elapsedMillis = 0, stdoutLines = null, stderrLines = null),
            failed().copy(stdoutLines = null, stderrLines = Int.MAX_VALUE),
            closed().copy(atEpochMillis = Long.MAX_VALUE, processId = null),
        )
        val lines = mutableListOf<String>()
        val sink = RuntimeJsonlTraceSink(lines::add)
        boundaries.forEach(sink::onEvent)
        assertEquals(boundaries.map { RUNTIME_TRACE_JSONL_PREFIX + it.toTraceJson() }, lines)
        assertEquals(Long.MAX_VALUE.toString(), boundaries[1].toTraceJson().getValue("dispatchId").jsonPrimitive.content)
        assertEquals(Int.MIN_VALUE.toString(), boundaries[3].toTraceJson().getValue("exitCode").jsonPrimitive.content)
        assertFalse(boundaries[5].toTraceJson().containsKey("stdoutLines"))
        assertFalse(boundaries[5].toTraceJson().containsKey("stderrLines"))
        assertEquals(Int.MAX_VALUE.toString(), boundaries[3].toTraceJson().getValue("stdoutLines").jsonPrimitive.content)
        assertEquals(Int.MAX_VALUE.toString(), boundaries[6].toTraceJson().getValue("stderrLines").jsonPrimitive.content)
    }

    private fun invalidEvents(): List<RuntimeTraceEvent> = buildList {
        for (time in listOf(-1L, Long.MIN_VALUE, -42L)) {
            add(started().copy(atEpochMillis = time)); add(dispatch().copy(atEpochMillis = time))
            add(output().copy(atEpochMillis = time)); add(completed().copy(atEpochMillis = time))
            add(failed().copy(atEpochMillis = time)); add(closed().copy(atEpochMillis = time))
        }
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            add(dispatch().copy(dispatchId = id)); add(output().copy(dispatchId = id))
            add(completed().copy(dispatchId = id)); add(failed().copy(dispatchId = id))
        }
        for (sequence in listOf(-1, Int.MIN_VALUE)) add(output().copy(sequence = sequence))
        for (elapsed in listOf(-1L, Long.MIN_VALUE)) {
            add(completed().copy(elapsedMillis = elapsed)); add(failed().copy(elapsedMillis = elapsed))
        }
        for (count in listOf(-1, Int.MIN_VALUE)) {
            add(completed().copy(stdoutLines = count)); add(completed().copy(stderrLines = count))
            add(failed().copy(stdoutLines = count)); add(failed().copy(stderrLines = count))
        }
        for ((stdout, stderr) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE)) {
            add(completed().copy(stdoutLines = stdout, stderrLines = stderr))
            add(failed().copy(stdoutLines = stdout, stderrLines = stderr))
        }
    }

    private fun started() = RuntimeSessionStarted("session", "sh", 1, 7, listOf("bash", ""))
    private fun dispatch() = RuntimeDispatchStarted("session", "sh", 1, 1, null, "", inputSessionIds = listOf("owner"))
    private fun output() = RuntimeDispatchOutput("session", "sh", 1, 1, 0, RuntimeStream.Stdout, "ready")
    private fun completed() = RuntimeDispatchCompleted("session", "sh", 1, 1, 0, 0, false, false, false, false, 0, 0)
    private fun failed() = RuntimeDispatchFailed("session", "sh", 1, 1, 0, "Failure", null, 0, 0)
    private fun closed() = RuntimeSessionClosed("session", "sh", 1, 7, false)
}
