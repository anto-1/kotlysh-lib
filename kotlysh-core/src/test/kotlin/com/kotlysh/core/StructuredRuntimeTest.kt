package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@Timeout(20)
class StructuredRuntimeTest {
    private val text = "O'Reilly \"quoted\" \\ path; \$HOME `text`\r\nUnicode: café 😀\u0000\u2028\u2029"

    @Test
    fun `structured strings retain characters that line output cannot`(): Unit = runBlocking {
        assertEquals(text, py { eval<String>("message", "message" to value(text)) })
        assertEquals(text, node { eval<String>("message", "message" to value(text)) })
    }

    @Test
    fun `nested JSON values survive both runtime boundaries`(): Unit = runBlocking {
        val input = buildJsonObject {
            put("text", text)
            put("enabled", true)
            put("absent", JsonNull)
            put("__proto__", buildJsonObject { put("data", "ordinary key") })
            put("items", buildJsonArray { add(1); add(JsonNull); add(false); add(1.5) })
        }
        val first = py { eval<JsonElement>("payload", "payload" to jsonValue(input)) }
        val second = node { eval<JsonElement>("payload", "payload" to jsonValue(first)) }
        assertEquals(input, second)
    }

    @Test
    fun `typed values have separate diagnostics even without a final newline`(): Unit = runBlocking {
        val python = py {
            evaluate<Map<String, Int>>("(print('progress', end=''), {'answer': 42})[1]")
        }
        assertEquals(mapOf("answer" to 42), python.value)
        assertEquals(listOf("progress"), python.execution.stdout)
        val javascript = node {
            evaluate<Map<String, Int>>("(process.stdout.write('progress'), {answer: 42})")
        }
        assertEquals(mapOf("answer" to 42), javascript.value)
        assertEquals(listOf("progress"), javascript.execution.stdout)
    }

    @Test
    fun `raw scriptlets and expressions use the same Python namespace`(): Unit = runBlocking {
        py {
            "base = 40"()
            assertEquals(42, eval<Int>("base + increment", "increment" to value(2)))
            assertEquals("40", "print(base)"().stdout.first())
        }
    }

    @Test
    fun `bindings are temporary and do not overwrite existing globals`(): Unit = runBlocking {
        py {
            "x = 41"()
            assertEquals(1, eval<Int>("x", "x" to value(1)))
            assertEquals(41, eval<Int>("x"))
            assertFailsWith<RuntimeCallException> { eval<Int>("1 / 0", "x" to value(9)) }
            assertEquals(41, eval<Int>("x"))
        }
        node {
            "const x = 41"()
            assertEquals(1, eval<Int>("x", "x" to value(1)))
            assertEquals(41, eval<Int>("x"))
        }
    }

    @Test
    fun `references preserve identity and mutations without copying`(): Unit = runBlocking {
        py {
            store(listOf(1, 2)) { rows ->
                hold("rows", "rows" to rows) { alias ->
                    assertEquals(3, eval<Int>("(rows.append(item), len(rows))[1]", "rows" to rows, "item" to value(3)))
                    assertEquals(listOf(1, 2, 3), read<List<Int>>(alias))
                }
                assertEquals(listOf(1, 2, 3), read<List<Int>>(rows))
            }
        }
        node {
            store(listOf(1, 2)) { rows ->
                hold("rows", "rows" to rows) { alias ->
                    assertEquals(3, eval<Int>("rows.push(item)", "rows" to rows, "item" to value(3)))
                    assertEquals(listOf(1, 2, 3), read<List<Int>>(alias))
                }
            }
        }
    }

    @Test
    fun `non JSON objects remain useful through references`(): Unit = runBlocking {
        py {
            hold("set([1, 1, 2, 3])") { unique ->
                assertEquals(3, eval<Int>("len(items)", "items" to unique))
                assertFailsWith<RuntimeCallException> { readJson(unique) }
                assertEquals(listOf(1, 2, 3), eval<List<Int>>("sorted(items)", "items" to unique))
            }
        }
        node {
            hold("new Set([1, 1, 2, 3])") { unique ->
                assertEquals(3, eval<Int>("items.size", "items" to unique))
                assertFailsWith<RuntimeCallException> { readJson(unique) }
                assertEquals(listOf(1, 2, 3), eval<List<Int>>("Array.from(items)", "items" to unique))
            }
        }
    }

    @Test
    fun `releasing a Python handle drops the retained object`(): Unit = runBlocking {
        py {
            """
                released = []
                class Probe:
                    def __del__(self):
                        released.append(True)
            """.trimIndent()()
            lateinit var probe: RuntimeRef
            hold("Probe()") { probe = it }
            assertTrue(probe.isReleased)
            assertEquals(1, eval<Int>("len(released)"))
            assertFailsWith<IllegalStateException> { readJson(probe) }
        }
    }

    @Test
    fun `scoped references release after failures and cancellation`(): Unit = runBlocking {
        py {
            lateinit var first: RuntimeRef
            assertFailsWith<IllegalArgumentException> {
                store(listOf(1)) {
                    first = it
                    throw IllegalArgumentException("example failure")
                }
            }
            assertTrue(first.isReleased)
            lateinit var second: RuntimeRef
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50.milliseconds) {
                    store(listOf(2)) {
                        second = it
                        delay(500)
                    }
                }
            }
            assertTrue(second.isReleased)
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `a reference cannot escape the lifetime of its session`(): Unit = runBlocking {
        val reference = py { hold("[1, 2, 3]") { it } }
        assertTrue(reference.isReleased)
        py { assertFailsWith<IllegalStateException> { readJson(reference) } }
    }

