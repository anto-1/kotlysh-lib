@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(20)
class RuntimeJsonNumberBoundaryTest {
    @Test
    fun `Node fractional spellings rounded to unsafe integers fail locally before dispatch`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            node {
                for (literal in listOf(
                    "9007199254740991.5", "-9007199254740991.5", "9007199254740992.1",
                    "-9007199254740992.25", "9007199254740992.00000000000000000001", "9.0071992547409915e15",
                )) {
                    val input = JsonUnquotedLiteral(literal)
                    assertFailsWith<IllegalArgumentException>(literal) { storeJson(input) {} }
                    assertFailsWith<IllegalArgumentException>(literal) { evaluateJson("payload", "payload" to jsonValue(input)) }
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
    }

    @Test
    fun `Node fractions rounded within its safe range and underflow remain valid`(): Unit = runBlocking {
        node {
            for (literal in listOf("9007199254740990.5", "9007199254740991.1", "-9007199254740991.1", "0.25", "1e-324")) {
                val input = JsonUnquotedLiteral(literal)
                val expected = literal.toDouble()
                assertEquals(expected, evaluateJson("payload", "payload" to jsonValue(input)).value.jsonPrimitive.content.toDouble())
                storeJson(input) { assertEquals(expected, readJson(it).jsonPrimitive.content.toDouble()) }
            }
        }
    }

    @Test
    fun `Python float overflow fails locally without admitting a request`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                for (literal in listOf("1e309", "-1e309", "1.0e309", "1.7976931348623159e308")) {
                    val input = JsonUnquotedLiteral(literal)
                    assertFailsWith<IllegalArgumentException>(literal) { storeJson(input) {} }
                    assertFailsWith<IllegalArgumentException>(literal) {
                        evaluateJson("payload", "payload" to jsonValue(input))
                    }
                }
                val nested = JsonObject(mapOf("items" to JsonArray(listOf(JsonUnquotedLiteral("1e309")))))
                assertFailsWith<IllegalArgumentException> { storeJson(nested) {} }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
    }

    @Test
    fun `Python finite float boundary and exact wide integers remain transferable`(): Unit = runBlocking {
        py {
            "import sys; sys.set_int_max_str_digits(0) if hasattr(sys, 'set_int_max_str_digits') else None"()
            for (literal in listOf("1e308", "-1e308", "1.7976931348623157e308", "1e-308", "-0.0")) {
                val input = JsonUnquotedLiteral(literal)
                assertEquals(literal.toDouble(), evaluateJson("payload", "payload" to jsonValue(input)).value.jsonPrimitive.content.toDouble())
                storeJson(input) { assertEquals(literal.toDouble(), readJson(it).jsonPrimitive.content.toDouble()) }
            }
            val integer = "9".repeat(4000)
            storeJson(JsonUnquotedLiteral(integer)) { assertEquals(integer, readJson(it).jsonPrimitive.content) }
        }
    }

