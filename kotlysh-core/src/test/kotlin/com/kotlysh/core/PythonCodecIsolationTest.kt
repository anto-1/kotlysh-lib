package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class PythonCodecIsolationTest {
    @Test
    fun `user JSON encoder cannot silently replace typed results`(): Unit = runBlocking {
        py {
            val spoofed = """{"version":1,"kind":"value","value":999}"""
            assertTrue("import json; json.dumps = lambda *args, **kwargs: ${pythonLiteral(spoofed)}; retained = 42"(timeout = 5.seconds).ok)
            assertEquals(42, eval<Int>("retained"))
            assertEquals(spoofed, eval<String>("json.dumps({})"))
            store(listOf(7)) { reference -> assertEquals(listOf(7), read<List<Int>>(reference)) }
            assertTrue(runtime.isOpen)
        }
    }

    @Test
    fun `user JSON encoder constructor cannot redirect driver serialization`(): Unit = runBlocking {
        py {
            val spoofed = """{"version":1,"kind":"value","value":999}"""
            assertTrue("""
                import json
                class UserEncoder:
                    def __init__(self, *args, **kwargs): pass
                    def encode(self, value): return ${pythonLiteral(spoofed)}
                json.JSONEncoder = UserEncoder
            """(timeout = 5.seconds).ok)
            assertEquals(42, eval<Int>("42"))
            assertEquals(spoofed, eval<String>("json.dumps({}, allow_nan=False)"))
        }
    }

    @Test
    fun `user JSON decoder customization remains visible without replacing requests`(): Unit = runBlocking {
        py {
            assertTrue("import json; json.loads = lambda *args, **kwargs: 'user parser'"(timeout = 5.seconds).ok)
            assertEquals("user parser", eval<String>("json.loads('ignored')"))
            assertEquals(42, eval<Int>("item + 1", "item" to value(41)))
            assertTrue(runtime.isOpen)
        }
    }

    @Test
    fun `user Base64 encoder cannot corrupt completion frames`(): Unit = runBlocking {
        py {
            assertTrue("import base64; base64.b64encode = lambda *args, **kwargs: b'user encoder'"(timeout = 5.seconds).ok)
            assertEquals("user encoder", eval<String>("base64.b64encode(b'ignored').decode('ascii')"))
            assertEquals(42, eval<Int>("42"))
            assertTrue(runtime.isOpen)
        }
    }

    @Test
    fun `user Base64 decoder cannot replace script or expression source`(): Unit = runBlocking {
        py {
            assertTrue("import base64; base64.b64decode = lambda *args, **kwargs: b'user decoder'"(timeout = 5.seconds).ok)
            assertEquals("user decoder", eval<String>("base64.b64decode('ignored').decode('ascii')"))
            assertEquals(42, eval<Int>("42"))
            assertTrue(runtime.isOpen)
        }
    }

    @Test
    fun `user compile exec and eval hooks cannot silently skip driver evaluation`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                assertTrue("""
                    import builtins, sys
                    builtins.compile = lambda *args, **kwargs: 'user compiler'
                    builtins.exec = lambda *args, **kwargs: 'user executor'
                    builtins.eval = lambda *args, **kwargs: 'user evaluator'
                    retained = 42
                """(timeout = 5.seconds).ok)
                val result = "print(builtins.compile('ignored', 'ignored', 'exec')); print(builtins.exec('ignored')); print(builtins.eval('ignored')); print(retained); print('separate:日本語', file=sys.stderr)"(timeout = 5.seconds)
                assertTrue(result.ok)
                assertEquals(listOf("user compiler", "user executor", "user evaluator", "42"), result.stdout)
                assertEquals(listOf("separate:日本語"), result.stderr)
                assertEquals(43, eval<Int>("retained + 1"))
                val failure = assertFailsWith<RuntimeCallException> { eval<JsonElement>("1 / 0") }
                assertEquals("ZeroDivisionError", failure.errorType)
                assertFalse(failure.execution.processExited)
                assertEquals(42, eval<Int>("retained"))
            }
        }
        assertEquals("ZeroDivisionError", trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.exitCode == 1 }.errorType)
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `decoder constructor replacement cannot lose deep request markers`(): Unit = runBlocking {
        py {
            assertTrue("import json; json.JSONDecoder = lambda *args, **kwargs: None"(timeout = 5.seconds).ok)
            var nested: JsonElement = JsonPrimitive(7)
            repeat(10_000) { nested = JsonArray(listOf(nested)) }
            val failure = assertFailsWith<RuntimeCallException> { storeJson(nested) { fail("Deep store body executed") } }
            assertEquals("RecursionError", failure.errorType)
            assertEquals(1, failure.execution.exitCode)
            assertFalse(failure.execution.processExited)
            assertEquals(42, eval<Int>("42"))
            assertTrue(runtime.isOpen)
        }
    }
}
