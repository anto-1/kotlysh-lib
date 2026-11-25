package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NativeTextBoundaryTest {
    @Test
    fun directOutputPreservesUtf8AcrossReadBuffersAndAtEof() {
        val text = "é雪😀".repeat(3_000)
        nativeExec {
            val result = execute("/bin/sh", "-c", """command printf '%s' "${'$'}1"; command printf '%s' "${'$'}1" >&2""", "--", text)
            assertTrue(result.ok)
            assertTrue(result.stdout.single() == text, "Direct stdout changed Unicode across read buffers")
            assertTrue(result.stderr.single() == text, "Direct stderr changed Unicode across read buffers")
        }
    }

    @Test
    fun shellOutputPreservesUtf8AcrossBothReadBuffersAndDispatches() {
        val text = "é雪😀".repeat(3_000)
        nativeSh {
            repeat(2) {
                val result = "command printf '%s\\n' '$text'; command printf '%s\\n' '$text' >&2"(timeout = 3.seconds)
                assertTrue(result.ok)
                assertTrue(result.stdout.single() == text, "Shell stdout changed Unicode across read buffers")
                assertTrue(result.stderr.single() == text, "Shell stderr changed Unicode across read buffers")
            }
        }
    }

    @Test
    fun nulInExecutableIsRejectedLocally() {
        assertInvalid { execute("/usr/bin/printf\u0000other", "%s", "wrong") }
    }

    @Test
    fun nulInArgumentsIsRejectedLocally() {
        assertInvalid { execute("/usr/bin/printf", "%s", "prefix\u0000lost") }
    }

    @Test
    fun nulInWorkingDirectoryIsRejectedLocally() {
        assertInvalid { execute("/bin/pwd", cwd = "/tmp\u0000/wrong") }
    }

    @Test
    fun nulInEnvironmentNamesAndValuesIsRejectedLocally() {
        assertInvalid { execute("/usr/bin/env", env = mapOf("KOTLYSH_NATIVE_TEXT\u0000wrong" to "value")) }
        assertInvalid { execute("/usr/bin/env", env = mapOf("KOTLYSH_NATIVE_TEXT" to "prefix\u0000lost")) }
    }

    @Test
    fun malformedUtf16CannotBeReplacedDuringArgumentEncoding() {
        for (text in listOf("\uD800", "\uDC00", "prefix\uD800suffix")) {
            assertInvalid { execute("/usr/bin/printf", "%s", text) }
        }
    }

    @Test
    fun malformedUtf16IsRejectedInEveryOtherLaunchField() {
        val text = "\uD800wrong"
        assertInvalid { execute(text) }
        assertInvalid { execute("/bin/pwd", cwd = text) }
        assertInvalid { execute("/usr/bin/env", env = mapOf(text to "value")) }
        assertInvalid { execute("/usr/bin/env", env = mapOf("KOTLYSH_NATIVE_TEXT" to text)) }
    }

    @Test
    fun invalidShellPathsAreRejectedBeforeSessionAcquisition() {
        for (path in listOf("", " \t", "/bin/sh\u0000wrong", "\uD800", "\uDC00")) {
            assertFailsWith<IllegalArgumentException> { NativeShellSession(path).close() }
        }
    }

    @Test
    fun emptyAndEqualsContainingEnvironmentNamesAreRejectedLocally() {
        for (name in listOf("", "A=B")) {
            assertInvalid { execute("/usr/bin/env", env = mapOf(name to "value")) }
        }
    }

    @Test
    fun malformedOutputAndNulDataKeepTheirCapturePolicy() {
        nativeExec {
            val result = execute("/usr/bin/printf", "\\377\\303\\n\\000tail\\303")
            assertTrue(result.ok)
            assertEquals(listOf("\uFFFD\uFFFD", "\u0000tail\uFFFD"), result.stdout)
            assertTrue(result.stderr.isEmpty())
        }
    }

    @Test
    fun emptyAndUnicodeArgumentsAndEnvironmentRemainLiteral() {
        val text = "é 雪 😀; & ' \""
        nativeExec {
            val result = execute("/bin/sh", "-c", """command printf '[%s]|%s|%s' "${'$'}1" "${'$'}2" "${'$'}KOTLYSH_NATIVE_TEXT";""",
                "--", "", text, env = mapOf("KOTLYSH_NATIVE_TEXT" to text, "KOTLYSH_NATIVE_EMPTY" to ""))
            assertTrue(result.ok)
            assertEquals("[]|$text|$text", result.stdout.single())
        }
    }

    private fun assertInvalid(block: NativeExecScope.() -> Unit) {
        nativeExec {
            assertFailsWith<IllegalArgumentException> { block() }
            assertEquals("ready", execute("/usr/bin/printf", "%s", "ready").stdout.single())
        }
    }
}
