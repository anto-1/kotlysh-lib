package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinIdentifierDispatchTest {
    @Test
    fun `quoted runtime calls and Unicode template names execute exact source`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val π = "'greek'"
        val café = "'accent'"
        val value٢ = "'digit'"
        val 𐐀 = "'astral'"
        val value𝟘 = "'astral-digit'"
        val `target host` = "'space'"
        withRuntimeTrace(trace) {
            `py` {
                for ((result, expected) in listOf(
                    "print($π)"() to "greek",
                    "print($café)"() to "accent",
                    "print($value٢)"() to "digit",
                    "print($𐐀)"() to "astral",
                    "print($value𝟘)"() to "astral-digit",
                    "print($`target host`)"() to "space",
                )) {
                    assertTrue(result.ok)
                    assertEquals(listOf(expected), result.stdout)
                    assertTrue(result.stderr.isEmpty())
                }
            }
        }
        val starts = trace.events.filterIsInstance<RuntimeDispatchStarted>()
        assertEquals(listOf("greek", "accent", "digit", "astral", "astral-digit", "space").map {
            MessageDigest.getInstance("SHA-256").digest("print('$it')".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }, starts.map { it.scriptSha256 })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().all { it.exitCode == 0 })
        assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
    }
}
