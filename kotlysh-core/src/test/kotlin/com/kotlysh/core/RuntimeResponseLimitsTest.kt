package com.kotlysh.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeResponseLimitsTest {
    @Test
    fun `response limits have finite defaults and require positive values`() {
        assertEquals(8 * 1024 * 1024, RuntimeResponseLimits().maxPayloadBytes)
        assertEquals(128, RuntimeResponseLimits().maxNestingDepth)
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { RuntimeResponseLimits(maxPayloadBytes = invalid) }
            assertFailsWith<IllegalArgumentException> { RuntimeResponseLimits(maxNestingDepth = invalid) }
        }
        assertEquals(2_863_311_532L, RuntimeResponseLimits(maxPayloadBytes = Int.MAX_VALUE).maxEncodedCharacters)
    }

    @Test
    fun `complete envelope byte boundary counts UTF8 rather than Kotlin characters`(): Unit = runBlocking {
        val json = response(JsonPrimitive("é😀"))
        val bytes = json.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > json.length)
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = bytes, maxNestingDepth = 1)) {
            runtime(framed(json)) {
                val scope = structured()
                repeat(2) {
                    val result = scope.evaluate<String>("ignored")
                    assertEquals("é😀", result.value)
                    assertEquals(listOf("visible"), result.execution.stdout)
                    assertEquals(listOf("diagnostic"), result.execution.stderr)
                    assertTrue(runtime.isOpen)
                }
            }
        }
        assertRejected(framed(json), RuntimeResponseLimits(maxPayloadBytes = bytes - 1), "payload byte")
    }

    @Test
    fun `actual decoded bytes are checked even when base64 lengths fit the same block`(): Unit = runBlocking {
        var json = response(JsonPrimitive("secret"))
        while (json.toByteArray(Charsets.UTF_8).size % 3 == 1) json += " "
        val bytes = json.toByteArray(Charsets.UTF_8).size
        val limit = RuntimeResponseLimits(maxPayloadBytes = bytes - 1)
        assertEquals(Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)).length.toLong(), limit.maxEncodedCharacters)
        assertRejected(framed(json), limit, "payload byte")
    }

    @Test
    fun `nesting includes envelope containers and honors its exact boundary`(): Unit = runBlocking {
        val json = """{"version":1,"kind":"value","value":[{"safe":[null]}]}"""
        withRuntimeResponseLimits(RuntimeResponseLimits(maxNestingDepth = 4)) {
            runtime(framed(json)) {
                assertEquals(JsonNull, structured().evaluateJson("ignored").value.jsonArray.single().jsonObject["safe"]!!.jsonArray.single())
                assertTrue(runtime.isOpen)
            }
        }
        assertRejected(framed(json), RuntimeResponseLimits(maxNestingDepth = 3), "nesting depth")
    }

    @Test
    fun `quoted brackets escaped quotes and backslashes do not increase nesting`(): Unit = runBlocking {
        val value = "[{}]".repeat(300) + "\\\"[{}]\\\\\u0000\r\n😀"
        withRuntimeResponseLimits(RuntimeResponseLimits(maxNestingDepth = 1)) {
            runtime(framed(response(JsonPrimitive(value)))) {
                assertEquals(value, structured().eval<String>("ignored"))
            }
        }
    }

    @Test
    fun `default payload cap applies without tracing and scoped raising permits a larger value`(): Unit = runBlocking {
        val characters = 9 * 1024 * 1024
        val spec = generatedString(characters)
        runtime(spec) {
            val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
            val failure = assertFailsWith<IllegalStateException> { structured().evaluateJson("ignored") }
            assertTrue(failure.message.orEmpty().contains("payload byte limit"))
            assertFalse(runtime.isOpen)
            assertFalse(process.isAlive)
            assertFailsWith<IllegalStateException> { "must not run"() }
        }
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = characters + 128)) {
            runtime(spec) {
                val result = structured().evaluateJson("ignored")
                assertEquals(characters, result.value.jsonPrimitive.content.length)
                assertTrue(result.execution.ok)
                assertTrue(result.execution.stdout.isEmpty())
                assertTrue(result.execution.stderr.isEmpty())
            }
        }
    }

    @Test
    fun `default depth cap applies without tracing and can be raised explicitly`(): Unit = runBlocking {
        val json = """{"version":1,"kind":"value","value":${"[".repeat(160)}0${"]".repeat(160)}}"""
        runtime(framed(json)) {
            val failure = assertFailsWith<IllegalStateException> { structured().evaluateJson("ignored") }
            assertTrue(failure.message.orEmpty().contains("nesting depth limit"))
            assertFalse(runtime.isOpen)
        }
        withRuntimeResponseLimits(RuntimeResponseLimits(maxNestingDepth = 161)) {
            runtime(framed(json)) {
                var value = structured().evaluateJson("ignored").value
                repeat(160) { value = value.jsonArray.single() }
                assertEquals(JsonPrimitive(0), value)
            }
        }
    }

    @Test
    fun `response policy remains independent of traced log limits`(): Unit = runBlocking {
        val value = "x".repeat(4096)
        val json = response(JsonPrimitive(value))
        val trace = RuntimeTraceBuffer()
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = json.toByteArray().size)) {
            withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 1, maxOutputLinesPerDispatch = 1)) {
                runtime(framed(json, logs = false)) {
                    val result = structured().evaluate<String>("ignored")
                    assertEquals(value, result.value)
                    assertFalse(result.execution.outputLimited)
                }
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
    }

    @Test
    fun `nested scoped overrides are restored for subsequently opened runtimes`(): Unit = runBlocking {
        val json = response(JsonArray(listOf(JsonPrimitive(42))))
        withRuntimeResponseLimits(RuntimeResponseLimits(maxNestingDepth = 1)) {
            withRuntimeResponseLimits(RuntimeResponseLimits(maxNestingDepth = 2)) {
                runtime(framed(json)) { assertEquals(JsonPrimitive(42), structured().evaluateJson("ignored").value.jsonArray.single()) }
            }
            runtime(framed(json)) {
                val failure = assertFailsWith<IllegalStateException> { structured().evaluateJson("ignored") }
                assertTrue(failure.message.orEmpty().contains("nesting depth limit of 1"))
            }
        }
        runtime(framed(json)) { assertTrue(structured().evaluateJson("ignored").execution.ok) }
    }

    @Test
    fun `unfinished oversized frame fails promptly and terminates its owned child`(): Unit = runBlocking {
        var child: ProcessHandle? = null
        val spec = shell { marker ->
            "sleep 30 & child=\$!; printf 'owned:%s\\n' \"\$child\"; " +
                "printf '%s' 'visible${marker}:0:'; printf 'A%.0s' {1..512}; sleep 30; :\n"
        }
        try {
            assertRejected(spec, RuntimeResponseLimits(maxPayloadBytes = 64), "payload byte") { line ->
                if (line.text.startsWith("owned:")) child = ProcessHandle.of(line.text.substringAfter(':').toLong()).orElseThrow()
            }
            val owned = assertNotNull(child)
            withTimeout(2.seconds) { while (owned.isAlive) delay(10) }
            assertFalse(owned.isAlive)
        } finally {
            child?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    @Test
    fun `EOF completes a response at the exact configured byte limit`(): Unit = runBlocking {
        val json = response(JsonNull)
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = json.toByteArray().size, maxNestingDepth = 1)) {
            runtime(framed(json, eof = true)) {
                val result = structured().evaluateJson("ignored")
                assertEquals(JsonNull, result.value)
                assertTrue(result.execution.ok)
                assertFalse(result.execution.processExited)
            }
        }
    }

    @Test
    fun `split marker status and payload preserve framing at the configured limit`(): Unit = runBlocking {
        val value = "😀[]\\\"".repeat(1500)
        val json = response(JsonPrimitive(value))
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val driver = """
            import sys,json,time
            for line in sys.stdin:
                marker=json.loads(line)['marker']
                for part in [marker[:10],marker[10:]+':','0:', '$encoded'[:17], '$encoded'[17:], '\r\n']:
                    sys.stdout.write(part);sys.stdout.flush();time.sleep(0.04)
                sys.stderr.write(marker+':0\r\n');sys.stderr.flush()
        """.trimIndent()
        val spec = pythonDriver(driver)
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = json.toByteArray().size, maxNestingDepth = 1)) {
            runtime(spec) {
                repeat(2) { assertEquals(value, structured().eval<String>("ignored")) }
            }
        }
    }

    @Test
    fun `ordinary large unterminated logs and marker only adapters do not use the response budget`(): Unit = runBlocking {
        val expected = "ordinary:" + "x".repeat(20_000)
        val spec = shell { marker ->
            "printf '%s' '$expected'; printf '%s\\n' '${marker}:0'; printf '%s\\n' '${marker}:0' >&2\n"
        }
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = 1, maxNestingDepth = 1)) {
            runtime(spec) {
                repeat(2) {
                    val result = "ignored"()
                    assertTrue(result.ok)
                    assertEquals(listOf(expected), result.stdout)
                    assertTrue(result.stderr.isEmpty())
                }
            }
        }
    }

    private suspend fun assertRejected(
        spec: RuntimeSpec,
        limits: RuntimeResponseLimits,
        reason: String,
        observer: (RuntimeOutputLine) -> Unit = {},
    ) {
        val trace = RuntimeTraceBuffer()
        withRuntimeResponseLimits(limits) {
            withRuntimeTrace(trace) {
                runtime(spec) {
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val failure = assertFailsWith<IllegalStateException> {
                        structured().evaluateJson("ignored", timeout = 10.seconds, output = RuntimeOutputSink(observer))
                    }
                    assertTrue(failure.message.orEmpty().contains(reason))
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive)
                    assertFailsWith<IllegalStateException> { "must not run"() }
                }
            }
        }
        val events = trace.events
        val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
        val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
        assertTrue(closed.poisoned)
        assertTrue(events.indexOf(failed) < events.indexOf(closed))
        assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertTrue(events.filterIsInstance<RuntimeDispatchOutput>().all {
            it.text == "visible" || it.text == "diagnostic" || it.text.startsWith("owned:")
        })
        assertEquals(closed, events.last())
    }

    private fun RuntimeScope.structured() = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))

    private fun response(value: JsonElement): String = buildJsonObject {
        put("version", 1); put("kind", "value"); put("value", value)
    }.toString()

    private fun framed(json: String, logs: Boolean = true, eof: Boolean = false): RuntimeSpec {
        val payload = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return shell { marker ->
            val log = if (logs) "printf diagnostic >&2; printf visible; " else ""
            val ending = if (eof) "" else "\\n"
            "$log printf '%s$ending' '${marker}:0:$payload'; printf '%s$ending' '${marker}:0' >&2; " +
                if (eof) "exit 0\n" else "\n"
        }
    }

    private fun shell(frame: (String) -> String): RuntimeSpec = RuntimeSpec(
        "response-limits-test", RuntimeSpecs.Bash.command,
        wrap = { _, marker -> frame(marker) }, requestWrapper = { _, marker -> frame(marker) },
    )

    private fun generatedString(characters: Int): RuntimeSpec = pythonDriver("""
        import sys,json,base64
        for line in sys.stdin:
            marker=json.loads(line)['marker']
            payload=json.dumps({'version':1,'kind':'value','value':'x'*$characters},separators=(',',':'))
            print(marker+':0:'+base64.b64encode(payload.encode('utf-8')).decode('ascii'),flush=True)
            print(marker+':0',file=sys.stderr,flush=True)
    """.trimIndent())

    private fun pythonDriver(driver: String): RuntimeSpec = RuntimeSpec(
        "response-limits-test", listOf("python3", "-u", "-c", driver),
        wrap = { _, marker -> buildJsonObject { put("marker", marker) }.toString() + "\n" },
        requestWrapper = { _, marker -> buildJsonObject { put("marker", marker) }.toString() + "\n" },
    )
}
