package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PyDslTest {
    @Test
    fun `python session preserves variables between invokes`(): Unit = runBlocking {
        var result = ""

        py {
            "x = 41"()
            "x += 1"()
            result = "print(x)"().stdout.first()
        }

        assertEquals("42", result)
    }
}