    @Test
    fun `cross session reference collisions are rejected before evaluation`(): Unit = runBlocking {
        py {
            hold("[1]") { original ->
                py {
                    hold("[99]") { local ->
                        assertFailsWith<IllegalArgumentException> { eval<Int>("len(rows)", "rows" to original) }
                        assertEquals(listOf(99), read<List<Int>>(local))
                        assertEquals(42, eval<Int>("42"))
                    }
                }
            }
        }
    }

    @Test
    fun `cross runtime copies are independent JSON snapshots`(): Unit = runBlocking {
        py {
            store(listOf(1, 2)) { original ->
                node {
                    copy(original) { copied ->
                        eval<Int>("rows.push(9)", "rows" to copied)
                        assertEquals(listOf(1, 2, 9), read<List<Int>>(copied))
                        py {
                            copy(copied) { roundTrip ->
                                assertEquals(listOf(1, 2, 9), read<List<Int>>(roundTrip))
                            }
                        }
                    }
                }
                assertEquals(listOf(1, 2), read<List<Int>>(original))
            }
        }
    }

    @Test
    fun `Python preserves wide integers and Node rejects lossy transfer`(): Unit = runBlocking {
        py {
            store(Long.MAX_VALUE) { wide ->
                assertEquals(Long.MAX_VALUE, read<Long>(wide))
                node {
                    assertFailsWith<IllegalArgumentException> { copy(wide) { } }
                    assertEquals(42, eval<Int>("42"))
                    hold("9223372036854775807n") { bigint ->
                        assertFailsWith<RuntimeCallException> { readJson(bigint) }
                        assertEquals(Long.MAX_VALUE.toString(), eval<String>("n.toString()", "n" to bigint))
                    }
                }
                assertEquals(Long.MAX_VALUE, read<Long>(wide))
            }
        }
    }

    @Test
    fun `unsupported values fail explicitly without poisoning a session`(): Unit = runBlocking {
        py {
            assertFailsWith<RuntimeCallException> { eval<JsonElement>("float('nan')") }
            assertFailsWith<RuntimeCallException> { eval<JsonElement>("{1: 'non-string key'}") }
            hold("[]") { cyclic ->
                eval<JsonElement>("items.append(items)", "items" to cyclic)
                assertFailsWith<RuntimeCallException> { readJson(cyclic) }
            }
            assertEquals(42, eval<Int>("42"))
        }
        node {
            for (expression in listOf("undefined", "NaN", "[undefined]", "new Date()", "({get x() {return 1}})")) {
                assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `Python response encoding failures leave the session usable`(): Unit = runBlocking {
        py {
            // CPython 3.10.7+ limits decimal conversion of extremely large ints.
            "import sys"()
            if (eval<Boolean>("hasattr(sys, 'set_int_max_str_digits')")) {
                "sys.set_int_max_str_digits(640)"()
                val failure = assertFailsWith<RuntimeCallException> { eval<JsonElement>("10 ** 1000") }
                assertTrue(failure.message.orEmpty().contains("ValueError"))
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `invalid and duplicate binding names fail locally`(): Unit = runBlocking {
        py {
            assertFailsWith<IllegalArgumentException> { eval<Int>("1", "bad-name" to value(1)) }
            assertFailsWith<IllegalArgumentException> { eval<Int>("x", "x" to value(1), "x" to value(2)) }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `type mismatches retain successful execution diagnostics`(): Unit = runBlocking {
        py {
            val failure = assertFailsWith<RuntimeDecodeException> { eval<Int>("['wrong type']") }
            assertTrue(failure.execution.ok)
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `remote errors preserve their type and partial logs`(): Unit = runBlocking {
        py {
            val failure = assertFailsWith<RuntimeCallException> { eval<Int>("(print('before error'), 1 / 0)[1]") }
            assertTrue(failure.message!!.contains("ZeroDivisionError"))
            assertEquals(listOf("before error"), failure.execution.stdout)
            assertEquals(42, eval<Int>("42"))
        }
        node {
            val failure = assertFailsWith<RuntimeCallException> { eval<Int>("Promise.reject(new TypeError('example failure'))") }
            assertTrue(failure.message!!.contains("TypeError: example failure"))
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `concurrent reference updates share the dispatch lock`(): Unit = runBlocking {
        py {
            store(emptyList<Int>()) { rows ->
                val sizes = (1..12).map { n -> async {
                    eval<Int>("(rows.append(n), len(rows))[1]", "rows" to rows, "n" to value(n))
                } }.awaitAll()
                assertEquals((1..12).toList(), sizes.sorted())
                assertEquals((1..12).toList(), read<List<Int>>(rows).sorted())
            }
        }
    }

    @Test
    fun `Node evaluates awaited expressions with structured bindings`(): Unit = runBlocking {
        node {
            assertEquals(42, eval<Int>("await Promise.resolve(n + 1)", "n" to value(41)))
        }
    }

    @Test
    fun `a structured timeout invalidates retained objects`(): Unit = runBlocking {
        py {
            lateinit var reference: RuntimeRef
            println("Task-owned Python interpreter for structured timeout: ${runtime.processId}")
            val failure = assertFailsWith<RuntimeCallException> {
                store(listOf(1, 2, 3)) {
                    reference = it
                    eval<JsonElement>("__import__('time').sleep(10)", timeout = 150.milliseconds)
                }
            }
            assertTrue(failure.execution.timedOut)
            assertTrue(reference.isReleased)
            assertFailsWith<IllegalStateException> { readJson(reference) }
        }
    }
}
