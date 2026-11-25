package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(20)
class StructuredErrorTraceTest {
    @Test
    fun `ordinary foreign errors retain typed diagnostics in completed records`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py {
                val failure = assertFailsWith<RuntimeCallException> { eval<Int>("1 / 0") }
                verifyError(trace, failure)
                assertEquals(42, eval<Int>("42"))
            }
            node {
                val failure = assertFailsWith<RuntimeCallException> {
                    eval<Int>("Promise.reject(new TypeError('<html><b>literal diagnostic</b>'))")
                }
                verifyError(trace, failure)
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().all { !it.poisoned })
    }

    @Test
    fun `foreign trace diagnostics are bounded without splitting valid surrogate pairs`(): Unit = runBlocking {
        val type = "x" + "🙂".repeat(5000)
        val message = "y" + "🙂".repeat(5000)
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            node {
                val failure = assertFailsWith<RuntimeCallException> {
                    eval<Int>("Promise.reject(Object.assign(new Error(${jsLiteral(message)}), {name: ${jsLiteral(type)}}))")
                }
                assertEquals(message, failure.execution.response?.get("message")?.jsonPrimitive?.content)
                val event = completed(trace, failure).toTraceJson()
                assertEquals(type.take(4095), event["errorType"]?.jsonPrimitive?.content)
                assertEquals(message.take(4095), event["message"]?.jsonPrimitive?.content)
                assertEquals(42, eval<Int>("42"))
            }
        }
    }

    @Test
    fun `successful dispatches and raw shell failures do not invent structured errors`(): Unit = runBlocking {
        assertTrue(RuntimeDispatchCompleted::class.java.constructors.any { it.parameterCount == 12 })
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            py { assertEquals(42, eval<Int>("42")) }
            sh { assertEquals(7, "exit 7"().exitCode) }
        }
        for (completed in trace.events.filterIsInstance<RuntimeDispatchCompleted>()) {
            val json = completed.toTraceJson()
            assertTrue(json["errorType"] == null || json["errorType"] == JsonNull)
            assertTrue(json["message"] == null || json["message"] == JsonNull)
        }
    }

    private fun completed(trace: RuntimeTraceBuffer, failure: RuntimeCallException): RuntimeDispatchCompleted =
        trace.events.filterIsInstance<RuntimeDispatchCompleted>().single {
            it.sessionId == failure.execution.sessionId && it.dispatchId == failure.execution.dispatchId
        }

    private fun verifyError(trace: RuntimeTraceBuffer, failure: RuntimeCallException) {
        val completed = completed(trace, failure)
        val json = completed.toTraceJson()
        assertEquals(failure.execution.response?.get("type"), json["errorType"])
        assertEquals(failure.execution.response?.get("message"), json["message"])
        assertEquals(1, completed.exitCode)
        assertFalse(completed.processExited)
        assertTrue(failure.execution.stderr.isNotEmpty())
        assertFalse(trace.events.filterIsInstance<RuntimeDispatchFailed>().any { it.dispatchId == completed.dispatchId && it.sessionId == completed.sessionId })
    }
}
