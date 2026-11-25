package com.kotlysh.core

import com.kotlysh.core.shLiteral as encodeShellData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(20)
class StringLiteralResolutionTest {
    @Test
    fun `loop and catch bindings can invoke unrelated encoder shaped values`(): Unit = runBlocking {
        val value = "\$(printf injected >&2)"
        for (shLiteral in listOf<(String) -> String>({ it })) {
            sh {
                assertEquals(listOf("injected"), "printf '%s' ${shLiteral(value)}"().stderr)
                val safe = "printf '%s' ${encodeShellData(value)}"()
                assertTrue(safe.ok)
                assertEquals(listOf(value), safe.stdout)
                assertTrue(safe.stderr.isEmpty())
            }
        }
        class CallableEncoderError : RuntimeException() {
            operator fun invoke(value: String) = value
        }
        try {
            throw CallableEncoderError()
        } catch (shLiteral: CallableEncoderError) {
            sh {
                assertEquals(listOf("injected"), "printf '%s' ${shLiteral(value)}"().stderr)
                val safe = "printf '%s' ${encodeShellData(value)}"()
                assertTrue(safe.ok)
                assertEquals(listOf(value), safe.stdout)
                assertTrue(safe.stderr.isEmpty())
            }
        }
    }

    @Test
    fun `unrelated qualified helpers do not inherit the literal encoder contracts`(): Unit = runBlocking {
        sh {
            val value = "\$(printf injected >&2)"
            val raw = "printf '%s' ${UntrustedEncoders.shLiteral(value)}"()
            assertTrue(raw.ok)
            assertEquals(listOf("injected"), raw.stderr)
            val safe = "printf '%s' ${com.kotlysh.core.shLiteral(UntrustedEncoders.shLiteral(value))}"()
            assertTrue(safe.ok, safe.stderrText)
            assertEquals(listOf(value), safe.stdout)
            assertTrue(safe.stderr.isEmpty())
        }
        py {
            val value = "(__import__('builtins').print('injected'), 'payload')[1]"
            val raw = "print(${UntrustedEncoders.pythonLiteral(value)})"()
            assertTrue(raw.ok, raw.stderrText)
            assertEquals(listOf("injected", "payload"), raw.stdout)
            val safe = "print(${pythonLiteral(UntrustedEncoders.pythonLiteral(value))})"()
            assertTrue(safe.ok, safe.stderrText)
            assertEquals(listOf(value), safe.stdout)
        }
        node {
            val value = "(console.log('injected'), 'payload')"
            val raw = "console.log(${UntrustedEncoders.jsLiteral(value)})"()
            assertTrue(raw.ok, raw.stderrText)
            assertEquals(listOf("injected", "payload"), raw.stdout)
            val safe = "console.log(${jsLiteral(UntrustedEncoders.jsLiteral(value))})"()
            assertTrue(safe.ok, safe.stderrText)
            assertEquals(listOf(value), safe.stdout)
        }
    }

    @Test
    fun `local functions can shadow unqualified literal encoders`(): Unit = runBlocking {
        fun shLiteral(value: String) = value
        fun pythonLiteral(value: String) = value
        fun jsLiteral(value: String) = value
        sh {
            val value = "\$(printf injected >&2)"
            assertEquals(listOf("injected"), "printf '%s' ${shLiteral(value)}"().stderr)
            val safe = "printf '%s' ${com.kotlysh.core.shLiteral(shLiteral(value))}"()
            assertEquals(listOf(value), safe.stdout)
            assertTrue(safe.stderr.isEmpty())
        }
        py {
            val value = "(__import__('builtins').print('injected'), 'payload')[1]"
            assertEquals(listOf("injected", "payload"), "print(${pythonLiteral(value)})"().stdout)
            assertEquals(listOf(value), "print(${com.kotlysh.core.pythonLiteral(pythonLiteral(value))})"().stdout)
        }
        node {
            val value = "(console.log('injected'), 'payload')"
            assertEquals(listOf("injected", "payload"), "console.log(${jsLiteral(value)})"().stdout)
            assertEquals(listOf(value), "console.log(${com.kotlysh.core.jsLiteral(jsLiteral(value))})"().stdout)
        }
    }

    @Test
    fun `function parameters override imported encoder names`(): Unit = runBlocking {
        suspend fun runTask(shLiteral: (String) -> String) {
            sh {
                val value = "\$(printf injected >&2)"
                assertEquals(listOf("injected"), "printf '%s' ${shLiteral(value)}"().stderr)
                val safe = "printf '%s' ${encodeShellData(shLiteral(value))}"()
                assertEquals(listOf(value), safe.stdout)
                assertTrue(safe.stderr.isEmpty())
            }
        }
        runTask { it }
    }

    @Test
    fun `package shaped local values override qualified calls while canonical aliases remain safe`(): Unit = runBlocking {
        val com = object {
            val kotlysh = object {
                val core = object {
                    fun shLiteral(value: String) = value
                }
            }
        }
        sh {
            val value = "\$(printf injected >&2)"
            val raw = "printf '%s' ${com.kotlysh.core.shLiteral(value)}"()
            assertEquals(listOf("injected"), raw.stderr)
            val safe = "printf '%s' ${encodeShellData(value)}"()
            assertEquals(listOf(value), safe.stdout)
            assertTrue(safe.stderr.isEmpty())
        }
    }
}

private object UntrustedEncoders {
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
}
