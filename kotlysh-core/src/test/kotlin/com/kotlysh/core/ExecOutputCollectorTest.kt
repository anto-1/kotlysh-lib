package com.kotlysh.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(15)
class ExecOutputCollectorTest {
    @Test
    fun everyChunkBoundaryPreservesUnicodeAndAllLineSeparators() {
        val out = "α🙃\r\n\r雪\n\nfinal"
        val err = "café🙂\n\r\nlast\r"
        for (size in 1..maxOf(out.length, err.length)) {
            val collected = ExecOutputCollector(RuntimeOutputLimits()) { _, _ -> }
            for (chunk in out.chunked(size)) collected.consume(RuntimeStream.Stdout, chunk)
            for (chunk in err.chunked(size)) collected.consume(RuntimeStream.Stderr, chunk)
            collected.end(RuntimeStream.Stdout)
            collected.end(RuntimeStream.Stderr)
            assertEquals(listOf("α🙃", "", "雪", "", "final"), collected.output(RuntimeStream.Stdout), "size=$size")
            assertEquals(listOf("café🙂", "", "last"), collected.output(RuntimeStream.Stderr), "size=$size")
            assertFalse(collected.outputLimited)
        }
    }

    @Test
    fun sharedPartialBudgetFlushesStdoutFirstAndPublishesTruncationBeforeDelivery() {
        val delivered = mutableListOf<Pair<RuntimeStream, String>>()
        val limitedAtDelivery = mutableListOf<Boolean>()
        lateinit var collected: ExecOutputCollector
        collected = ExecOutputCollector(RuntimeOutputLimits(10, 3)) { stream, text ->
            delivered += stream to text
            limitedAtDelivery += collected.outputLimited
            assertEquals(listOf(text), collected.output(stream))
        }
        collected.consume(RuntimeStream.Stderr, "cd")
        collected.consume(RuntimeStream.Stdout, "ab")
        assertTrue(collected.outputLimited)
        assertEquals(listOf(RuntimeStream.Stdout to "ab", RuntimeStream.Stderr to "c"), delivered)
        assertEquals(listOf(false, true), limitedAtDelivery)
        collected.end(RuntimeStream.Stdout)
        collected.end(RuntimeStream.Stderr)
        collected.consume(RuntimeStream.Stdout, "later\n")
        assertEquals(2, delivered.size)
    }

    @Test
    fun carriageReturnStateBelongsToEachStreamAndEmptyLinesUseTheSharedLineBudget() {
        val collected = ExecOutputCollector(RuntimeOutputLimits(2, 100)) { _, _ -> }
        collected.consume(RuntimeStream.Stdout, "\r")
        collected.consume(RuntimeStream.Stderr, "\n")
        collected.consume(RuntimeStream.Stdout, "\n")
        assertFalse(collected.outputLimited)
        collected.consume(RuntimeStream.Stderr, "\n")
        assertTrue(collected.outputLimited)
        assertEquals(listOf(""), collected.output(RuntimeStream.Stdout))
        assertEquals(listOf(""), collected.output(RuntimeStream.Stderr))
    }

    @Test
    fun aCompletedPeerLineEnforcesTheBudgetOfAQuietUnfinishedStream() {
        val collected = ExecOutputCollector(RuntimeOutputLimits(1, 100)) { _, _ -> }
        collected.consume(RuntimeStream.Stdout, "unfinished")
        collected.consume(RuntimeStream.Stderr, "complete\n")
        assertTrue(collected.outputLimited)
        assertTrue(collected.output(RuntimeStream.Stdout).isEmpty())
        assertEquals(listOf("complete"), collected.output(RuntimeStream.Stderr))
    }

    @Test
    fun truncatedUnicodeRetainsWholeSurrogatePairsBeforeAThrowingCallback() {
        val failure = IllegalStateException("consumer failed")
        lateinit var collected: ExecOutputCollector
        collected = ExecOutputCollector(RuntimeOutputLimits(10, 3)) { stream, text ->
            assertTrue(collected.outputLimited)
            assertEquals("A😀", text)
            assertEquals(listOf(text), collected.output(stream))
            throw failure
        }
        assertSame(failure, assertFailsWith<IllegalStateException> {
            collected.consume(RuntimeStream.Stdout, "A😀B\n")
        })
        assertTrue(collected.outputLimited)
        assertEquals(listOf("A😀"), collected.output(RuntimeStream.Stdout))
    }

