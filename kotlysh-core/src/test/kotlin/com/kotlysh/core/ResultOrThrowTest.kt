package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ResultOrThrowTest {
    @Test
    fun `a successful result is returned unchanged for chaining`(): Unit = runBlocking {
        sh {
            val result = "printf 'ready\\n'"()
            assertSame(result, result.orThrow())
            assertEquals("ready", "printf 'ready\\n'"().orThrow().stdoutText)
        }
    }

    @Test
    fun `a failed shell command reports its status and stderr and keeps the session`(): Unit = runBlocking {
        sh {
            val failure = assertFailsWith<RuntimeCallException> {
                "printf 'partial\\n'; printf 'no such thing\\n' >&2; (exit 7)"().orThrow()
            }
            assertEquals("sh scriptlet failed with exit code 7: no such thing", failure.message)
            assertEquals(7, failure.execution.exitCode)
            assertEquals(listOf("partial"), failure.execution.stdout)
            // Only the explicit check throws; an ordinary failure leaves the session usable.
            assertEquals("alive", "echo alive"().orThrow().stdoutText)
        }
    }

    @Test
    fun `a failed Python scriptlet reports through the same exception`(): Unit = runBlocking {
        py {
            val failure = assertFailsWith<RuntimeCallException> { "raise ValueError('bad input')"().orThrow() }
            assertEquals("ValueError", failure.errorType)
            assertEquals("py scriptlet failed with exit code 1: ValueError: bad input", failure.message)
        }
    }

    @Test
    fun `deadlines are failures even without an exit code`() {
        val expired = RuntimeResult("sh", emptyList(), emptyList(), exitCode = null, timedOut = true)
        assertEquals("sh scriptlet failed after timeout", assertFailsWith<RuntimeCallException> { expired.orThrow() }.message)

        runBlocking {
            sh {
                val failure = assertFailsWith<RuntimeCallException> {
                    "sleep 5"(timeout = 200.milliseconds).orThrow()
                }
                assertTrue(failure.execution.timedOut)
            }
        }
    }

    @Test
    fun `the structured failure message is unchanged`() {
        val execution = RuntimeResult("py", emptyList(), listOf("first", "second"), 1)
        assertEquals("py request failed: first\nsecond", RuntimeCallException(execution).message)
    }
}
