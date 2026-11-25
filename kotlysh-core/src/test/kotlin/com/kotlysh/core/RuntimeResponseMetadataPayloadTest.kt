package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*

@Timeout(30)
class RuntimeResponseMetadataPayloadTest {
    @Test
    fun `unknown metadata is preserved completely at the whole envelope byte boundary`(): Unit = runBlocking {
        val metadata = """{"items":[${"0,".repeat(5_000)}null],"text":"é😀\\n[]{}"}"""
        val json = envelope(metadata)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > json.length)
        withRuntimeResponseLimits(RuntimeResponseLimits(maxPayloadBytes = bytes)) {
            runtime(adapter(json)) {
                val scope = structured()
                repeat(2) {
                    val result = scope.evaluateJson("ignored")
                    assertEquals(JsonPrimitive(42), result.value)
                    assertEquals(Json.parseToJsonElement(metadata), result.execution.response!!["future"])
                    assertEquals(listOf("visible"), result.execution.stdout)
                    assertEquals(listOf("diagnostic"), result.execution.stderr)
                    assertTrue(runtime.isOpen)
                }
            }
        }
    }

    @Test
    fun `unknown metadata contributes to the byte budget and excessive envelopes stop the process`(): Unit = runBlocking {
        val json = envelope("[${"0,".repeat(5_000)}null]")
        reject(json, RuntimeResponseLimits(maxPayloadBytes = json.toByteArray(Charsets.UTF_8).size - 1), "payload byte limit")
    }

    @Test
    fun `malformed metadata after many array entries cannot become a successful value`(): Unit = runBlocking {
        for (tail in listOf("NaN", "+1", "{\"x\":1,\"\\u0078\":2}")) {
            val json = envelope("[${"0,".repeat(5_000)}$tail]")
            reject(json, RuntimeResponseLimits(), if (tail.startsWith('{')) "Duplicate JSON object key" else "Malformed JSON literal")
        }
    }

    private suspend fun reject(json: String, limits: RuntimeResponseLimits, reason: String) {
        val trace = RuntimeTraceBuffer()
        withRuntimeResponseLimits(limits) {
            withRuntimeTrace(trace) {
                runtime(adapter(json)) {
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val failure = assertFailsWith<IllegalStateException> { structured().evaluateJson("ignored") }
                    assertTrue(failure.message.orEmpty().contains(reason), failure.message)
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive)
                    assertFailsWith<IllegalStateException> { structured().evaluateJson("must not run") }
                }
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
        assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchFailed>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchOutput>().all { it.text in setOf("visible", "diagnostic") })
    }

    private fun RuntimeScope.structured() = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))

    private fun envelope(metadata: String) = """{"version":1,"kind":"value","value":42,"future":$metadata}"""

    private fun adapter(json: String): RuntimeSpec {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val frame: (String) -> String = { marker ->
            "printf 'diagnostic\\n' >&2; printf 'visible\\n'; " +
                "printf '%s\\n' '$marker:0:$encoded'; printf '%s\\n' '$marker:0' >&2\n"
        }
        return RuntimeSpec("metadata-payload-test", RuntimeSpecs.Bash.command,
            wrap = { _, marker -> frame(marker) }, requestWrapper = { _, marker -> frame(marker) })
    }
}
