package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(20)
class StructuredArrayBoundaryTest {
    @Test
    fun `Node array subclasses and counterfeit prototypes require explicit conversion`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.constructorReads = 0"().ok)
            for (expression in listOf(
                "new (class Fancy extends Array {})(7, 8)",
                "Object.setPrototypeOf([7, 8], {get constructor() {constructorReads++; return Array}})",
            )) {
                assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
                assertEquals(0, eval<Int>("constructorReads"))
            }
            assertEquals(listOf(7, 8), eval<List<Int>>("[7, 8]"))
            assertEquals(listOf(7, 8), eval<List<Int>>("Object.setPrototypeOf([7, 8], null)"))
            hold("new (class Fancy extends Array {})(7, 8)") { reference ->
                assertEquals(2, eval<Int>("item.length", "item" to reference))
                assertEquals(listOf(7, 8), eval<List<Int>>("Array.from(item)", "item" to reference))
            }
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `Node arrays cannot silently drop enumerable properties or getters`(): Unit = runBlocking {
        node {
            assertTrue("globalThis.getterReads = 0"().ok)
            for (expression in listOf(
                "Object.assign([7], {label: 'lost data'})",
                "Object.assign([7], {'01': 8})",
                "Object.defineProperty([7], 'label', {get() {getterReads++; return 8}, enumerable: true})",
                "Object.assign([7], {[Symbol('key')]: 8})",
            )) {
                assertFailsWith<RuntimeCallException> { eval<JsonElement>(expression) }
                assertEquals(0, eval<Int>("getterReads"))
            }
            assertEquals(listOf(7), eval<List<Int>>("Object.defineProperty([7], 'ignored', {value: 8, enumerable: false})"))
            assertEquals(listOf(7), eval<List<Int>>("Object.defineProperty([7], '0', {value: 7, enumerable: false})"))
            assertEquals(42, eval<Int>("42"))
        }
    }

    @Test
    fun `a rejected array copy does not create destination data or lineage`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            node {
                val source = runtime
                hold("new (class Fancy extends Array {})(7, 8)") { original ->
                    py {
                        val destination = runtime
                        val failure = assertFailsWith<RuntimeCallException> { copy(original) { } }
                        assertEquals("TypeError", failure.execution.response?.get("type")?.jsonPrimitive?.content)
                        assertFalse(trace.events.filterIsInstance<RuntimeDispatchStarted>().any {
                            it.sessionId == destination.sessionId && it.operation == "store"
                        })
                        assertEquals(42, eval<Int>("42"))
                    }
                    hold("Array.from(item)", "item" to original) { converted ->
                        py {
                            val destination = runtime
                            copy(converted) { copied -> assertEquals(listOf(7, 8), read<List<Int>>(copied)) }
                            val stored = trace.events.filterIsInstance<RuntimeDispatchStarted>().single {
                                it.sessionId == destination.sessionId && it.operation == "store"
                            }
                            assertEquals(listOf(source.sessionId), stored.inputSessionIds)
                        }
                    }
                    assertEquals(2, eval<Int>("item.length", "item" to original))
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
    }
}
