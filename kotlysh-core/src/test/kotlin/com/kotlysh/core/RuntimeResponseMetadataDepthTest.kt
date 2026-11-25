package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeResponseMetadataDepthTest {
    @Test
    fun `unknown metadata at the default depth boundary preserves the value and reusable session`(): Unit = runBlocking {
        for (shape in Shape.entries) {
            runtime(adapter(metadata(127, shape))) {
                val scope = structured()
                repeat(2) {
                    val result = scope.evaluateJson("ignored")
                    assertEquals(JsonPrimitive(42), result.value)
                    assertEquals(listOf("visible"), result.execution.stdout)
                    assertEquals(listOf("diagnostic"), result.execution.stderr)
                    assertTrue(runtime.isOpen)
                }
            }
        }
    }

    @Test
    fun `quoted containers and escapes in unknown metadata do not count as nesting`(): Unit = runBlocking {
        val text = "[{\"quoted\":\"\\\\\"}]".repeat(1_000) + "\u0000\r\n😀"
        withRuntimeResponseLimits(RuntimeResponseLimits(maxNestingDepth = 1)) {
            runtime(adapter(JsonPrimitive(text).toString())) {
                val result = structured().evaluateJson("ignored")
                assertEquals(JsonPrimitive(42), result.value)
                assertEquals(text, result.execution.response!!["future"]!!.jsonPrimitive.content)
                assertTrue(runtime.isOpen)
            }
        }
    }

    @Test
    fun `excessive unknown metadata fails before returning a value and stops owned processes`(): Unit = runBlocking {
        for (depth in listOf(128, 10_000)) for (shape in Shape.entries) {
            val trace = RuntimeTraceBuffer()
            var child: ProcessHandle? = null
            var childStarted: java.time.Instant? = null
            try {
                withRuntimeTrace(trace) {
                    runtime(adapter(metadata(depth, shape), ownedChild = true)) {
                        val root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        val failure = assertFailsWith<IllegalStateException> {
                            structured().evaluateJson("ignored", timeout = 10.seconds, output = RuntimeOutputSink { line ->
                                if (line.stream == RuntimeStream.Stdout && line.text.startsWith("owned:")) {
                                    child = ProcessHandle.of(line.text.removePrefix("owned:").toLong()).orElseThrow()
                                    childStarted = child!!.info().startInstant().orElse(null)
                                }
                            })
                        }
                        assertTrue(failure.message.orEmpty().contains("nesting depth limit of 128"))
                        assertFalse(runtime.isOpen)
                        assertFalse(root.isAlive)
                        assertFalse(assertNotNull(child).isAlive)
                        assertFailsWith<IllegalStateException> { structured().evaluateJson("must not run") }
                    }
                }
                val events = trace.events
                val failure = events.filterIsInstance<RuntimeDispatchFailed>().single()
                val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
                assertTrue(closed.poisoned)
                assertTrue(events.indexOf(failure) < events.indexOf(closed))
                assertTrue(events.filterIsInstance<RuntimeDispatchCompleted>().isEmpty())
                assertTrue(events.filterIsInstance<RuntimeDispatchOutput>().all {
                    it.text == "visible" || it.text == "diagnostic" || it.text.startsWith("owned:")
                })
                assertEquals(closed, events.last())
            } finally {
                child?.takeIf { it.isAlive && it.info().startInstant().orElse(null) == childStarted }?.destroyForcibly()
            }
        }
    }

    private fun RuntimeScope.structured() = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))

    private fun adapter(metadata: String, ownedChild: Boolean = false): RuntimeSpec {
        val json = """{"version":1,"kind":"value","value":42,"future":$metadata}"""
        val payload = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val frame: (String) -> String = { marker ->
            val setup = if (ownedChild) "sleep 30 & printf 'owned:%s\\n' \"\$!\"; sleep 0.2; " else ""
            setup + "printf 'diagnostic\\n' >&2; printf 'visible\\n'; " +
                "printf '%s\\n' '$marker:0:$payload'; printf '%s\\n' '$marker:0' >&2\n"
        }
        return RuntimeSpec("metadata-depth-test", RuntimeSpecs.Bash.command,
            wrap = { _, marker -> frame(marker) }, requestWrapper = { _, marker -> frame(marker) })
    }

    private fun metadata(depth: Int, shape: Shape): String {
        val objects = (0 until depth).map { shape == Shape.OBJECT || shape == Shape.MIXED && it % 2 == 0 }
        return buildString {
            for (objectContainer in objects) append(if (objectContainer) "{\"nested\":" else "[")
            append("null")
            for (objectContainer in objects.asReversed()) append(if (objectContainer) '}' else ']')
        }
    }

    private enum class Shape { ARRAY, OBJECT, MIXED }
}
