package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*

@Timeout(20)
class StructuredResponseEnvelopeTest {
    @Test
    fun `duplicate response fields cannot overwrite envelope facts`(): Unit = runBlocking {
        for (fields in listOf(
            """"version":2,"version":1,"kind":"value","value":7""",
            """"version":1,"kind":"error","kind":"value","value":7""",
            """"version":1,"kind":"value","value":6,"value":7""",
            """"version":1,"kind":"value","value":null,"value":null""",
        )) assertDuplicate("{$fields}")
        assertDuplicate("""{"version":1,"kind":"ref","id":"first","id":"second"}""", operation = "hold")
        assertDuplicate("""{"version":1,"kind":"error","type":"Error","message":"first","message":"second"}""", status = 1)
    }

    @Test
    fun `duplicate fields cannot hide invalid JSON literals`(): Unit = runBlocking {
        for (literal in listOf("NaN", "+1", "undefined")) {
            assertDuplicate("""{"version":1,"kind":"value","value":$literal,"value":7}""")
        }
    }

    @Test
    fun `duplicate keys are rejected in values and unknown metadata`(): Unit = runBlocking {
        for (value in listOf("""{"x":1,"x":2}""", """[{"":null,"":null}]""")) {
            assertDuplicate("""{"version":1,"kind":"value","value":$value}""")
            assertDuplicate("""{"version":1,"kind":"value","value":7,"future":$value}""")
        }
    }

    @Test
    fun `escaped aliases are duplicate decoded keys`(): Unit = runBlocking {
        assertDuplicate("""{"version":1,"kind":"value","value":6,"v\u0061lue":7}""")
        for (value in listOf(
            """{"a":1,"\u0061":2}""",
            """{"😀":1,"\ud83d\ude00":2}""",
            """{"\ud800":1,"\uD800":2}""",
            """{"a/b":1,"a\/b":2}""",
            """{"a\\b":1,"a\u005cb":2}""",
            """{"a\"b":1,"a\u0022b":2}""",
            """{"a\nb":1,"a\u000ab":2}""",
        )) assertDuplicate("""{"version":1,"kind":"value","value":$value}""")
    }

