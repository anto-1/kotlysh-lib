package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class NodeNamespaceTest {
    @Test
    fun `user promise prototype callbacks cannot substitute typed results`(): Unit = runBlocking {
        node {
            assertTrue("""
                globalThis.promiseHookCalls = 0;
                Promise.prototype.then = function(fulfilled) {
                    promiseHookCalls++;
                    return fulfilled({then: undefined, value: 999});
                };
            """().ok)
            assertEquals(42, eval<Int>("40 + 2"))
            assertEquals(0, eval<Int>("promiseHookCalls"))
            hold("({keep: 7})") { reference ->
                assertEquals(7, eval<Int>("held.keep", "held" to reference))
            }
            val explicit = "Promise.resolve(7).then(value => console.log('user value:' + value.value)); void 0"()
            assertEquals(listOf("user value:999"), explicit.stdout)
            assertEquals(1, eval<Int>("promiseHookCalls"))
        }
    }

    @Test
    fun `user promise prototype getter is not read by typed evaluation`(): Unit = runBlocking {
        node {
            assertTrue("""
                globalThis.promiseHookReads = 0;
                Object.defineProperty(Promise.prototype, 'then', {
                    configurable: true,
                    get() { promiseHookReads++; throw new Error('user promise getter'); }
                });
            """().ok)
            assertEquals(42, eval<Int>("42"))
            assertEquals(42, eval<Int>("Promise.resolve(42)"))
            hold("Promise.resolve({keep: 7})") { reference ->
                assertEquals(7, eval<Int>("held.keep", "held" to reference))
            }
            assertEquals(0, eval<Int>("promiseHookReads"))
            assertEquals(listOf("user promise getter"), "try { Promise.resolve().then } catch (error) { console.log(error.message) }; void 0"().stdout)
            assertEquals(1, eval<Int>("promiseHookReads"))
        }
    }

    @Test
    fun `async typed failures keep diagnostics and session reuse after prototype overrides`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            node {
                assertTrue("Promise.prototype.then = () => { throw new Error('user callback'); }; void 0"().ok)
                val failure = assertFailsWith<RuntimeCallException> {
                    eval<Int>("(async () => { throw new RangeError('actual typed failure'); })()", timeout = 5.seconds)
                }
                assertEquals("RangeError", failure.errorType)
                assertEquals("actual typed failure", failure.errorMessage)
                assertEquals(1, failure.execution.exitCode)
                assertFalse(failure.execution.processExited)
                assertEquals(42, eval<Int>("(async () => 42)()"))
                assertTrue(runtime.isOpen)
            }
        }
        val failed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().filter { it.exitCode == 1 }
        assertEquals(1, failed.size)
        assertEquals("RangeError", failed.single().errorType)
        assertEquals("actual typed failure", failed.single().message)
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }

    @Test
    fun `user global bindings cannot overwrite driver dependencies`(): Unit = runBlocking {
        for (name in listOf("JSON", "Buffer", "Object", "Array", "Number", "String", "Set", "Map", "Error", "TypeError", "ReferenceError", "process")) {
            node {
                val assigned = "var $name = {marker: 'user-$name'};"(timeout = 5.seconds)
                assertTrue(assigned.ok, assigned.stderrText)
                assertEquals("user-$name", eval<String>("$name.marker"))
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `Node language constructors agree with literal prototypes`(): Unit = runBlocking {
        node {
            assertTrue(eval<Boolean>("Object.getPrototypeOf({}) === Object.prototype && Object.getPrototypeOf([]) === Array.prototype"))
            assertTrue(eval<Boolean>("({}) instanceof Object && [] instanceof Array"))
            assertEquals(listOf(1, 2), eval<List<Int>>("new Array(1,2)"))
        }
    }

    @Test
    fun `user JSON and prototype hooks do not rewrite transport data`(): Unit = runBlocking {
        node {
            assertTrue("""
                var hookReads = 0;
                JSON.parse = () => { hookReads++; throw new Error('user parser'); };
                JSON.stringify = () => { hookReads++; return 'user encoder'; };
                Object.prototype.toJSON = () => { hookReads++; return 'lost object'; };
                Array.prototype.toJSON = () => { hookReads++; return 'lost array'; };
            """().ok)
            val expected = buildJsonObject {
                put("text", "café 😀")
                put("items", buildJsonArray { add(1); add(2) })
            }
            assertEquals(expected, eval<JsonElement>("({text: 'café 😀', items: [1,2]})"))
            assertEquals(0, eval<Int>("hookReads"))
            assertEquals("user encoder", eval<String>("JSON.stringify({})"))
            assertEquals(1, eval<Int>("hookReads"))
        }
    }

    @Test
    fun `Node APIs global aliases async work and streams remain available`(): Unit = runBlocking {
        node {
            assertTrue(eval<Boolean>("globalThis === global && global.require === require && typeof module === 'object'"))
            assertEquals("café 😀", eval<String>("Buffer.from('café 😀', 'utf8').toString('utf8')"))
            assertEquals(42, eval<Int>("await new Promise(resolve => setTimeout(() => resolve(42), 10))"))
            val output = "console.log('namespace-out'); process.stderr.write('namespace-error')"()
            assertEquals(listOf("namespace-out"), output.stdout)
            assertEquals(listOf("namespace-error"), output.stderr)
            assertEquals("ok", eval<String>("require('node:path').basename('/tmp/ok')"))
        }
    }

    @Test
    fun `user numeric helper overrides cannot bypass JSON validation`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            node {
                assertTrue("Number.isFinite = () => true; Number.isInteger = () => false; Number.isSafeInteger = () => true"().ok)
                for (expression in listOf("NaN", "Infinity", "9007199254740992")) {
                    val failure = assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
                    assertEquals("TypeError", failure.errorType)
                    assertEquals(1, failure.execution.exitCode)
                    assertFalse(failure.execution.processExited)
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
        val failed = trace.events.filterIsInstance<RuntimeDispatchCompleted>().filter { it.exitCode == 1 }
        assertEquals(3, failed.size)
        assertTrue(failed.all { it.errorType == "TypeError" })
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }
}
