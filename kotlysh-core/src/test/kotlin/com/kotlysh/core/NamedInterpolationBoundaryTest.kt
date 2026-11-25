package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamedInterpolationBoundaryTest {
    @Test
    fun `shell encoder protects the whole expression regardless of value names`(): Unit = runBlocking {
        val inputLiteral = "ordinary data"
        val suffixEncoded = "; boundary_tripwire=1; printf INJECTED; #"
        sh {
            assertTrue("boundary_tripwire=0"().ok)
            val result = "printf '%s' ${shLiteral(inputLiteral + suffixEncoded)}"()
            assertTrue(result.ok, result.stderrText)
            assertEquals(inputLiteral + suffixEncoded, result.stdoutText)
            assertTrue(result.stderr.isEmpty())
            assertEquals(listOf("0"), "printf '%s' \"${'$'}boundary_tripwire\""().stdout)
        }
    }

    @Test
    fun `python encoder protects the whole expression regardless of value names`(): Unit = runBlocking {
        val inputLiteral = "ordinary data"
        val suffixEncoded = "\"; boundary_tripwire = 1; print('INJECTED'); #"
        py {
            assertTrue("boundary_tripwire = 0"().ok)
            val result = """
                received = ${pythonLiteral(inputLiteral + suffixEncoded)}
                print(received, end='')
            """()
            assertTrue(result.ok, result.stderrText)
            assertEquals(inputLiteral + suffixEncoded, result.stdoutText)
            assertTrue(result.stderr.isEmpty())
            assertEquals(0, eval<Int>("boundary_tripwire"))
        }
    }

    @Test
    fun `javascript encoder protects the whole expression regardless of value names`(): Unit = runBlocking {
        val inputLiteral = "ordinary data"
        val suffixEncoded = "\"; globalThis.boundary_tripwire = 1; console.log('INJECTED'); //"
        node {
            assertTrue("globalThis.boundary_tripwire = 0"().ok)
            val result = """
                const received = ${jsLiteral(inputLiteral + suffixEncoded)};
                process.stdout.write(received);
            """()
            assertTrue(result.ok, result.stderrText)
            assertEquals(inputLiteral + suffixEncoded, result.stdoutText)
            assertTrue(result.stderr.isEmpty())
            assertEquals(0, eval<Int>("globalThis.boundary_tripwire"))
        }
    }
}
