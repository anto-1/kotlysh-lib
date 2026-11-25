package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(20)
class StructuredPromiseBoundaryTest {
    @Test
    fun `raw scripts await native promises without implicitly calling ordinary thenables`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.calls = 0; ({then(resolve) {calls++; resolve(99)}})"().ok)
            assertEquals(0, eval<Int>("calls"))
            val waited = "new Promise(resolve => setTimeout(() => {console.log('settled'); resolve()}, 10))"()
            assertTrue(waited.ok, waited.stderrText)
            assertEquals(listOf("settled"), waited.stdout)
        }
    }

    @Test
    fun `JSON rejection does not implicitly invoke then getters or proxy traps`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.reads = 0"().ok)
            for (expression in listOf(
                "({get then() {reads++; return undefined}, keep: 7})",
                "new Proxy({keep: 7}, {get(target, key) {reads++; return Reflect.get(target, key)}})",
            )) {
                assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
                assertEquals(0, eval<Int>("reads"))
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `holding plain thenables preserves identity and defers explicit await`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.calls = 0; globalThis.item = {then(resolve) {calls++; resolve(99)}, keep: 7}"().ok)
            hold("item") { reference ->
                assertTrue(eval<Boolean>("held === item", "held" to reference))
                assertEquals(0, eval<Int>("calls"))
                assertEquals(7, eval<Int>("held.keep", "held" to reference))
                assertFailsWith<RuntimeCallException> { readJson(reference) }
                assertEquals(0, eval<Int>("calls"))
                assertEquals(99, eval<Int>("await held", "held" to reference))
                assertEquals(1, eval<Int>("calls"))
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `holding getters and proxies does not inspect their then property`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.reads = 0; globalThis.item = {get then() {reads++; return undefined}, keep: 7}; globalThis.proxy = new Proxy({keep: 8}, {get(target, key) {reads++; return Reflect.get(target, key)}})"().ok)
            for (expression in listOf("item", "proxy")) {
                hold(expression) { reference ->
                    assertTrue(eval<Boolean>("held === $expression", "held" to reference))
                    assertEquals(0, eval<Int>("reads"))
                }
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `native promises and async expressions still resolve without reading user then getters`(): Unit = runBlocking {
        node {
            assertEquals(42, eval<Int>("Promise.resolve(42)"))
            assertEquals(42, eval<Int>("await Promise.resolve(42)"))
            assertEquals(42, eval<Int>("(async () => 42)()"))
            assertTrue("globalThis.reads = 0; globalThis.result = Promise.resolve(42); Object.defineProperty(result, 'then', {get() {reads++; throw new Error('unexpected getter')}})"().ok)
            assertEquals(42, eval<Int>("result"))
            assertEquals(0, eval<Int>("reads"))
            hold("Promise.resolve({keep: 7})") { reference ->
                assertEquals(7, eval<Int>("held.keep", "held" to reference))
            }
        }
    }
}