    @Test
    fun `Python configured integer digit limit rejects a request without killing the session`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                val hasLimit = eval<Boolean>("hasattr(__import__('sys'), 'set_int_max_str_digits')")
                val integer = "9".repeat(700)
                if (hasLimit) {
                    "import sys; sys.set_int_max_str_digits(640)"()
                    val stored = assertFailsWith<RuntimeCallException> { storeJson(JsonUnquotedLiteral(integer)) {} }
                    assertEquals(1, stored.execution.exitCode)
                    assertFalse(stored.execution.processExited)
                    assertTrue(runtime.isOpen)
                    assertEquals(42, eval<Int>("42"))
                    val bound = assertFailsWith<RuntimeCallException> {
                        evaluateJson("payload", "payload" to jsonValue(JsonUnquotedLiteral(integer)))
                    }
                    assertEquals(1, bound.execution.exitCode)
                    assertFalse(bound.execution.processExited)
                    assertTrue(runtime.isOpen)
                    "sys.set_int_max_str_digits(0)"()
                }
                storeJson(JsonUnquotedLiteral(integer)) { assertEquals(integer, readJson(it).jsonPrimitive.content) }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `Python outbound integer serialization errors preserve references and interpreter limits`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                val hasLimit = eval<Boolean>("hasattr(__import__('sys'), 'set_int_max_str_digits')")
                if (hasLimit) {
                    "import sys; sys.set_int_max_str_digits(640)"()
                    val evaluated = assertFailsWith<RuntimeCallException> { evaluateJson("10**1000") }
                    assertEquals(1, evaluated.execution.exitCode)
                    assertEquals("ValueError", evaluated.execution.response?.get("type")?.jsonPrimitive?.content)
                    assertFalse(evaluated.execution.processExited)
                }
                hold("10**1000") { reference ->
                    if (hasLimit) {
                        val read = assertFailsWith<RuntimeCallException> { readJson(reference) }
                        assertEquals(1, read.execution.exitCode)
                        assertEquals("ValueError", read.execution.response?.get("type")?.jsonPrimitive?.content)
                        assertFalse(read.execution.processExited)
                        assertFalse(reference.isReleased)
                        assertEquals(640, eval<Int>("sys.get_int_max_str_digits()"))
                    } else {
                        assertEquals(BigInteger.TEN.pow(1000).toString(), readJson(reference).jsonPrimitive.content)
                    }
                    val hex = eval<String>("hex(value)", "value" to reference).removePrefix("0x")
                    assertEquals(BigInteger.TEN.pow(1000), BigInteger(hex, 16))
                    assertTrue(runtime.isOpen)
                    assertEquals(42, eval<Int>("42"))
                }
                if (hasLimit) assertEquals(640, eval<Int>("sys.get_int_max_str_digits()"))
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `malformed finite number spellings fail before dispatch and leave sessions usable`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                for (literal in listOf("+1", "01", "-01", ".5", "1.", "+1.5", "00.5")) {
                    val input = JsonUnquotedLiteral(literal)
                    assertFailsWith<IllegalArgumentException>(literal) { storeJson(input) {} }
                    assertFailsWith<IllegalArgumentException>(literal) {
                        evaluateJson("payload", "payload" to jsonValue(input))
                    }
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
    }

    @Test
    fun `malformed number syntax is rejected inside nested containers before dispatch`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val input = JsonObject(mapOf("items" to JsonArray(listOf(JsonUnquotedLiteral("01")))))
                assertFailsWith<IllegalArgumentException> { storeJson(input) {} }
                assertFailsWith<IllegalArgumentException> { evaluateJson("payload", "payload" to jsonValue(input)) }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
    }

    @Test
    fun `standard JSON integer decimal and exponent spellings remain valid`(): Unit = runBlocking {
        eachRuntime {
            for (literal in listOf("0", "-0", "10", "-10", "0.5", "-0.25", "1.0", "1e2", "1E+2", "1E-2")) {
                val input = JsonUnquotedLiteral(literal)
                assertNumericValue(literal, evaluateJson("payload", "payload" to jsonValue(input)).value)
                storeJson(input) { assertNumericValue(literal, readJson(it)) }
            }
        }
    }

    @Test
    fun `strings resembling invalid numeric literals remain ordinary data`(): Unit = runBlocking {
        eachRuntime {
            val input = JsonArray(listOf("+1", "01", ".5", "1.").map(::JsonPrimitive))
            assertEquals(input, evaluateJson("payload", "payload" to jsonValue(input)).value)
            storeJson(input) { assertEquals(input, readJson(it)) }
        }
    }

    private fun assertNumericValue(expected: String, actual: JsonElement) {
        assertEquals(0, expected.toBigDecimal().compareTo(actual.jsonPrimitive.content.toBigDecimal()))
    }

    private suspend fun eachRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }
}
