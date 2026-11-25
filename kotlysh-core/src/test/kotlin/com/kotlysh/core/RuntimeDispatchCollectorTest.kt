package com.kotlysh.core

import java.io.IOException
import java.nio.charset.MalformedInputException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeDispatchCollectorTest {
    private val marker = "__KOTLYSH_test__"
    private fun collector(
        output: RuntimeOutputLimits = RuntimeOutputLimits(),
        response: RuntimeResponseLimits = RuntimeResponseLimits(),
        expected: String? = null,
        delivered: (RuntimeStream, String) -> Unit = { _, _ -> },
    ) = RuntimeDispatchCollector("test", marker, output, response, expected, delivered)

    private fun encoded(json: String) = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun everyChunkBoundaryPreservesUnicodeBlankLinesAndCompletionFrames() {
        val out = "out α🙃\r\n\r\n尾$marker:7\n"
        val err = "err café🙂\n\n終$marker:7\n"
        for (size in 1..maxOf(out.length, err.length)) {
            val collected = collector()
            for (chunk in out.chunked(size)) collected.consume(RuntimeStream.Stdout, chunk)
            for (chunk in err.chunked(size)) collected.consume(RuntimeStream.Stderr, chunk)
            assertEquals(listOf("out α🙃", "", "尾"), collected.output(RuntimeStream.Stdout), "size=$size")
            assertEquals(listOf("err café🙂", "", "終"), collected.output(RuntimeStream.Stderr), "size=$size")
            assertEquals(7, collected.exitCode)
            assertTrue(collected.allDone)
            assertTrue(collected.allFinished)
            assertFalse(collected.outputLimited)
        }
    }

    @Test
    fun unfinishedLinesShareTheirBudgetAndFlushInCapturedStreamOrder() {
        val delivered = mutableListOf<Pair<RuntimeStream, String>>()
        val collected = collector(RuntimeOutputLimits(10, 3), delivered = { stream, text -> delivered += stream to text })
        collected.consume(RuntimeStream.Stdout, "ab")
        assertFalse(collected.outputLimited)
        collected.consume(RuntimeStream.Stderr, "cd")
        assertTrue(collected.outputLimited)
        assertEquals(listOf("ab"), collected.output(RuntimeStream.Stdout))
        assertEquals(listOf("c"), collected.output(RuntimeStream.Stderr))
        assertEquals(listOf(RuntimeStream.Stdout to "ab", RuntimeStream.Stderr to "c"), delivered)
        collected.flushIncomplete()
        assertEquals(2, delivered.size)
    }

    @Test
    fun emptyLinesCountTowardTheLimitAndUnicodeTruncationKeepsWholeSurrogatePairs() {
        val lines = collector(RuntimeOutputLimits(2, 100))
        lines.consume(RuntimeStream.Stdout, "\n\n")
        assertFalse(lines.outputLimited)
        lines.consume(RuntimeStream.Stderr, "\n")
        assertTrue(lines.outputLimited)
        assertEquals(listOf("", ""), lines.output(RuntimeStream.Stdout))
        assertTrue(lines.output(RuntimeStream.Stderr).isEmpty())
        val characters = collector(RuntimeOutputLimits(10, 3))
        characters.consume(RuntimeStream.Stdout, "A😀B\n")
        assertTrue(characters.outputLimited)
        assertEquals(listOf("A😀"), characters.output(RuntimeStream.Stdout))
    }

    @Test
    fun incompleteTransportStaysOutOfLogsWhileOrdinarySuffixesSurviveEof() {
        val partial = collector(RuntimeOutputLimits(10, 7))
        partial.consume(RuntimeStream.Stdout, "visible$marker:0:eyJ2")
        assertFalse(partial.outputLimited)
        partial.flushIncomplete()
        assertEquals(listOf("visible"), partial.output(RuntimeStream.Stdout))
        assertNull(partial.response)
        assertNull(partial.exitCode)
        val eof = collector()
        eof.consume(RuntimeStream.Stdout, "ordinary_")
        assertTrue(eof.end(RuntimeStream.Stdout))
        assertTrue(eof.end(RuntimeStream.Stderr))
        assertEquals(listOf("ordinary_"), eof.output(RuntimeStream.Stdout))
        assertTrue(eof.allFinished)
        assertFalse(eof.allDone)
    }

    @Test
    fun structuredPayloadsUseTheirOwnBudgetAndNeverReachOutputCallbacks() {
        val json = "{\"version\":1,\"kind\":\"value\",\"value\":42}"
        val delivered = mutableListOf<String>()
        val collected = collector(RuntimeOutputLimits(10, 6), expected = "value", delivered = { _, text -> delivered += text })
        val frame = "out$marker:0:${encoded(json)}\r\n"
        for (chunk in frame.chunked(3)) collected.consume(RuntimeStream.Stdout, chunk)
        collected.consume(RuntimeStream.Stderr, "err$marker:0")
        assertFalse(collected.end(RuntimeStream.Stderr))
        assertEquals(listOf("out", "err"), delivered)
        assertEquals(json, collected.response.toString())
        assertEquals(0, collected.exitCode)
        assertTrue(collected.allDone)
        assertFalse(collected.outputLimited)
    }

    @Test
    fun malformedDuplicateAndMisplacedResponsesKeepProtocolFailures() {
        val json = "{\"version\":1,\"kind\":\"value\",\"value\":42}"
        val payload = encoded(json)
        val misplaced = collector(expected = "value")
        assertFailsWith<IllegalStateException> { misplaced.consume(RuntimeStream.Stderr, "$marker:0:$payload\n") }
        val duplicate = collector(expected = "value")
        duplicate.consume(RuntimeStream.Stdout, "$marker:0:$payload\n")
        assertFailsWith<IllegalStateException> { duplicate.consume(RuntimeStream.Stdout, "$marker:0:$payload\n") }
        val conflict = collector()
        conflict.consume(RuntimeStream.Stdout, "$marker:7\n")
        assertFailsWith<IllegalStateException> { conflict.consume(RuntimeStream.Stderr, "$marker:8\n") }
        val invalid = collector()
        val invalidUtf8 = Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte()))
        assertFailsWith<MalformedInputException> { invalid.consume(RuntimeStream.Stdout, "$marker:0:$invalidUtf8\n") }
    }

    @Test
    fun payloadAndJsonValidationRejectBeforeUntrustedResponsePublication() {
        val small = collector(response = RuntimeResponseLimits(1))
        assertFailsWith<IllegalStateException> { small.consume(RuntimeStream.Stdout, "visible$marker:0:AAAAA") }
        assertEquals(listOf("visible"), small.output(RuntimeStream.Stdout))
        assertNull(small.response)
        for (json in listOf(
            "{\"version\":1,\"kind\":\"value\",\"value\":1,\"value\":2}",
            "{\"version\":2,\"kind\":\"value\",\"value\":1}",
            "{\"version\":1,\"kind\":\"unit\"}",
        )) {
            val collected = collector(expected = "value")
            assertFailsWith<IllegalStateException>(json) { collected.consume(RuntimeStream.Stdout, "$marker:0:${encoded(json)}\n") }
            assertNull(collected.response)
        }
    }

    @Test
    fun failedReadsKeepTheirOriginalFailureAndRetainOutputBeforeCallbackFailure() {
        val ioFailure = IOException("read failed")
        val callbackFailure = IllegalStateException("consumer failed")
        lateinit var collected: RuntimeDispatchCollector
        collected = collector(delivered = { stream, text ->
            assertEquals(listOf(text), collected.output(stream))
            throw callbackFailure
        })
        collected.consume(RuntimeStream.Stdout, "visible$marker:0:unfinished")
        val thrown = assertFailsWith<IOException> { collected.readFailed(RuntimeStream.Stdout, ioFailure) }
        assertSame(ioFailure, thrown)
        assertEquals(listOf(callbackFailure), thrown.suppressed.toList())
        assertEquals(listOf("visible"), collected.output(RuntimeStream.Stdout))
        assertNull(collected.response)
        assertFalse(collected.allFinished)
    }

    @Test
    fun outputSnapshotsAreDetachedAndCallbackFailuresPreserveTheAcceptedLine() {
        val collected = collector()
        collected.consume(RuntimeStream.Stdout, "first\n")
        val snapshot = collected.output(RuntimeStream.Stdout)
        collected.consume(RuntimeStream.Stdout, "second\n")
        assertEquals(listOf("first"), snapshot)
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableList<String>).clear() }
        val failure = IllegalStateException("consumer failed")
        val rejected = collector(delivered = { _, _ -> throw failure })
        assertSame(failure, assertFailsWith<IllegalStateException> { rejected.consume(RuntimeStream.Stdout, "accepted\n") })
        assertEquals(listOf("accepted"), rejected.output(RuntimeStream.Stdout))
    }
}
