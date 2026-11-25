@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(30)
class RuntimeNumberPolicyTest {
    @Test
    fun `renamed Node retains local numeric validation when its request wrapper is decorated`(): Unit = runBlocking {
        var wrapped = 0
        val original = RuntimeSpecs.Node
        val renamed = original.copy(name = "custom-node", requestWrapper = { request, marker ->
            wrapped++
            original.requestWrapper!!(request, marker)
        })
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(renamed) {
                val typed = structured()
                for (literal in listOf("9007199254740993", "9007199254740991.5", "1e309")) {
                    val nested = buildJsonObject { put("items", buildJsonArray { add(JsonUnquotedLiteral(literal)) }) }
                    assertFailsWith<IllegalArgumentException> { typed.storeJson(nested) { fail("Invalid store entered its body") } }
                    assertFailsWith<IllegalArgumentException> { typed.evaluateJson("payload", "payload" to jsonValue(nested)) }
                }
                assertEquals(0, wrapped)
                assertEquals(42, typed.eval<Int>("42"))
                assertEquals(1, wrapped)
                assertTrue(runtime.isOpen)
            }
        }
        assertEquals(listOf("eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
        assertHealthyClosed(trace, "custom-node")
    }

    @Test
    fun `renamed Python preserves local float rejection and exact integer transfers`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(RuntimeSpecs.Python.copy(name = "custom-python")) {
                val typed = structured()
                val overflow = JsonUnquotedLiteral("1e309")
                assertFailsWith<IllegalArgumentException> { typed.storeJson(overflow) {} }
                assertFailsWith<IllegalArgumentException> { typed.evaluateJson("payload", "payload" to jsonValue(overflow)) }
                val wide = JsonUnquotedLiteral("123456789012345678901234567890")
                typed.storeJson(wide) { assertEquals(wide, typed.readJson(it)) }
                assertTrue(typed.eval<Boolean>("__import__('math').copysign(1.0, payload) < 0",
                    "payload" to jsonValue(JsonUnquotedLiteral("-0e2147483648"))))
                assertTrue(runtime.isOpen)
            }
        }
        assertHealthyClosed(trace, "custom-python")
    }

    @Test
    fun `Python named node does not acquire JavaScript numeric restrictions`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(RuntimeSpecs.Python.copy(name = "node")) {
                val typed = structured()
                val wide = JsonUnquotedLiteral("123456789012345678901234567890")
                assertEquals(wide, typed.evaluateJson("payload", "payload" to jsonValue(wide)).value)
                typed.storeJson(wide) { assertEquals(wide, typed.readJson(it)) }
                assertTrue(runtime.isOpen)
            }
        }
        assertHealthyClosed(trace, "node")
    }

    @Test
    fun `copy to renamed Node rejects an unsafe integer before a destination dispatch`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                val wide = JsonUnquotedLiteral("123456789012345678901234567890")
                storeJson(wide) { original ->
                    runtime(RuntimeSpecs.Node.copy(name = "copy-node")) {
                        val typed = structured()
                        assertFailsWith<IllegalArgumentException> { typed.copy(original) { fail("Invalid copy entered its body") } }
                        assertTrue(trace.events.filterIsInstance<RuntimeDispatchStarted>().none {
                            it.runtime == "copy-node" && it.operation == "store"
                        })
                        assertEquals(42, typed.eval<Int>("42"))
                        assertTrue(runtime.isOpen)
                    }
                    assertEquals(wide, readJson(original))
                }
            }
        }
        assertHealthyClosed(trace, "py", "copy-node")
    }

    private fun RuntimeScope.structured() = StructuredRuntimeScope(coroutineContext, runtimeContext)

    @Test
    fun `custom specifications choose numeric policy without reserving runtime names`(): Unit = runBlocking {
        val python = RuntimeSpecs.Python
        val generic = RuntimeSpec("node", python.command, python.wrap, python.requestWrapper)
        val wide = JsonUnquotedLiteral("123456789012345678901234567890")
        runtime(generic) {
            val typed = structured()
            assertEquals(wide, typed.evaluateJson("payload", "payload" to jsonValue(wide)).value)
        }
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(generic.copy(jsonNumberPolicy = RuntimeJsonNumberPolicy.JavaScript)) {
                assertFailsWith<IllegalArgumentException> { structured().storeJson(wide) {} }
                assertTrue(runtime.isOpen)
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchStarted>().isEmpty())
        assertHealthyClosed(trace, "node")
    }

    private fun assertHealthyClosed(trace: RuntimeTraceBuffer, vararg names: String) {
        val started = trace.events.filterIsInstance<RuntimeSessionStarted>()
        val closed = trace.events.filterIsInstance<RuntimeSessionClosed>()
        assertEquals(names.toSet(), started.map { it.runtime }.toSet())
        assertEquals(started.map { it.sessionId }.toSet(), closed.map { it.sessionId }.toSet())
        assertTrue(closed.none { it.poisoned })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
    }
}
