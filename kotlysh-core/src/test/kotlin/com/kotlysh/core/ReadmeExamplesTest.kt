package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The README's examples, kept compiling and behaving as written. */
@Timeout(60)
class ReadmeExamplesTest {
    @Test
    fun `Python returns values with their dispatch and keeps live objects`(): Unit = runBlocking {
        val longest = py {
            "print('measuring')"()
            evaluate<String>("max(words, key=len)", "words" to value(listOf("sh", "python", "kotlin")))
        }
        assertEquals("python", longest.value)
        assertTrue(longest.execution.ok)

        val (top, distinct) = py {
            "import collections"().orThrow()
            hold("collections.Counter(words)", "words" to value(listOf("a", "b", "a", "c", "a"))) { counter ->
                val top = eval<String>("counter.most_common(1)[0][0]", "counter" to counter)
                val distinct = eval<Int>("len(counter)", "counter" to counter)
                top to distinct
            }
        }
        assertEquals("a" to 3, top to distinct)
    }

    @Test
    fun `a custom runtime is a command and a completion wrapper`(): Unit = runBlocking {
        val posix = RuntimeSpec(
            name = "posix",
            command = listOf("/bin/sh", "-s"),
            wrap = { script, marker -> "$script\nprintf '%s:%s\\n' '$marker' \"\$?\" | tee /dev/stderr\n" },
        )

        val shell = runtime(posix) { "echo \"running in \$0\""().orThrow().stdoutText }
        assertTrue(shell.startsWith("running in "), shell)
        runtime(posix) {
            assertEquals(3, "(exit 3)"().exitCode)
            "kept=state"().orThrow()
            assertEquals("state", "echo \$kept"().orThrow().stdoutText)
            assertEquals("untouched", "echo \"\${status:-untouched}\""().orThrow().stdoutText)
        }
    }

    @Test
    fun `a session keeps its state and returns the block result`(): Unit = runBlocking {
        val where = sh {
            "cd /tmp"()
            "export NAME=kotlysh"()
            "echo \"\$NAME in \$PWD\""().stdoutText
        }
        assertEquals("kotlysh in /tmp", where)
    }

    @Test
    fun `failures are inspected or thrown explicitly`(): Unit = runBlocking {
        sh {
            val result = "ls /definitely/missing"()
            assertFalse(result.ok)
            assertEquals(2, result.exitCode)
            assertTrue(result.stderrText.isNotEmpty())

            val failure = assertFailsWith<RuntimeCallException> { "ls /definitely/missing"().orThrow() }
            assertEquals(2, failure.execution.exitCode)
            assertEquals("still here", "echo still here"().orThrow().stdoutText)
        }
    }

    @Test
    fun `typed values cross the boundary as data`(): Unit = runBlocking {
        val total: Int = py {
            "import statistics"()
            eval<Int>("sum(items)", "items" to value(listOf(2, 3, 5)))
        }
        assertEquals(10, total)

        val doubled = node {
            evaluate<Int>("(console.log('working'), input * 2)", "input" to value(21))
        }
        assertEquals(42, doubled.value)
        assertEquals(listOf("working"), doubled.execution.stdout)
    }

    @Test
    fun `foreign objects stay in their interpreter behind references`(): Unit = runBlocking {
        val (largest, copied) = py {
            hold("sorted(items)", "items" to value(listOf(5, 2, 3))) { sorted ->
                val largest = eval<Int>("data[-1]", "data" to sorted)
                val copied = node {
                    copy(sorted) { array -> eval<Int>("array.length", "array" to array) }
                }
                largest to copied
            }
        }
        assertEquals(5, largest)
        assertEquals(3, copied)
    }

    @Test
    fun `literal helpers encode data placed in source`(): Unit = runBlocking {
        val text = "O'Reilly's \$HOME"
        assertEquals(text, sh { "printf '%s' ${shLiteral(text)}"().stdoutText })
        assertEquals(text, py { "print(${pythonLiteral(text)}, end='')"().stdoutText })
        assertEquals(text, node { "process.stdout.write(${jsLiteral(text)})"().stdoutText })
    }

