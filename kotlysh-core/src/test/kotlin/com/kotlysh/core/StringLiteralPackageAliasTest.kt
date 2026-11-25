package com.kotlysh.core

import com.kotlysh.core.UntrustedPackageRoot as com
import com.kotlysh.core.shLiteral as encodeShellData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(20)
class StringLiteralPackageAliasTest {
    @Test
    fun `import aliases can hide package roots without hiding canonical helper aliases`(): Unit = runBlocking {
        sh {
            val value = "\$(printf injected >&2)"
            val raw = "printf '%s' ${com.kotlysh.core.shLiteral(value)}"()
            assertTrue(raw.ok)
            assertEquals(listOf("injected"), raw.stderr)
            val safe = "printf '%s' ${encodeShellData(value)}"()
            assertTrue(safe.ok)
            assertEquals(listOf(value), safe.stdout)
            assertTrue(safe.stderr.isEmpty())
        }
    }
}

private object UntrustedPackageRoot {
    val kotlysh = UntrustedKotlysh()
}
private class UntrustedKotlysh {
    val core = UntrustedCore()
}
private class UntrustedCore {
    fun shLiteral(value: String) = value
}
