package com.kotlysh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeTraceBufferSnapshotTest {
    @Test
    fun `reused command lists cannot rewrite earlier publications or their JSONL`() {
        val command = mutableListOf("node", "pair:😀 lone:\uD800", "")
        val buffer = RuntimeTraceBuffer()
        val event = RuntimeSessionStarted("original", "node", 12, 123, command, commandTruncated = true)
        buffer.onEvent(event)
        val first = buffer.events
        val original = event.toTraceJson()

        command.clear()
        command += "changed"
        buffer.onEvent(event.copy(sessionId = "later"))
        command.clear()

        assertEquals(original, first.single().toTraceJson())
        val recorded = buffer.events.filterIsInstance<RuntimeSessionStarted>()
        assertEquals(listOf("node", "pair:😀 lone:\uD800", ""), recorded[0].command)
        assertEquals(listOf("changed"), recorded[1].command)
        assertTrue(recorded.all { it.commandTruncated })
        val lines = mutableListOf<String>()
        RuntimeJsonlTraceSink(lines::add).onEvent(recorded.first())
        assertTrue(lines.single().contains("\\ud800", ignoreCase = true))
        assertEquals("original", recorded[0].sessionId)
        assertEquals(12L, recorded[0].atEpochMillis)
        assertEquals(123L, recorded[0].processId)
    }

    @Test
    fun `reused lineage lists cannot rewrite earlier owners or their JSONL`() {
        val owners = mutableListOf("source", "same-prefix-a", "same-prefix-b", "lone:\uDC00", " padded-owner ")
        val buffer = RuntimeTraceBuffer()
        val event = RuntimeDispatchStarted("target", "node", 13, 7, "hash", "preview", "store", owners)
        buffer.onEvent(event)
        val first = buffer.events
        val original = event.toTraceJson()

        owners.clear()
        owners += "later-owner"
        buffer.onEvent(event.copy(dispatchId = 8))
        owners.clear()

        assertEquals(original, first.single().toTraceJson())
        val recorded = buffer.events.filterIsInstance<RuntimeDispatchStarted>()
        assertEquals(listOf("source", "same-prefix-a", "same-prefix-b", "lone:\uDC00", " padded-owner "), recorded[0].inputSessionIds)
        assertEquals(listOf("later-owner"), recorded[1].inputSessionIds)
        assertEquals("hash", recorded[0].scriptSha256)
        assertEquals("preview", recorded[0].scriptPreview)
        assertEquals("store", recorded[0].operation)
        assertEquals(7L, recorded[0].dispatchId)
    }

    @Test
    fun `observers cannot mutate collected events or their metadata lists`() {
        val buffer = RuntimeTraceBuffer()
        buffer.onEvent(RuntimeSessionStarted("s", "node", 1, 123, mutableListOf("node")))
        buffer.onEvent(RuntimeDispatchStarted("s", "node", 2, 1, null, null, "store", mutableListOf("source")))
        val observed = buffer.events
        val before = observed.map(RuntimeTraceEvent::toTraceJson)
        assertFailsWith<UnsupportedOperationException> { (observed as MutableList<RuntimeTraceEvent>).clear() }
        val started = assertIs<RuntimeSessionStarted>(observed[0])
        val dispatch = assertIs<RuntimeDispatchStarted>(observed[1])
        assertFailsWith<UnsupportedOperationException> { (started.command as MutableList<String>)[0] = "changed" }
        assertFailsWith<UnsupportedOperationException> { (dispatch.inputSessionIds as MutableList<String>).clear() }
        buffer.onEvent(RuntimeSessionClosed("s", "node", 3, 123, false))
        assertEquals(before, observed.map(RuntimeTraceEvent::toTraceJson))
        assertEquals(before, buffer.events.take(2).map(RuntimeTraceEvent::toTraceJson))
        assertEquals(3, buffer.events.size)
    }
}
