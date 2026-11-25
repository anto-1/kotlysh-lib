package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(30)
class NodeValueRealmTest {
    @Test
    fun `JSON bindings use the script context prototypes at every nested level`(): Unit = runBlocking {
        val payload = buildJsonObject {
            put("items", buildJsonArray { add(2); add(buildJsonObject { put("nested", buildJsonArray { add(3) }) }) })
        }
        val roots = mutableListOf<ProcessHandle>()
        withRuntimeTrace(RuntimeTraceSink { if (it is RuntimeSessionStarted) roots += ProcessHandle.of(it.processId).orElseThrow() }) {
            node {
                assertTrue(eval<Boolean>("""
                    payload instanceof Object &&
                    Object.getPrototypeOf(payload) === Object.prototype &&
                    payload.items instanceof Array &&
                    Object.getPrototypeOf(payload.items) === Array.prototype &&
                    payload.items[1] instanceof Object &&
                    payload.items[1].nested instanceof Array
                """, "payload" to jsonValue(payload)))
                assertEquals(payload, eval<JsonElement>("payload", "payload" to jsonValue(payload)))
                assertTrue(runtime.isOpen)
            }
        }
        assertEquals(1, roots.size)
        assertTrue(roots.none { it.isAlive })
    }

    @Test
    fun `stored and copied arrays use script helpers and preserve independent reference ownership`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val roots = mutableListOf<ProcessHandle>()
        withRuntimeTrace(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeSessionStarted) roots += ProcessHandle.of(event.processId).orElseThrow()
        }) {
            py {
                store(listOf(2, 3, 5)) { original ->
                    node {
                        assertTrue("Array.prototype.realmName = function() { return 'script-array' }; void 0"().ok)
                        store(listOf(7, 11)) { local ->
                            assertEquals("script-array", eval<String>("items.realmName()", "items" to local))
                            assertTrue(eval<Boolean>("items instanceof Array", "items" to local))
                            assertEquals(listOf(7, 11), read<List<Int>>(local))
                        }
                        copy(original) { copied ->
                            assertEquals("script-array", eval<String>("items.realmName()", "items" to copied))
                            assertTrue(eval<Boolean>("items instanceof Array", "items" to copied))
                            assertEquals(4, eval<Int>("items.push(7)", "items" to copied))
                            assertEquals(listOf(2, 3, 5, 7), read<List<Int>>(copied))
                        }
                    }
                    assertEquals(listOf(2, 3, 5), read<List<Int>>(original))
                }
            }
        }
        assertEquals(2, roots.size)
        assertTrue(roots.none { it.isAlive })
        val sessions = trace.events.filterIsInstance<RuntimeSessionStarted>()
        val python = sessions.single { it.runtime == "py" }.sessionId
        val copied = trace.events.filterIsInstance<RuntimeDispatchStarted>().single {
            it.runtime == "node" && it.operation == "store" && it.inputSessionIds == listOf(python)
        }
        assertEquals("node", copied.runtime)
        assertEquals("store", copied.operation)
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().all { !it.poisoned })
    }

    @Test
    fun `script parser and inherited JSON hooks cannot rewrite imported data or transport`(): Unit = runBlocking {
        val payload = buildJsonObject {
            put("__proto__", buildJsonObject { put("safe", true) })
            put("constructor", "ordinary key")
            put("then", "ordinary key")
            put("nested", buildJsonArray { add("é😀\uD800"); add(buildJsonObject { put("value", 7) }) })
        }
        val roots = mutableListOf<ProcessHandle>()
        withRuntimeTrace(RuntimeTraceSink { if (it is RuntimeSessionStarted) roots += ProcessHandle.of(it.processId).orElseThrow() }) {
            node {
                assertTrue("""
                    globalThis.jsonHookReads = 0;
                    JSON.parse = () => { jsonHookReads++; throw new Error('user parser'); };
                    JSON.stringify = () => { jsonHookReads++; throw new Error('user encoder'); };
                    for (const prototype of [Object.prototype, Array.prototype]) {
                        Object.defineProperty(prototype, 'toJSON', {
                            get() { jsonHookReads++; throw new Error('user JSON hook'); }
                        });
                    }
                    Object.defineProperty(Object.prototype, 'nested', {
                        set() { jsonHookReads++; throw new Error('user object setter'); }
                    });
                    Object.defineProperty(Array.prototype, '0', {
                        set() { jsonHookReads++; throw new Error('user array setter'); }
                    });
                    void 0;
                """().ok)
                assertEquals(payload, eval<JsonElement>("payload", "payload" to jsonValue(payload)))
                assertTrue(eval<Boolean>("Object.getPrototypeOf(payload) === Object.prototype && payload.nested instanceof Array",
                    "payload" to jsonValue(payload)))
                storeJson(payload) { stored ->
                    assertEquals(payload, read<JsonElement>(stored))
                    assertTrue(eval<Boolean>("Object.getPrototypeOf(payload) === Object.prototype && payload.nested instanceof Array",
                        "payload" to stored))
                }
                assertEquals(0, eval<Int>("jsonHookReads"))
                assertTrue(runtime.isOpen)
            }
        }
        assertEquals(1, roots.size)
        assertFalse(roots.single().isAlive)
    }
}