    @Test
    fun emptyLinesRemainAdmissibleAtAnExactCharacterBudget() {
        val collected = ExecOutputCollector(RuntimeOutputLimits(10, 1)) { _, _ -> }
        collected.consume(RuntimeStream.Stdout, "a\n\n\n")
        assertFalse(collected.outputLimited)
        assertEquals(listOf("a", "", ""), collected.output(RuntimeStream.Stdout))
        collected.consume(RuntimeStream.Stderr, "b\n")
        assertTrue(collected.outputLimited)
        assertTrue(collected.output(RuntimeStream.Stderr).isEmpty())
    }

    @Test
    fun normalReaderCompletionFlushesOnePartialLineWithoutPhantomTrailingLines() {
        val collected = ExecOutputCollector(RuntimeOutputLimits()) { _, _ -> }
        collected.consume(RuntimeStream.Stdout, "partial")
        assertTrue(collected.output(RuntimeStream.Stdout).isEmpty())
        collected.consume(RuntimeStream.Stderr, "terminated\r")
        collected.end(RuntimeStream.Stdout)
        collected.end(RuntimeStream.Stderr)
        collected.end(RuntimeStream.Stdout)
        assertEquals(listOf("partial"), collected.output(RuntimeStream.Stdout))
        assertEquals(listOf("terminated"), collected.output(RuntimeStream.Stderr))
    }

    @Test
    fun acceptedOutputPrecedesCallbackFailureAndCancellationRemainsTheCallersFailure() {
        for (failure in listOf(IllegalStateException("consumer failed"), CancellationException("consumer cancelled"))) {
            val collected = ExecOutputCollector(RuntimeOutputLimits()) { _, _ -> throw failure }
            assertSame(failure, assertFailsWith<Exception> { collected.consume(RuntimeStream.Stdout, "retained\n") })
            assertEquals(listOf("retained"), collected.output(RuntimeStream.Stdout))
            assertFalse(collected.outputLimited)
        }
    }

    @Test
    fun concurrentReadersSerializeDeliveryAndKeepTheExactCombinedLineCount() {
        val activeCallbacks = AtomicInteger()
        val delivered = AtomicInteger()
        val collected = ExecOutputCollector(RuntimeOutputLimits(200, 10_000)) { _, _ ->
            try {
                assertEquals(1, activeCallbacks.incrementAndGet())
                Thread.yield()
                delivered.incrementAndGet()
            } finally {
                activeCallbacks.decrementAndGet()
            }
        }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val readers = Executors.newFixedThreadPool(2)
        try {
            val tasks = RuntimeStream.entries.map { stream -> readers.submit {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(100) {
                    collected.consume(stream, it.toString())
                    collected.consume(stream, "\n")
                }
                collected.end(stream)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertFalse(collected.outputLimited)
            for (stream in RuntimeStream.entries) assertEquals((0 until 100).map(Int::toString), collected.output(stream))
            assertEquals(200, delivered.get())
            assertEquals(0, activeCallbacks.get())
            collected.consume(RuntimeStream.Stderr, "overflow\n")
            assertTrue(collected.outputLimited)
            assertEquals(200, delivered.get())
        } finally {
            start.countDown()
            readers.shutdownNow()
            assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun outputSnapshotsAreDetachedAndTheirCollectionViewsAreReadOnly() {
        val collected = ExecOutputCollector(RuntimeOutputLimits()) { _, _ -> }
        collected.consume(RuntimeStream.Stdout, "first\n")
        val snapshot = collected.output(RuntimeStream.Stdout)
        collected.consume(RuntimeStream.Stdout, "second\n")
        assertEquals(listOf("first"), snapshot)
        assertEquals(listOf("first", "second"), collected.output(RuntimeStream.Stdout))
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableList<String>).clear() }
    }
}
