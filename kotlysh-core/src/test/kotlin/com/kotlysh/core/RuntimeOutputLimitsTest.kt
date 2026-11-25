package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeOutputLimitsTest {
    @Test
    fun `output limits require positive finite budgets`() {
        for (invalid in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { RuntimeOutputLimits(maxLinesPerDispatch = invalid) }
            assertFailsWith<IllegalArgumentException> { RuntimeOutputLimits(maxCharactersPerDispatch = invalid) }
        }
    }

    @Test
    fun `default character cap is the same without tracing with tracing and in exec`(): Unit = runBlocking {
        val plain = py { "print('x' * 1100000)"() }
        val traced = withRuntimeTrace(RuntimeTraceBuffer()) { py { "print('x' * 1100000)"() } }
        val once = exec(listOf("python3", "-c", "print('x' * 1100000)"))
        for (result in listOf(plain, traced)) {
            assertTrue(result.outputLimited)
            assertFalse(result.ok)
            assertFalse(result.timedOut)
            assertEquals(1_000_000, result.stdoutText.length)
        }
        assertTrue(once.outputLimited)
        assertFalse(once.ok)
        assertFalse(once.timedOut)
        assertEquals(plain.stdout, traced.stdout)
        assertEquals(plain.stdout, once.stdout)
    }

    @Test
    fun `default line cap applies to empty lines in untraced dispatch and exec`(): Unit = runBlocking {
        val dispatch = py { "print('\\n' * 10000)"() }
        val once = exec(listOf("python3", "-c", "print('\\n' * 10000)"))
        assertTrue(dispatch.outputLimited)
        assertTrue(once.outputLimited)
        assertEquals(List(10_000) { "" }, dispatch.stdout)
        assertEquals(dispatch.stdout, once.stdout)
    }

    @Test
    fun `explicit output policy survives either nesting order with legacy trace limits`(): Unit = runBlocking {
        val limits = RuntimeOutputLimits(maxCharactersPerDispatch = 6)
        val traceLimits = RuntimeTraceLimits(maxOutputCharactersPerDispatch = 2)
        val outerPolicy = withRuntimeOutputLimits(limits) {
            withRuntimeTrace(RuntimeTraceBuffer(), traceLimits) { py { "print('abcdef')"() } }
        }
        val innerPolicy = withRuntimeTrace(RuntimeTraceBuffer(), traceLimits) {
            withRuntimeOutputLimits(limits) { py { "print('abcdef')"() } }
        }
        for (result in listOf(outerPolicy, innerPolicy)) {
            assertTrue(result.ok)
            assertEquals(listOf("abcdef"), result.stdout)
        }
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 2)) {
            withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 100)) {
                assertTrue(py { "print('abc')"() }.outputLimited)
                assertTrue(exec(listOf("python3", "-c", "print('abc')")).outputLimited)
            }
        }
    }

    @Test
    fun `scoped policy can raise defaults and is inherited by child exec calls`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 1_200_000)) {
            withRuntimeTrace(RuntimeTraceBuffer()) {
                val result = async { exec(listOf("python3", "-c", "print('x' * 1100000)")) }.await()
                assertTrue(result.ok)
                assertEquals(1_100_000, result.stdoutText.length)
                assertTrue(py { "print('x' * 1100000)"() }.ok)
            }
        }
    }

    @Test
    fun `each dispatch gets a fresh budget and an opened session keeps its policy`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 2, maxCharactersPerDispatch = 6)) {
            sh {
                repeat(2) {
                    val result = "printf 'abc\\n'; printf 'def\\n' >&2"()
                    assertTrue(result.ok)
                    assertEquals(listOf("abc"), result.stdout)
                    assertEquals(listOf("def"), result.stderr)
                }
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 100)) {
                    val result = "printf abcdefg; sleep 30"()
                    assertTrue(result.outputLimited)
                    assertFalse(result.timedOut)
                    assertEquals(listOf("abcdef"), result.stdout)
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive)
                }
            }
        }
    }

    @Test
    fun `small untraced output budgets do not restrict typed responses`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1, maxCharactersPerDispatch = 3)) {
            py {
                assertEquals("x".repeat(20_000), eval<String>("'x' * 20000"))
                assertTrue("print('abc')"().ok)
            }
            node {
                assertEquals("x".repeat(20_000), eval<String>("'x'.repeat(20000)"))
                assertTrue("console.log('abc')"().ok)
            }
        }
    }

    @Test
    fun `two unfinished streams share their character budget before a deadline`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 3)) {
            for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node, RuntimeSpecs.Ruby)) {
                val result = runtime(spec) { splitOutput(spec, "ab", "cd")(timeout = 3.seconds) }
                assertTrue(result.outputLimited, "${spec.name}: $result")
                assertFalse(result.timedOut)
                assertFalse(result.idleTimedOut)
                assertEquals(3, result.stdout.sumOf { it.length } + result.stderr.sumOf { it.length })
            }
        }
    }

    @Test
    fun `a completed line enforces the remaining budget of an already quiet peer`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxCharactersPerDispatch = 3)) {
            for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node, RuntimeSpecs.Ruby)) {
                val result = runtime(spec) { splitOutput(spec, "e", "abc\\n")(timeout = 3.seconds) }
                assertTrue(result.outputLimited, "${spec.name}: $result")
                assertFalse(result.timedOut)
                assertFalse(result.idleTimedOut)
                assertTrue(result.stdout.isEmpty())
                assertEquals(listOf("abc"), result.stderr)
            }
        }
    }

    @Test
    fun `two unfinished streams consume separate lines before either newline`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 1)) {
            for (spec in listOf(RuntimeSpecs.Bash, RuntimeSpecs.Python, RuntimeSpecs.Node, RuntimeSpecs.Ruby)) {
                val result = runtime(spec) { splitOutput(spec, "a", "b")(timeout = 3.seconds) }
                assertTrue(result.outputLimited, "${spec.name}: $result")
                assertFalse(result.timedOut)
                assertFalse(result.idleTimedOut)
                assertEquals(listOf("a"), result.stdout)
                assertTrue(result.stderr.isEmpty())
            }
        }
    }

    private fun splitOutput(spec: RuntimeSpec, stdout: String, stderr: String): String = when (spec.name) {
        "sh" -> "printf '$stdout'; sleep 0.1; printf '$stderr' >&2; sleep 30"
        "py" -> "import sys, time; sys.stdout.write('$stdout'); sys.stdout.flush(); time.sleep(0.1); sys.stderr.write('$stderr'); sys.stderr.flush(); time.sleep(30)"
        "node" -> "process.stdout.write('$stdout'); new Promise(resolve => setTimeout(() => { process.stderr.write('$stderr'); setTimeout(resolve, 30000); }, 100));"
        "ruby" -> "STDOUT.write(\"$stdout\"); STDOUT.flush; sleep(0.1); STDERR.write(\"$stderr\"); STDERR.flush; sleep(30)"
        else -> error("Unexpected runtime: ${spec.name}")
    }
}
