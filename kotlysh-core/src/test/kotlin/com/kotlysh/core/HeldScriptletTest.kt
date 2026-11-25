package com.kotlysh.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Timeout(60)
class HeldScriptletTest {
    @Test
    fun `a held scriptlet dispatches nothing until it is invoked`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val probes = mapOf(
                    "user" to "id -un"(hold),
                    "count" to "echo \$((++count))"(hold),
                )
                assertEquals(0, trace.events.count { it is RuntimeDispatchStarted })

                assertTrue(probes.getValue("user")().orThrow().stdoutText.isNotBlank())
                // Each invocation is an ordinary dispatch in this session.
                assertEquals(listOf("1", "2"), List(2) { probes.getValue("count")().stdoutText })
                assertEquals(3, trace.events.count { it is RuntimeDispatchStarted })
            }
        }
    }

    @Test
    fun `held scriptlets outlive their block and run in any session of their runtime`(): Unit = runBlocking {
        val bump = sh { "echo \$((++count))"(hold) }
        val steps = py { listOf("print('one')"(hold), "print('two')"(hold)) }

        assertEquals("sh", bump.runtime)
        assertEquals("echo \$((++count))", bump.script)
        // State belongs to the invoking session, not to the unit.
        assertEquals(List(3) { "1" }, List(3) { async { sh { bump().stdoutText } } }.awaitAll())
        assertEquals(listOf("one", "two"), py { steps.map { it().orThrow().stdoutText } })
    }

    @Test
    fun `a held scriptlet keeps multiline normalization and dispatch options`(): Unit = runBlocking {
        sh {
            val unit = """
                printf 'first\n'
                printf 'second\n'
            """(hold)
            assertEquals("printf 'first\\n'\nprintf 'second\\n'", unit.script)
            assertEquals(listOf("first", "second"), unit().stdout)

            val lines = RuntimeOutputBuffer()
            unit(output = lines)
            assertEquals(listOf("first", "second"), lines.stdout)
            assertTrue("sleep 5"(hold)(timeout = 200.milliseconds).timedOut)
        }
    }

    @Test
    fun `a held scriptlet is refused by another runtime without disturbing it`(): Unit = runBlocking {
        val shell = sh { "echo shell"(hold) }
        py {
            val failure = assertFailsWith<IllegalArgumentException> { shell() }
            assertEquals("A scriptlet held for sh cannot run in a py session", failure.message)
            assertEquals(4, eval<Int>("2 + 2"))
            // The modifier and the reference-retaining hold(...) coexist in structured scopes.
            assertEquals(3, hold("[1, 2, 3]") { items -> eval<Int>("len(items)", "items" to items) })
            assertEquals("held", "print('held')"(hold)().stdoutText)
        }
    }
}
