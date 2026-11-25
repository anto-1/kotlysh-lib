package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ShDslTest {
    @Test
    fun `shell session preserves state between invokes`(): Unit = runBlocking {
        var pwd = ""
        var mode = ""

        sh {
            "cd /tmp"()
            "export KOTLYSH_TEST_MODE=session"()

            pwd = "pwd"().stdout.first()
            mode = "echo \$KOTLYSH_TEST_MODE"().stdout.first()
        }

        assertEquals("/tmp", pwd)
        assertEquals("session", mode)
    }

    @Test
    fun `shell invoke captures exit code`(): Unit = runBlocking {
        var result: RuntimeResult? = null

        sh {
            result = "false"()
        }

        assertEquals(1, result?.exitCode)
        assertFalse(result?.ok ?: true)
    }

    @Test
    fun `drain projects and parses captured stdout without hiding status`(): Unit = runBlocking {
        sh {
            assertEquals("alpha\nbeta", "printf 'alpha\\nbeta\\n'"().drain())
            assertEquals(42, "printf ' 42\\n'"().drain { it.trim().toInt() })

            val failed = "printf partial; exit 9"()
            assertEquals("partial", failed.drain())
            assertFalse(failed.ok)
        }
    }

    @Test
    fun `shell invoke can stream output into a sink`(): Unit = runBlocking {
        val buffer = RuntimeOutputBuffer()
        var result: RuntimeResult? = null

        sh {
            result = "printf 'one\\n'; printf 'two\\n' >&2"(
                output = buffer,
            )
        }

        assertEquals(listOf("one"), result?.stdout)
        assertEquals(listOf("two"), result?.stderr)
        assertEquals(listOf("one"), buffer.stdout)
        assertEquals(listOf("two"), buffer.stderr)
    }

    @Test
    fun `idle timeout is reported`(): Unit = runBlocking {
        var result: RuntimeResult? = null

        sh {
            result = "sleep 2; echo done"(timeout = 3.seconds, idleTimeout = 100.milliseconds)
        }

        assertTrue(result?.idleTimedOut == true)
    }
}
