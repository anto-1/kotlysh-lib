package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class NodePromiseSpeciesTest {
    @Test
    fun `internal expression promises do not invoke user species getters`(): Unit = runBlocking {
        node {
            assertTrue("""
                globalThis.speciesReads = 0;
                Object.defineProperty(Promise, Symbol.species, {
                    configurable: true,
                    get() { speciesReads++; throw new Error('user species'); }
                });
                void 0;
            """().ok)
            assertEquals(42, eval<Int>("40 + 2"))
            hold("({keep: 7})") { reference ->
                assertEquals(7, eval<Int>("held.keep", "held" to reference))
            }
            assertEquals(0, eval<Int>("speciesReads"))
            val explicit = "try { Promise.resolve(7).then(value => value) } catch (error) { console.log(error.message) }; void 0"()
            assertEquals(listOf("user species"), explicit.stdout)
            assertEquals(1, eval<Int>("speciesReads"))
            assertTrue("""
                Object.defineProperty(Promise, Symbol.species, {
                    configurable: true,
                    get() { speciesReads++; return Promise; }
                });
                globalThis.userPromise = Object.freeze(Promise.resolve(42));
                void 0;
            """().ok)
            assertEquals(42, eval<Int>("userPromise"))
            assertEquals(2, eval<Int>("speciesReads"))
            assertEquals(0, eval<Int>("Object.getOwnPropertyNames(userPromise).length"))
        }
    }

    @Test
    fun `internal expression promises do not invoke user constructor getters`(): Unit = runBlocking {
        node {
            assertTrue("""
                globalThis.constructorReads = 0;
                Object.defineProperty(Promise.prototype, 'constructor', {
                    configurable: true,
                    get() { constructorReads++; throw new Error('user constructor'); }
                });
                void 0;
            """().ok)
            assertEquals(42, eval<Int>("42"))
            hold("Object.freeze({keep: 7})") { reference ->
                assertEquals(7, eval<Int>("held.keep", "held" to reference))
            }
            assertEquals(0, eval<Int>("constructorReads"))
            val explicit = "try { Promise.resolve(7).then(value => value) } catch (error) { console.log(error.message) }; void 0"()
            assertEquals(listOf("user constructor"), explicit.stdout)
            assertEquals(1, eval<Int>("constructorReads"))
        }
    }

    @Test
    fun `species overrides cannot hide an expression failure or terminate its session`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            node {
                assertTrue("Object.defineProperty(Promise, Symbol.species, {get() { throw new Error('user species'); }}); void 0"().ok)
                val failure = assertFailsWith<RuntimeCallException> {
                    eval<Int>("(() => { throw new RangeError('actual expression failure'); })()", timeout = 5.seconds)
                }
                assertEquals("RangeError", failure.errorType)
                assertEquals("actual expression failure", failure.errorMessage)
                assertEquals(1, failure.execution.exitCode)
                assertFalse(failure.execution.processExited)
                assertEquals(42, eval<Int>("21 * 2", timeout = 5.seconds))
                assertTrue(runtime.isOpen)
            }
        }
        val failure = trace.events.filterIsInstance<RuntimeDispatchCompleted>().single { it.exitCode == 1 }
        assertEquals("RangeError", failure.errorType)
        assertEquals("actual expression failure", failure.message)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
    }
}
