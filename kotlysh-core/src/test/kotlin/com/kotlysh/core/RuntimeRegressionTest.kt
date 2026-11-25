package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeRegressionTest {
    @Test
    fun `python syntax errors and SystemExit return failed dispatches`(): Unit = runBlocking {
        py {
            assertFalse("if:"().ok)
            assertFalse("raise SystemExit(0)"().ok)
            assertEquals("still usable", "print('still usable')"().stdout.first())
        }
    }

    @Test
    fun `python exceptions return failure and leave the session usable`(): Unit = runBlocking {
        py {
            val failed = "raise ValueError('expected failure')"()
            assertFalse(failed.ok)
            assertEquals(1, failed.exitCode)
            assertTrue(failed.stderrText.contains("ValueError: expected failure"))
            assertEquals("42", "print(6 * 7)"().stdout.first())
        }
    }

    @Test
    fun `shell output without a newline survives completion framing`(): Unit = runBlocking {
        sh {
            val result = "printf payload; printf diagnostic >&2"()
            assertEquals(listOf("payload"), result.stdout)
            assertEquals(listOf("diagnostic"), result.stderr)
        }
    }

    @Test
    fun `python output that resembles a prompt is user data`(): Unit = runBlocking {
        py {
            val result = "print('>>> user data'); print('... more data')"()
            assertEquals(listOf(">>> user data", "... more data"), result.stdout)
        }
    }

    @Test
    fun `python source preserves triple quotes and backslashes`(): Unit = runBlocking {
        py {
            val result = "print('''triple quoted'''); print(r'C:\\data\\file')"()
            assertEquals(listOf("triple quoted", "C:\\data\\file"), result.stdout)
        }
    }
}
