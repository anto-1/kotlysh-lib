package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StringLiteralBoundaryTest {
    private val unicodeCases = listOf(
        "",
        "\uD800",
        "\uDC00",
        "\uD800x\uDC00",
        "\uD800\uD800\uDC00\uDC00",
        "\uD83D\uDE00\uDBFF\uDFFF",
        "\u0000\u0001\b\u000C\r\n\t\u2028\u2029\uFEFF",
        "quotes '\" backslash \\ dollars \$HOME; \$(printf injected); café",
    )

    @Test
    fun `python preserves lone surrogates and Unicode scalar pairs`(): Unit = runBlocking {
        py {
            for (value in unicodeCases) {
                val result = "print([ord(c) for c in ${pythonLiteral(value)}])"()
                assertTrue(result.ok, result.stderrText)
                assertEquals(value.codePoints().toArray().toList().toString(), result.stdoutText)
            }
        }
    }

    @Test
    fun `javascript preserves every UTF16 code unit`(): Unit = runBlocking {
        node {
            for (value in unicodeCases) {
                val result = """
                    (() => {
                        const value = ${jsLiteral(value)};
                        console.log(JSON.stringify(Array.from({length: value.length}, (_, index) => value.charCodeAt(index))));
                    })();
                """()
                assertTrue(result.ok, result.stderrText)
                assertEquals(value.map(Char::code).joinToString(",", "[", "]"), result.stdoutText)
            }
        }
    }

    @Test
    fun `bash preserves word bytes inside indented multiline dispatches`(): Unit = runBlocking {
        val values = listOf(
            "",
            "a\rb",
            "a\r\nb",
            "a\n    b",
            "\n    a\n    b\n",
            "\r\n'\"\\\t\n\r",
            (1..31).map(Int::toChar).joinToString(""),
            "Unicode café 😀; '\" \$HOME \$(printf injected) `printf injected`",
        )
        sh {
            for (value in values) {
                val result = """
                    set -- ${shLiteral(value)}
                    test "${'$'}#" -eq 1 || exit 91
                    printf '%s' "${'$'}1" | python3 -c 'import sys; print(list(sys.stdin.buffer.read()))'
                """()
                assertTrue(result.ok, result.stderrText)
                assertEquals(value.toByteArray(Charsets.UTF_8).map { it.toInt() and 255 }.toString(), result.stdoutText)
            }
        }
    }

    @Test
    fun `bash rejects unpaired surrogates before UTF8 can replace them`() {
        for (value in unicodeCases.filter { it.codePoints().anyMatch { code -> code in 0xD800..0xDFFF } }) {
            val failure = assertFailsWith<IllegalArgumentException> { shLiteral(value) }
            assertTrue(failure.message.orEmpty().contains("unpaired UTF-16 surrogate"))
        }
    }

    @Test
    fun `ordinary Bash literal spelling and apostrophe quoting remain unchanged`() {
        assertEquals("'plain data'", shLiteral("plain data"))
        assertEquals("'a'\"'\"'b'", shLiteral("a'b"))
    }
}
