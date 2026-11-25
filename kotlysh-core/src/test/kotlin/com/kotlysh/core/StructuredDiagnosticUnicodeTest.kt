package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(20)
class StructuredDiagnosticUnicodeTest {
    @Test
    fun `native error diagnostics preserve lone surrogates while clipping only complete pairs`(): Unit = runBlocking {
        val cases = listOf(
            "tail:\uD800" to "tail:\uD800",
            ("x".repeat(4095) + "\uD800suffix") to ("x".repeat(4095) + "\uD800"),
            ("x".repeat(4095) + "😀suffix") to "x".repeat(4095),
            ("x".repeat(4094) + "😀suffix") to ("x".repeat(4094) + "😀"),
        )
        for (spec in listOf(RuntimeSpecs.Python, RuntimeSpecs.Node)) {
            val trace = RuntimeTraceBuffer()
            withRuntimeTrace(trace) {
                runtime(spec) {
                    val scope = StructuredRuntimeScope(coroutineContext, runtimeContext)
                    for ((data, retained) in cases) {
                        val expression = if (spec == RuntimeSpecs.Python) {
                            "(_ for _ in ()).throw(ValueError(${pythonLiteral(data)}))"
                        } else {
                            "(() => { throw Object.assign(new Error(${jsLiteral(data)}), {name: ${jsLiteral(data)}}); })()"
                        }
                        val failure = assertFailsWith<RuntimeCallException> { scope.evaluateJson(expression) }
                        val type = if (spec == RuntimeSpecs.Python) "ValueError" else data
                        assertEquals(type, failure.execution.response?.get("type")?.jsonPrimitive?.content)
                        assertEquals(data, failure.execution.response?.get("message")?.jsonPrimitive?.content)
                        val completed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single {
                            it.sessionId == failure.execution.sessionId && it.dispatchId == failure.execution.dispatchId
                        }
                        assertEquals(if (spec == RuntimeSpecs.Python) type else retained, completed.errorType)
                        assertEquals(retained, completed.message)
                        assertEquals(1, completed.exitCode)
                        assertTrue(runtime.isOpen, "A valid foreign error must leave its session usable")
                        assertEquals("42", scope.evaluateJson("42").value.toString())
                    }
                }
            }
            assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().all { !it.poisoned })
        }
    }

    @Test
    fun `bounded command data keeps lone surrogates without splitting valid pairs`() {
        assertEquals(listOf("node", "-e", "a\uD800…"), boundedCommand(listOf("node", "-e", "a\uD800btail"), 9))
        assertEquals(listOf("node", "-e", "a…"), boundedCommand(listOf("node", "-e", "a😀btail"), 9))
        assertEquals(listOf("a\uD800"), boundedCommand(listOf("a\uD800"), 2))
    }
}
