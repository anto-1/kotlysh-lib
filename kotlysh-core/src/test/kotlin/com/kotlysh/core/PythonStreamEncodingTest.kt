package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import java.util.Base64
import kotlin.test.*

@Timeout(30)
class PythonStreamEncodingTest {
    @Test
    fun `UTF8 setup preserves standard stream identities error policies and unbuffered output`(): Unit = runBlocking {
        runtime(python(listOf("PYTHONIOENCODING=latin-1:backslashreplace"))) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            "import sys"()
            assertEquals(JsonPrimitive(true), scope.evaluateJson(
                "all(getattr(sys, name) is getattr(sys, '__' + name + '__') and " +
                    "getattr(sys, name).encoding == 'utf-8' and getattr(sys, name).errors == 'backslashreplace' " +
                    "for name in ('stdin', 'stdout', 'stderr')) and sys.stdout.write_through and sys.stderr.write_through",
            ).value)
        }
    }

    @Test
    fun `stream fallback preserves pipe ownership and aliases without reconfigure`(): Unit = runBlocking {
        val command = RuntimeSpecs.Python.command
        val driver = command.last()
        val encoded = Base64.getEncoder().encodeToString(driver.toByteArray(Charsets.UTF_8))
        val bootstrap = """
            import sys, base64
            class LegacyStream:
                def __init__(self, stream): self.stream = stream
                def __getattr__(self, name):
                    if name == 'reconfigure': raise AttributeError(name)
                    return getattr(self.stream, name)
            for name in ('stdin', 'stdout', 'stderr'):
                stream = LegacyStream(getattr(sys, name))
                setattr(sys, name, stream)
                setattr(sys, '__' + name + '__', stream)
            exec(base64.b64decode('$encoded').decode('utf-8'))
        """.trimIndent()
        val spec = RuntimeSpecs.Python.copy(command = listOf("env", "PYTHONIOENCODING=ascii:backslashreplace") + command.dropLast(1) + bootstrap)
        runtime(spec) {
            val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
            "import sys"()
            assertEquals(JsonPrimitive(true), scope.evaluateJson(
                "all(getattr(sys, name) is getattr(sys, '__' + name + '__') and " +
                    "getattr(sys, name).encoding == 'utf-8' and getattr(sys, name).errors == 'backslashreplace' " +
                    "for name in ('stdin', 'stdout', 'stderr')) and sys.stdout.write_through and sys.stderr.write_through",
            ).value)
            assertEquals(listOf("café 😀"), "print(${pythonLiteral("café 😀")})"().stdout)
            assertEquals(JsonPrimitive(value), scope.evaluateJson("item", "item" to jsonValue(JsonPrimitive(value))).value)
            assertTrue(runtime.isOpen)
        }
    }

    private val value = "café Ελληνικά 日本語 😀\n\u0000\uD800\uDC00\uD800"
    private val environments = listOf(
        listOf("LC_ALL=C", "PYTHONUTF8=0", "PYTHONCOERCECLOCALE=0"),
        listOf("PYTHONIOENCODING=ascii:strict"),
        listOf("PYTHONIOENCODING=latin-1:replace"),
    )

    @Test
    fun `structured values and bindings preserve Unicode independently of ambient encoding`(): Unit = runBlocking {
        for (environment in environments) {
            runtime(python(environment)) {
                val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                scope.storeJson(JsonPrimitive(value)) { reference ->
                    assertEquals(JsonPrimitive(value), scope.readJson(reference))
                    assertEquals(JsonPrimitive(value), scope.evaluateJson("item", "item" to jsonValue(JsonPrimitive(value))).value)
                }
                assertEquals(JsonPrimitive(42), scope.evaluateJson("42").value)
                assertTrue(runtime.isOpen)
            }
        }
    }

    @Test
    fun `Python Unicode stdout stderr and error diagnostics remain exact across ambient encodings`(): Unit = runBlocking {
        val text = "café Ελληνικά 日本語 😀"
        for (environment in environments) {
            val trace = RuntimeTraceBuffer()
            withRuntimeTrace(trace) {
                runtime(python(environment)) {
                    val scope = StructuredRuntimeScope(coroutineContext, RuntimeContext(runtime))
                    val result = "import sys; print(${pythonLiteral(text)}); print(${pythonLiteral(text)}, file=sys.stderr)"()
                    assertTrue(result.ok, "exit=${result.exitCode}, stderr=${result.stderrText}")
                    assertEquals(listOf(text), result.stdout)
                    assertEquals(listOf(text), result.stderr)
                    val failure = assertFailsWith<RuntimeCallException> {
                        scope.evaluateJson("(_ for _ in ()).throw(ValueError(${pythonLiteral(text)}))")
                    }
                    assertEquals(text, failure.errorMessage)
                    assertTrue(failure.execution.stderr.any { it.contains(text) })
                    assertEquals(JsonPrimitive(42), scope.evaluateJson("42").value)
                    assertTrue(runtime.isOpen)
                }
            }
            assertEquals(text, trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.exitCode == 1 }.message)
            assertEquals(setOf(RuntimeStream.Stdout, RuntimeStream.Stderr), trace.events.filterIsInstance<RuntimeDispatchOutput>()
                .filter { it.text == text }.map { it.stream }.toSet())
            assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        }
    }

    private fun python(environment: List<String>): RuntimeSpec = RuntimeSpecs.Python.copy(
        command = listOf("env") + environment + RuntimeSpecs.Python.command,
    )
}
