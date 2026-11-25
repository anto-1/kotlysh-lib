package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecDslTest {
    @Test
    fun `concurrent output streams preserve every captured line`(): Unit = runBlocking {
        val buffer = RuntimeOutputBuffer()
        val result = exec(
            listOf("/bin/bash", "--noprofile", "--norc", "-c",
                "for ((i=0;i<2000;i++)); do printf 'out:%s\\n' \"\$i\"; printf 'err:%s\\n' \"\$i\" >&2; done"),
            output = buffer,
        )
        assertTrue(result.ok)
        assertEquals((0 until 2000).map { "out:$it" }, result.stdout)
        assertEquals((0 until 2000).map { "err:$it" }, result.stderr)
        assertEquals(result.stdout, buffer.stdout)
        assertEquals(result.stderr, buffer.stderr)
    }

    @Test
    fun `exec captures stdout stderr and exit code`(): Unit = runBlocking {
        val result = exec(
            listOf("bash", "-lc", "printf 'out\\n'; printf 'err\\n' >&2; exit 7"),
            timeout = null,
        )

        assertEquals(listOf("out"), result.stdout)
        assertEquals(listOf("err"), result.stderr)
        assertEquals(7, result.exitCode)
        assertFalse(result.ok)
    }

    @Test
    fun `exec streams output into sink`(): Unit = runBlocking {
        val buffer = RuntimeOutputBuffer()

        val result = exec(
            listOf("bash", "-lc", "printf 'out\\n'; printf 'err\\n' >&2"),
            timeout = null,
            output = buffer,
        )

        assertTrue(result.ok)
        assertEquals(listOf("out"), buffer.stdout)
        assertEquals(listOf("err"), buffer.stderr)
    }
}
