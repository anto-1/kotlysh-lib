package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinUnicodeVersionDispatchTest {
    @Test
    fun `post-compiler Unicode stays literal while quoted names execute on the JDK 21 runtime`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val value = "fixed"
        val `𞊐` = "'quoted'"
        val suffixes = listOf("𞊐", "𖩰", "𞓐", "𞓰", "Ꟑ", "ࡰ", "𐾰", "𑤀")
        val expected = suffixes.map { value + it } + suffixes.map { "$" + it } + "quoted"
        withRuntimeTrace(trace) {
            py {
                val results = listOf(
                    "print('$value𞊐')"(), "print('$value𖩰')"(), "print('$value𞓐')"(), "print('$value𞓰')"(),
                    "print('$valueꟐ')"(), "print('$valueࡰ')"(), "print('$value𐾰')"(), "print('$value𑤀')"(),
                    "print('$𞊐')"(), "print('$𖩰')"(), "print('$𞓐')"(), "print('$𞓰')"(),
                    "print('$Ꟑ')"(), "print('$ࡰ')"(), "print('$𐾰')"(), "print('$𑤀')"(),
                    "print($`𞊐`)"(),
                )
                assertTrue(results.all { it.ok && it.stderr.isEmpty() })
                assertEquals(expected.map(::listOf), results.map { it.stdout })
            }
        }
        assertEquals(expected.map { value ->
            MessageDigest.getInstance("SHA-256").digest("print('$value')".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }, trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.scriptSha256 })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchCompleted>().all { it.exitCode == 0 })
        assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
    }
}