    @Test
    fun `duplicates separated by many nested members cannot evade validation`(): Unit = runBlocking {
        val members = (0 until 2000).joinToString(",") { "\"key$it\":[{},[],{\"same\":$it}]" }
        val response = """{"version":1,"kind":"value","value":null,"future":{$members}}"""
        runtime(adapter { response to 0 }) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            assertEquals(JsonNull, scope.evaluateJson("ignored").value)
            assertTrue(runtime.isOpen)
        }
        assertDuplicate("""{"version":1,"kind":"value","value":null,"future":{$members,"key0":null}}""")
    }

    @Test
    fun `distinct keys and repeated keys in separate objects remain lossless`(): Unit = runBlocking {
        val value = """[{"a":1,"A":2,"é":3,"e\u0301":4,"\ud800":5,"a\\b":6,"a\"b":7,"":8},{"a":9,"":10},"{\"a\":1,\"a\":2}"]"""
        val response = """{"version":1,"kind":"value","value":$value,"future":{"a":10}}"""
        runtime(adapter { response to 0 }) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            repeat(2) {
                assertEquals(Json.parseToJsonElement(value), scope.evaluateJson("ignored").value)
                assertTrue(runtime.isOpen)
            }
        }
    }

    @Test
    fun `non JSON number tokens cannot become successful values or metadata`(): Unit = runBlocking {
        for (number in listOf("+1", "01", "-01", ".5", "1.", "NaN", "Infinity")) {
            assertMalformed("""{"version":1,"kind":"value","value":$number}""")
            assertMalformed("""{"version":1,"kind":"value","value":7,"future":{"number":$number}}""")
        }
        for (version in listOf("+1", "01")) {
            assertMalformed("""{"version":$version,"kind":"value","value":7}""")
        }
    }

    @Test
    fun `non JSON bare literals cannot become successful values`(): Unit = runBlocking {
        for (literal in listOf("TRUE", "False", "NULL", "undefined", "bare")) {
            assertMalformed("""{"version":1,"kind":"value","value":$literal}""")
        }
    }

    @Test
    fun `response documents require JSON field names separators escapes and whitespace`(): Unit = runBlocking {
        val valid = """{"version":1,"kind":"value","value":7}"""
        val invalid = listOf(
            valid.replace("\"version\"", "version"),
            valid.replace("\"value\"", "'value'"),
            valid.replace(",", ";"),
            "/*comment*/$valid",
            "$valid true",
            valid.dropLast(1) + ",}",
            valid.dropLast(1) + ",\"future\":[1,]}",
            valid.dropLast(1) + ",\"future\":\"bad\\'escape\"}",
            "\u000b$valid",
            "$valid\u000c",
        )
        for (response in invalid) assertMalformed(response, parserFailure = true)
    }

    @Test
    fun `unescaped string controls cannot become successful values or metadata`(): Unit = runBlocking {
        for (control in listOf('\u0000', '\t', '\n', '\r', '\u001f')) {
            assertMalformed("""{"version":1,"kind":"value","value":"bad${control}value"}""")
            assertMalformed("""{"version":1,"kind":"value","value":7,"future":{"bad${control}key":true}}""")
        }
    }

    @Test
    fun `valid numeric spellings and escaped controls remain lossless in responses`(): Unit = runBlocking {
        for (number in listOf("0", "-0", "-10", "0.5", "1e+2", "1E-2", "1e2147483648", "9".repeat(4000))) {
            val response = """{"version":1,"kind":"value","value":$number,"future":[true,false,null,{"n":$number}]}"""
            runtime(adapter { response to 0 }) {
                val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                assertEquals(number, scope.evaluateJson("ignored").value.jsonPrimitive.content)
                assertTrue(runtime.isOpen)
            }
        }
        val value = JsonObject(mapOf("key\u0000\t\n\r\u001f" to JsonPrimitive("value\u0000\t\n\r\u001f")))
        val response = buildJsonObject { put("version", 1); put("kind", "value"); put("value", value) }.toString()
        runtime(adapter { response to 0 }) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            assertEquals(value, scope.evaluateJson("ignored").value)
        }
    }


    @Test
    fun `response payload must be a JSON object`(): Unit = runBlocking {
        for (response in listOf("null", "[]", "7", "true", "\"object\"")) {
            assertMalformed(response)
        }
    }

    @Test
    fun `response versions are numeric protocol integers`(): Unit = runBlocking {
        for (version in listOf("\"1\"", "true", "false", "null", "{}", "1.0", "2")) {
            assertMalformed("""{"version":$version,"kind":"value","value":7}""")
        }
        assertMalformed("""{"kind":"value","value":7}""")
    }

    @Test
    fun `response kinds must be recognized JSON strings`(): Unit = runBlocking {
        for (kind in listOf("true", "7", "null", "{}", "[]", "\"unknown\"")) {
            assertMalformed("""{"version":1,"kind":$kind,"value":7}""")
        }
        assertMalformed("""{"version":1,"value":7}""")
    }

    @Test
    fun `value response must contain the value field`(): Unit = runBlocking {
        assertMalformed("""{"version":1,"kind":"value"}""")
    }

    @Test
    fun `reference IDs cannot be coerced from other JSON types`(): Unit = runBlocking {
        for (id in listOf("1", "true", "false", "null", "{}", "[]")) {
            assertMalformed("""{"version":1,"kind":"ref","id":$id}""", operation = "hold")
        }
        assertMalformed("""{"version":1,"kind":"ref"}""", operation = "hold")
    }

    @Test
    fun `error diagnostics must be strings in received envelopes`(): Unit = runBlocking {
        for (field in listOf("type", "message")) {
            for (invalid in listOf("1", "true", "null", "{}", "[]")) {
                val other = if (field == "type") "message" else "type"
                assertMalformed("""{"version":1,"kind":"error","$field":$invalid,"$other":"valid"}""", status = 1)
            }
        }
        assertMalformed("""{"version":1,"kind":"error","message":"valid"}""", status = 1)
        assertMalformed("""{"version":1,"kind":"error","type":"valid"}""", status = 1)
    }

    @Test
    fun `response kind must agree with completion status`(): Unit = runBlocking {
        assertMalformed("""{"version":1,"kind":"error","type":"Error","message":"failed"}""")
        for (response in listOf(
            """{"version":1,"kind":"unit"}""",
            """{"version":1,"kind":"value","value":7}""",
            """{"version":1,"kind":"ref","id":"object"}""",
        )) {
            assertMalformed(response, status = 7)
        }
    }

    @Test
    fun `successful structured responses match their requested operation`(): Unit = runBlocking {
        val value = """{"version":1,"kind":"value","value":7}"""
        val reference = """{"version":1,"kind":"ref","id":"object"}"""
        val unit = """{"version":1,"kind":"unit"}"""
        assertMalformed(reference, operation = "eval")
        assertMalformed(value, operation = "hold")
        assertMalformed(value, operation = "store")
        assertMalformed(unit, operation = "get")
        assertMalformed(reference, operation = "release")
        assertMalformed(value, operation = "exec")
    }

    @Test
    fun `successful structured request requires a response payload`(): Unit = runBlocking {
        assertMalformed(null)
    }

    @Test
    fun `JSON null and unknown metadata remain valid value responses`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val response = """{"version":1,"kind":"value","value":null,"future":{"flag":true}}"""
        withRuntimeTrace(trace) {
            runtime(adapter { response to 0 }) {
                val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                repeat(2) {
                    val result = scope.evaluateJson("ignored")
                    assertEquals(JsonNull, result.value)
                    assertTrue(result.execution.ok)
                    assertTrue(runtime.isOpen)
                }
            }
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `opaque string reference IDs remain unchanged including empty IDs`(): Unit = runBlocking {
        for (id in listOf("", "opaque \"id\"\\\r\n\u0000😀")) {
            val observed = mutableListOf<String>()
            runtime(adapter { request ->
                val operation = request["op"]!!.jsonPrimitive.content
                when (operation) {
                    "get", "release" -> {
                        val sent = request["id"]!!.jsonPrimitive
                        assertTrue(sent.isString)
                        observed += sent.content
                    }
                    "eval" -> {
                        val sent = request["bindings"]!!.jsonObject["item"]!!.jsonObject["ref"]!!.jsonPrimitive
                        assertTrue(sent.isString)
                        observed += sent.content
                    }
                }
                standardResponse(operation, id) to 0
            }) {
                val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                scope.hold("ignored") { reference ->
                    assertEquals(JsonPrimitive(42), scope.readJson(reference))
                    assertEquals(42, scope.eval<Int>("item", "item" to reference))
                }
            }
            assertEquals(List(3) { id }, observed)
        }
    }

    @Test
    fun `valid failed dispatches retain logs and leave the adapter usable`(): Unit = runBlocking {
        for (response in listOf(null, """{"version":1,"kind":"error","type":"RemoteError","message":"original message"}""")) {
            var first = true
            val trace = RuntimeTraceBuffer()
            withRuntimeTrace(trace) {
                runtime(adapter { request ->
                    if (first) {
                        first = false
                        response to 7
                    } else standardResponse(request["op"]!!.jsonPrimitive.content) to 0
                }) {
                    val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                    val failure = assertFailsWith<RuntimeCallException> { scope.evaluateJson("ignored") }
                    assertEquals(7, failure.execution.exitCode)
                    assertEquals(listOf("visible"), failure.execution.stdout)
                    assertEquals(listOf("diagnostic"), failure.execution.stderr)
                    assertTrue(failure.message.orEmpty().contains(if (response == null) "diagnostic" else "RemoteError: original message"))
                    assertTrue(runtime.isOpen)
                    assertEquals(42, scope.eval<Int>("next"))
                }
            }
            assertEquals(listOf(7, 0), trace.events.filterIsInstance<RuntimeDispatchCompleted>().map { it.exitCode })
            assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
            assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        }
    }

    @Test
    fun `marker only raw adapters retain success and failure results`(): Unit = runBlocking {
        var first = true
        val trace = RuntimeTraceBuffer()
        val spec = RuntimeSpec("marker-only", RuntimeSpecs.Bash.command) { _, marker ->
            frame(marker, null, if (first) 7.also { first = false } else 0)
        }
        withRuntimeTrace(trace) {
            runtime(spec) {
                val failure = "ignored"()
                assertEquals(7, failure.exitCode)
                assertFalse(failure.ok)
                assertTrue(runtime.isOpen)
                val success = "ignored"()
                assertTrue(success.ok)
                assertEquals(listOf("visible"), success.stdout)
                assertEquals(listOf("diagnostic"), success.stderr)
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `caller constructed malformed diagnostics cannot replace a RuntimeCallException`(): Unit = runBlocking {
        for (invalid in listOf<JsonElement>(JsonObject(emptyMap()), JsonArray(emptyList()), JsonPrimitive(7), JsonPrimitive(true), JsonNull)) {
            val execution = RuntimeResult(
                runtime = "constructed", stdout = listOf("out"), stderr = listOf("preserved diagnostic"), exitCode = 7,
                response = buildJsonObject { put("type", invalid); put("message", invalid) },
            )
            val failure = RuntimeCallException(execution)
            assertSame(execution, failure.execution)
            assertTrue(failure.message.orEmpty().contains("preserved diagnostic"))
        }
    }

    private suspend fun assertDuplicate(response: String, status: Int = 0, operation: String = "eval") =
        assertMalformed(response, status, operation, expectedMessage = "Duplicate JSON object key")

    private suspend fun assertMalformed(
        response: String?, status: Int = 0, operation: String = "eval", parserFailure: Boolean = false,
        expectedMessage: String? = null,
    ) {
        val trace = RuntimeTraceBuffer()
        var returnedHandle = false
        withRuntimeTrace(trace) {
            runtime(adapter { request ->
                val current = request["op"]!!.jsonPrimitive.content
                if (current == operation) response to status else standardResponse(current) to 0
            }) {
                val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                val failure = assertFails {
                    when (operation) {
                        "eval" -> scope.evaluateJson("ignored")
                        "hold" -> scope.hold("ignored") { returnedHandle = true }
                        "store" -> scope.storeJson(JsonNull) { returnedHandle = true }
                        "get" -> scope.hold("ignored") { scope.readJson(it) }
                        "release" -> scope.hold("ignored") {
                            runtime.request(buildJsonObject { put("op", "release"); put("id", it.id) })
                        }
                        "exec" -> runtime.request(buildJsonObject { put("op", "exec"); put("code", "") })
                        else -> error("Unknown test operation")
                    }
                }
                if (parserFailure) {
                    assertTrue(failure is SerializationException || failure is IllegalStateException)
                } else assertIs<IllegalStateException>(failure)
                if (expectedMessage != null) assertTrue(failure.message.orEmpty().contains(expectedMessage))
                assertFalse(returnedHandle)
                assertFalse(runtime.isOpen)
                assertFalse(process.isAlive)
                assertFailsWith<IllegalStateException> { scope.evaluateJson("next") }
            }
        }
        val dispatch = trace.events.filterIsInstance<RuntimeDispatchStarted>().single { it.operation == operation }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().none { it.dispatchId == dispatch.dispatchId })
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().count { it.dispatchId == dispatch.dispatchId })
        assertEquals(listOf("visible"), trace.events.filterIsInstance<RuntimeDispatchOutput>()
            .filter { it.dispatchId == dispatch.dispatchId && it.stream == RuntimeStream.Stdout }.map { it.text })
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    private fun adapter(response: (JsonObject) -> Pair<String?, Int>): RuntimeSpec = RuntimeSpec(
        "envelope-test", RuntimeSpecs.Bash.command,
        wrap = { _, marker -> frame(marker, standardResponse("exec"), 0) },
        requestWrapper = { request, marker ->
            val (payload, status) = response(request)
            frame(marker, payload, status)
        },
    )

    private fun standardResponse(operation: String, id: String = "object"): String = buildJsonObject {
        put("version", 1)
        when (operation) {
            "hold", "store" -> { put("kind", "ref"); put("id", id) }
            "eval", "get" -> { put("kind", "value"); put("value", 42) }
            else -> put("kind", "unit")
        }
    }.toString()

    private fun frame(marker: String, response: String?, status: Int): String {
        val suffix = if (response == null) "" else ":" + Base64.getEncoder().encodeToString(response.toByteArray(Charsets.UTF_8))
        return "printf 'diagnostic\\n' >&2; printf 'visible'; " +
            "printf '%s\\n' '${marker}:$status$suffix'; printf '%s\\n' '${marker}:$status' >&2\n"
    }
}
