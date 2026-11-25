package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(20)
class StringLiteralContextTest {
    @Test
    fun `JavaScript block comment contents are not a string expression context`(): Unit = runBlocking {
        val value = "*/ console.log('injected'); /*"
        node {
            val safe = "console.log(${jsLiteral(value)})"()
            assertTrue(safe.ok, safe.stderrText)
            assertEquals(listOf(value), safe.stdout)
            val commented = "/* ${jsLiteral(value)} */ console.log('ready')"()
            assertTrue(commented.ok, commented.stderrText)
            assertEquals(listOf("injected", "ready"), commented.stdout)
        }
    }

    @Test
    fun `Bash encodes one complete word including substitution shaped data`(): Unit = runBlocking {
        val value = "\$(printf injected >&2) ' \" ; \\ café 😀"
        sh {
            val result = "printf '%s' ${shLiteral(value)}"()
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf(value), result.stdout)
            assertTrue(result.stderr.isEmpty())
            val escaped = "printf '%s' \\\"${shLiteral(value)}\\\""()
            assertTrue(escaped.ok, escaped.stderrText)
            assertEquals(listOf("\"$value\""), escaped.stdout)
            assertTrue(escaped.stderr.isEmpty())
        }
    }

    @Test
    fun `Python literal expressions keep quote and statement shaped data inert`(): Unit = runBlocking {
        val value = "'); print('injected'); #"
        py {
            assertEquals(value, evaluate<String>(pythonLiteral(value)).value)
            val result = "print(${pythonLiteral(value)})"()
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf(value), result.stdout)
        }
    }

    @Test
    fun `JavaScript literal expressions keep quote and statement shaped data inert`(): Unit = runBlocking {
        val value = "'); console.log('injected'); //"
        node {
            assertEquals(value, evaluate<String>(jsLiteral(value)).value)
            val result = "console.log(${jsLiteral(value)})"()
            assertTrue(result.ok, result.stderrText)
            assertEquals(listOf(value), result.stdout)
        }
    }

    @Test
    fun `foreign outer quotes change the encoder context and can execute data`(): Unit = runBlocking {
        sh {
            val result = "printf '%s' \"${shLiteral("\$(printf injected >&2)")}\""()
            assertTrue(result.ok)
            assertEquals(listOf("injected"), result.stderr)
            val ansi = "printf '%s' \$'escaped\\'${shLiteral("\$(printf injected >&2)")}'"()
            assertTrue(ansi.ok)
            assertEquals(listOf("injected"), ansi.stderr)
        }
        py {
            val result = "print('${pythonLiteral("'); print('injected'); #")}')"()
            assertTrue(result.ok, result.stderrText)
            assertTrue("injected" in result.stdout)
        }
        node {
            val result = "console.log('${jsLiteral("'); console.log('injected'); //")}')"()
            assertTrue(result.ok, result.stderrText)
            assertTrue("injected" in result.stdout)
        }
    }
}