    @Test
    fun `sessions run in parallel under ordinary coroutines`(): Unit = runBlocking {
        val (a, b) = coroutineScope {
            val first = async { sh { "sleep 0.2; echo first"().orThrow().stdoutText } }
            val second = async { py { eval<Int>("6 * 7") } }
            first.await() to second.await()
        }
        assertEquals("first" to 42, a to b)
    }

    @Test
    fun `deadlines and one-shot commands`(): Unit = runBlocking {
        sh {
            val slow = "sleep 5"(timeout = 200.milliseconds)
            assertTrue(slow.timedOut)
        }
        val version = exec(listOf("bash", "-c", "echo one-shot"), timeout = 10.seconds)
        assertTrue(version.ok)
        assertEquals("one-shot", version.stdoutText)
    }

    @Test
    fun `the first tour steps run one command, switch language and take deadlines`(): Unit = runBlocking {
        val changes = sh { "git status --short"().stdoutText }
        assertTrue(changes.length >= 0)

        val version = py {
            "import platform"()
            "print(platform.python_version())"().stdoutText
        }
        assertTrue(version.first().isDigit(), version)

        val bounded = withTimeout(15.minutes) {
            sh { "echo bounded"(timeout = 10.minutes, idleTimeout = 2.minutes).orThrow().stdoutText }
        }
        assertEquals("bounded", bounded)
    }

    @Test
    fun `a pipeline reports its last command until the session sets pipefail`(): Unit = runBlocking {
        sh {
            assertTrue("false | true"().ok)
            "set -o pipefail"()
            assertFalse("false | true"().ok)
        }
    }

    @Test
    fun `a stored Python list is copied into a Node session`(): Unit = runBlocking {
        val sum: Int = py {
            store(listOf(2, 3, 5)) { pythonNumbers ->
                node {
                    copy(pythonNumbers) { nodeNumbers ->
                        eval<Int>("items.reduce((sum, item) => sum + item, 0)", "items" to nodeNumbers)
                    }
                }
            }
        }
        assertEquals(10, sum)
    }

    @Test
    fun `quoting habits carry untrusted data into shell and Python`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kotlysh-readme")
        try {
            Files.writeString(directory.resolve("a.log"), "it's \$5\nnope\nit's \$5 again\n")
            Files.writeString(directory.resolve("b.log"), "nothing\n")
            val pattern = "it's \$5"                      // untrusted data
            val counts = sh {
                "cd ${shLiteral(directory.toString())}"().orThrow()
                """
                for file in *.log; do
                    grep -cF -- ${shLiteral(pattern)} "${'$'}file"
                done
                """().stdout
            }
            assertEquals(listOf("2", "0"), counts)
            val digits = py {
                "import re"().orThrow()
                eval<Int>("len(re.findall(r'\\d+', text))", "text" to value(pattern))
            }
            assertEquals(1, digits)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a trace buffer records each session and dispatch in order`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh { "git status --short"() }
            py { eval<Int>("6 * 7") }
        }
        assertEquals(listOf("sh", "py"), trace.events.filterIsInstance<RuntimeSessionStarted>().map { it.runtime })
        assertEquals(listOf("exec", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
        assertTrue(trace.events.last() is RuntimeSessionClosed)
    }

    @Test
    fun `limits are scoped and a built-in spec can be copied`(): Unit = runBlocking {
        withRuntimeOutputLimits(RuntimeOutputLimits(maxLinesPerDispatch = 5, maxCharactersPerDispatch = 1_000)) {
            sh {
                val limited = "seq 1 100"()
                assertTrue(limited.outputLimited)
                assertEquals(5, limited.stdout.size)
            }
        }
        val size = withRuntimeResponseLimits(
            RuntimeResponseLimits(maxPayloadBytes = 16 * 1024 * 1024, maxNestingDepth = 128),
        ) {
            py { evaluateJson("list(range(1000))").value.jsonArray.size }
        }
        assertEquals(1000, size)

        val python = RuntimeSpecs.Python
        val renamed = python.copy(name = "py312", command = listOf("python3") + python.command.drop(1))
        assertEquals("py312", runtime(renamed) { "print(6 * 7)"().orThrow().runtime })

        val live = RuntimeOutputBuffer()
        sh { "echo out; echo err >&2"(output = live).orThrow() }
        assertEquals(setOf("out", "err"), live.lines.map { it.text }.toSet())
    }
}
