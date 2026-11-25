package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(20)
class RuntimeDeepJsonRequestTest {
    @Test
    fun `built in request writers serialize deep arrays and objects without a JVM stack overflow`() {
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            for (objects in listOf(false, true)) {
                val input = nested(10_000, objects)
                val line = spec.requestWrapper!!(buildJsonObject { put("op", "store"); put("value", input) }, "marker")
                assertTrue(line.endsWith("\n"))
                assertTrue(line.contains("\"marker\":\"marker\""))
                assertEquals(10_001, line.count { it == if (objects) '{' else '[' } + if (objects) 0 else 1)
            }
        }
    }

    @Test
    fun `deep caller bindings report native depth errors and leave sessions usable`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val input = nested(10_000, false)
                val failure = assertFailsWith<RuntimeCallException> { evaluateJson("payload", "payload" to jsonValue(input)) }
                assertDepthFailure(failure)
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval", "eval", "eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    @Test
    fun `deep stores do not invoke their body or lose the native request marker`(): Unit = runBlocking {
        eachRuntime {
            val data = buildJsonObject {
                put("marker", "a nested decoy")
                put("text", "\"},\"marker\":\"another decoy\",\"value\":{")
                put("deep", nested(10_000, true))
            }
            var invoked = false
            val failure = assertFailsWith<RuntimeCallException> { storeJson(data) { invoked = true } }
            assertFalse(invoked)
            assertDepthFailure(failure)
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `cycles reached through deep containers are rejected locally without a dispatch`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                val backing = mutableListOf<JsonElement>()
                val cycle = JsonArray(backing)
                var input: JsonElement = cycle
                repeat(10_000) { input = JsonArray(listOf(input)) }
                backing += input
                val failure = assertFailsWith<IllegalArgumentException> { storeJson(input) {} }
                assertTrue(failure.message.orEmpty().contains("cyclic"))
                assertTrue(runtime.isOpen)
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
    }

    @Test
    fun `request writers reject cyclic containers instead of looping or overflowing`() {
        val backing = mutableListOf<JsonElement>()
        val input = JsonArray(backing)
        backing.add(input)
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            val failure = assertFailsWith<IllegalArgumentException> {
                spec.requestWrapper!!(buildJsonObject { put("op", "store"); put("value", input) }, "marker")
            }
            assertTrue(failure.message.orEmpty().contains("cyclic"))
        }
    }

    @Test
    fun `request writer keeps escaping member order and repeated aliases exact`() {
        val text = "quote:\" slash:\\ controls:\r\n\u0000 lone:\uD800 pair:😀"
        val shared = JsonObject(linkedMapOf("key $text" to JsonPrimitive(text), "empty" to JsonArray(emptyList())))
        val request = buildJsonObject { put("op", "store"); put("value", JsonArray(listOf(shared, shared))) }
        val expected = JsonObject(request + mapOf("version" to JsonPrimitive(1), "marker" to JsonPrimitive(text)))
            .toString().escapeUnpairedSurrogates() + "\n"
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            assertEquals(expected, spec.requestWrapper!!(request, text))
        }
    }

    @Test
    fun `Python recovers an escaped marker name before a deeply nested request value`(): Unit = runBlocking {
        val specification = RuntimeSpecs.Python.copy(requestWrapper = { request, marker ->
            val reordered = buildJsonObject {
                put("marker", marker)
                put("version", 1)
                request.forEach { (name, value) -> put(name, value) }
            }
            reordered.toJsonWireString().replaceFirst("\"marker\":", "\"\\u006dar\\u006ber\" : ") + "\n"
        })
        runtime(specification) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            val failure = assertFailsWith<RuntimeCallException> { scope.storeJson(nested(10_000, false)) {} }
            scope.assertDepthFailure(failure)
            assertEquals(42, scope.eval<Int>("42"))
        }
    }

    private fun StructuredRuntimeScope.assertDepthFailure(failure: RuntimeCallException) {
        assertEquals(1, failure.execution.exitCode)
        assertFalse(failure.execution.processExited)
        assertFalse(failure.execution.timedOut)
        assertFalse(failure.execution.outputLimited)
        assertEquals(if (runtime.name == "py") "RecursionError" else "RangeError", failure.execution.response!!["type"]!!.jsonPrimitive.content)
        assertTrue(runtime.isOpen)
    }

    private fun nested(depth: Int, objects: Boolean): JsonElement {
        var input: JsonElement = JsonPrimitive(0)
        repeat(depth) { input = if (objects) JsonObject(mapOf("nested" to input)) else JsonArray(listOf(input)) }
        return input
    }

    private suspend fun eachRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }
}
