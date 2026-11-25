package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptTextTest {
    @Test
    fun `multiline script literals have file-like whitespace semantics`() {
        val script = "\r\n        def answer():\r\n            return 42\r\n\r\n        print(answer())\r\n    "

        assertEquals(
            "def answer():\n    return 42\n\nprint(answer())",
            script.normalizeKotlyshScript(),
        )
    }

    @Test
    fun `single-line commands retain intentional leading whitespace`() {
        assertEquals("  printf ready", "  printf ready".normalizeKotlyshScript())
    }

    @Test
    fun `python executes a raw multiline literal without trimIndent ceremony`(): Unit = runBlocking {
        val answer = py {
            """
                def answer():
                    return 42

                print(answer())
            """().drain()
        }

        assertEquals("42", answer)
    }
}
