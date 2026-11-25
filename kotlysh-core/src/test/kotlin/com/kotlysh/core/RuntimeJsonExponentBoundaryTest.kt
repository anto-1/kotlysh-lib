@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*

@Timeout(30)
class RuntimeJsonExponentBoundaryTest {
    private val hugeExponent = "9".repeat(2_000)
    private val finite = listOf(
        "0e2147483648", "-0e2147483648", "1e-2147483648", "-1e-2147483648",
        "0.0E+2147483648", "-0.0E+2147483648",
        "0e$hugeExponent", "-0e$hugeExponent", "1e-$hugeExponent", "-1e-$hugeExponent",
    )

    @Test
    fun `Python accepts finite wide exponent bindings and stores without losing zero signs`(): Unit = runBlocking {
        verifyFinite(RuntimeSpecs.Python)
    }

    @Test
    fun `Node accepts finite wide exponent bindings and stores without losing zero signs`(): Unit = runBlocking {
        verifyFinite(RuntimeSpecs.Node)
    }

    private suspend fun verifyFinite(spec: RuntimeSpec) {
        runtime(spec) {
            val scope = structured()
            val sign = if (spec.name == "py") "__import__('math').copysign(1.0, payload) < 0" else "Object.is(payload, -0)"
            for (literal in finite) {
                val input = JsonUnquotedLiteral(literal)
                assertEquals(literal.startsWith('-'), scope.eval<Boolean>(sign, "payload" to jsonValue(input)), literal)
                assertTrue(scope.evaluateJson("payload", "payload" to jsonValue(input)).value.jsonPrimitive.double == 0.0)
                scope.storeJson(input) { reference ->
                    assertEquals(literal.startsWith('-'), scope.eval<Boolean>(sign, "payload" to reference), literal)
                    assertTrue(scope.readJson(reference).jsonPrimitive.double == 0.0)
                }
            }
            assertEquals(42, scope.eval<Int>("42"))
            assertTrue(runtime.isOpen)
        }
    }

    @Test
    fun `wide exponent overflow still fails locally and leaves native sessions usable`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
                runtime(spec) {
                    val scope = structured()
                    for (literal in listOf("1e2147483648", "-1e2147483648", "1e$hugeExponent", "-1e$hugeExponent")) {
                        val input = JsonUnquotedLiteral(literal)
                        assertFailsWith<IllegalArgumentException> { scope.storeJson(input) {} }
                        assertFailsWith<IllegalArgumentException> { scope.evaluateJson("payload", "payload" to jsonValue(input)) }
                    }
                    assertEquals(42, scope.eval<Int>("42"))
                    assertTrue(runtime.isOpen)
                }
            }
        }
        assertEquals(listOf("eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    @Test
    fun `generic adapters retain valid number spellings beyond JVM decimal scale limits`(): Unit = runBlocking {
        for (literal in finite + "1e$hugeExponent") {
            runtime(adapter(literal) { request ->
                if (request["op"]?.jsonPrimitive?.content == "eval") {
                    assertEquals(literal, request.getValue("bindings").jsonObject.getValue("payload")
                        .jsonObject.getValue("json").jsonPrimitive.content)
                }
            }) {
                val result = structured().evaluateJson("payload", "payload" to jsonValue(JsonUnquotedLiteral(literal)))
                assertEquals(literal, result.value.jsonPrimitive.content)
                assertTrue(runtime.isOpen)
            }
        }
    }

    @Test
    fun `finite wide exponent snapshots copy into native sessions with explicit lineage`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(adapter(finite.joinToString(",", "[", "]"))) {
                val source = runtime
                val scope = structured()
                scope.hold("remote_numeric_value") { reference ->
                    for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
                        runtime(spec) {
                            val target = runtime
                            val native = structured()
                            native.copy(reference) { copied ->
                                val signs = if (spec.name == "py") {
                                    "[__import__('math').copysign(1.0, item) < 0 for item in items]"
                                } else "items.map(item => Object.is(item, -0))"
                                assertEquals(finite.map { it.startsWith('-') }, native.eval<List<Boolean>>(signs, "items" to copied))
                                assertEquals(finite.size, native.readJson(copied).jsonArray.size)
                            }
                            val store = trace.events.filterIsInstance<RuntimeDispatchStarted>().single {
                                it.sessionId == target.sessionId && it.operation == "store"
                            }
                            assertEquals(listOf(source.sessionId), store.inputSessionIds)
                            assertEquals(42, native.eval<Int>("42"))
                            assertTrue(target.isOpen)
                        }
                    }
                    assertEquals(finite, scope.readJson(reference).jsonArray.map { it.jsonPrimitive.content })
                }
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    @Test
    fun `overflowing copied snapshots never create partial native values or lineage`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            runtime(adapter("[1e$hugeExponent]")) {
                val scope = structured()
                scope.hold("remote_numeric_value") { reference ->
                    for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
                        runtime(spec) {
                            val target = runtime
                            val native = structured()
                            assertFailsWith<IllegalArgumentException> { native.copy(reference) { fail("Overflow entered the body") } }
                            assertTrue(trace.events.filterIsInstance<RuntimeDispatchStarted>().none {
                                it.sessionId == target.sessionId && it.operation == "store"
                            })
                            assertEquals(42, native.eval<Int>("42"))
                            assertTrue(target.isOpen)
                        }
                    }
                    assertEquals("1e$hugeExponent", scope.readJson(reference).jsonArray.single().jsonPrimitive.content)
                }
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    private fun RuntimeScope.structured() = StructuredRuntimeScope(coroutineContext, runtimeContext)

    private fun adapter(json: String, observe: (JsonObject) -> Unit = {}): RuntimeSpec = RuntimeSpec(
        "wide-json-source", RuntimeSpecs.Bash.command, wrap = { _, marker -> frame(marker, "{\"version\":1,\"kind\":\"unit\"}") },
        requestWrapper = { request, marker ->
            observe(request)
            val payload = when (request.getValue("op").jsonPrimitive.content) {
                "hold", "store" -> "{\"version\":1,\"kind\":\"ref\",\"id\":\"remote-value\"}"
                "eval", "get" -> "{\"version\":1,\"kind\":\"value\",\"value\":$json}"
                else -> "{\"version\":1,\"kind\":\"unit\"}"
            }
            frame(marker, payload)
        },
    )

    private fun frame(marker: String, json: String): String {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "printf '%s\\n' '$marker:0:$encoded'; printf '%s\\n' '$marker:0' >&2\n"
    }
}
