package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StringLiteralsTest {
    private val value = "quotes: '\" triple: ''' backslash: \\ dollars: \$HOME; Unicode: café 😀\nnext\tline"

    @Test
    fun `shell and python preserve transferred text as data`(): Unit = runBlocking {
        val fromShell = sh { "printf '%s' ${shLiteral(value)}"().stdoutText }
        assertEquals(value, fromShell)
        val fromPython = py { "print(${pythonLiteral(fromShell)}, end='')"().stdoutText }
        assertEquals(value, fromPython)
    }

    @Test
    fun `javascript preserves transferred text as data`(): Unit = runBlocking {
        val result = node { "process.stdout.write(${jsLiteral(value)})"().stdoutText }
        assertEquals(value, result)
    }

    @Test
    fun `python and javascript literals preserve control characters`(): Unit = runBlocking {
        val control = "\u0000\u0001\b\u000C\u2028\u2029"
        val python = py { "print([ord(c) for c in ${pythonLiteral(control)}])"().stdout.first() }
        val javascript = node {
            "console.log(JSON.stringify(Array.from(${jsLiteral(control)}, c => c.codePointAt(0))))"()
                .stdout.first()
        }
        assertEquals("[0, 1, 8, 12, 8232, 8233]", python)
        assertEquals("[0,1,8,12,8232,8233]", javascript)
    }

    @Test
    fun `shell rejects a NUL argument`() {
        assertFailsWith<IllegalArgumentException> { shLiteral("\u0000") }
    }
}
