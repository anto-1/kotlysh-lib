package com.kotlysh.core

import com.kotlysh.core.shLiteral
import com.kotlysh.core.shLiteral as encodeShellData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(20)
class StringLiteralConstructorScopeTest {
    @Test
    fun `plain primary parameters bind initialization but not member functions or getters`(): Unit = runBlocking {
        class Holder(shLiteral: (String) -> String, private val value: String) {
            val initializerSource = "printf '%s' ${shLiteral(value)}"
            val delegatedSource by lazy { "printf '%s' ${shLiteral(value)}" }
            val initSource: String
            init { initSource = "printf '%s' ${shLiteral(value)}" }
            fun memberSource() = "printf '%s' ${shLiteral(value)}"
            val getterSource get() = "printf '%s' ${shLiteral(value)}"
            fun nestedMemberSource(): String {
                val task = { "printf '%s' ${shLiteral(value)}" }
                return task()
            }
        }
        val value = "\$(printf injected >&2)"
        val holder = Holder({ it }, value)
        sh {
            for (source in listOf(holder.initializerSource, holder.delegatedSource, holder.initSource)) {
                val raw = source()
                assertTrue(raw.ok, raw.stderrText)
                assertEquals(listOf("injected"), raw.stderr)
            }
            for (source in listOf(holder.memberSource(), holder.getterSource, holder.nestedMemberSource())) {
                val safe = source()
                assertTrue(safe.ok, safe.stderrText)
                assertEquals(listOf(value), safe.stdout)
                assertTrue(safe.stderr.isEmpty())
            }
        }
    }

    @Test
    fun `secondary parameters bind their body and delegation in all three runtimes`(): Unit = runBlocking {
        class Holder {
            val shell: String
            val python: String
            val javascript: String
            constructor(shLiteral: (String) -> String, pythonLiteral: (String) -> String, jsLiteral: (String) -> String) {
                shell = "printf '%s' ${shLiteral("\$(printf injected >&2)")}"
                python = "print(${pythonLiteral("(print('injected'), 'payload')[1]")})"
                javascript = "console.log(${jsLiteral("(console.log('injected'), 'payload')")})"
            }
        }
        class DelegatingHolder : ConstructorScriptHolder {
            constructor(shLiteral: (String) -> String, value: String) : super("printf '%s' ${shLiteral(value)}")
        }
        val holder = Holder({ it }, { it }, { it })
        sh {
            assertEquals(listOf("injected"), holder.shell().stderr)
            assertEquals(listOf("injected"), DelegatingHolder({ it }, "\$(printf injected >&2)").source().stderr)
            val value = "\$(printf injected >&2)"
            val safe = "printf '%s' ${encodeShellData(value)}"()
            assertTrue(safe.ok)
            assertEquals(listOf(value), safe.stdout)
            assertTrue(safe.stderr.isEmpty())
        }
        py {
            assertEquals(listOf("injected", "payload"), holder.python().stdout)
            val value = "(print('injected'), 'payload')[1]"
            val safe = "print(${com.kotlysh.core.pythonLiteral(value)})"()
            assertTrue(safe.ok, safe.stderrText)
            assertEquals(listOf(value), safe.stdout)
        }
        node {
            assertEquals(listOf("injected", "payload"), holder.javascript().stdout)
            val value = "(console.log('injected'), 'payload')"
            val safe = "console.log(${com.kotlysh.core.jsLiteral(value)})"()
            assertTrue(safe.ok, safe.stderrText)
            assertEquals(listOf(value), safe.stdout)
        }
    }

    @Test
    fun `primary defaults and super calls use constructor parameter bindings`(): Unit = runBlocking {
        class Defaults(shLiteral: (String) -> String, value: String, val source: String = "printf '%s' ${shLiteral(value)}")
        class Derived(shLiteral: (String) -> String, value: String) : ConstructorScriptHolder("printf '%s' ${shLiteral(value)}")
        val value = "\$(printf injected >&2)"
        sh {
            for (source in listOf(Defaults({ it }, value).source, Derived({ it }, value).source)) {
                val raw = source()
                assertTrue(raw.ok)
                assertEquals(listOf("injected"), raw.stderr)
            }
            val safe = "printf '%s' ${encodeShellData(value)}"()
            assertTrue(safe.ok)
            assertEquals(listOf(value), safe.stdout)
            assertTrue(safe.stderr.isEmpty())
        }
    }

    @Test
    fun `package shaped constructor parameters stop shadowing after initialization`(): Unit = runBlocking {
        class Primary(com: ConstructorPackageRoot, private val value: String) {
            val source = "printf '%s' ${com.kotlysh.core.shLiteral(value)}"
            val encoded = "printf '%s' ${encodeShellData(value)}"
            fun memberSource() = "printf '%s' ${com.kotlysh.core.shLiteral(value)}"
        }
        class Secondary {
            val source: String
            constructor(com: ConstructorPackageRoot, value: String) {
                source = "printf '%s' ${com.kotlysh.core.shLiteral(value)}"
            }
        }
        val value = "\$(printf injected >&2)"
        val holder = Primary(ConstructorPackageRoot(), value)
        sh {
            for (source in listOf(holder.source, Secondary(ConstructorPackageRoot(), value).source)) {
                val raw = source()
                assertTrue(raw.ok)
                assertEquals(listOf("injected"), raw.stderr)
            }
            for (source in listOf(holder.encoded, holder.memberSource())) {
                val safe = source()
                assertTrue(safe.ok)
                assertEquals(listOf(value), safe.stdout)
                assertTrue(safe.stderr.isEmpty())
            }
        }
    }
}

private open class ConstructorScriptHolder(val source: String)
private class ConstructorPackageRoot { val kotlysh = ConstructorPackageNamespace() }
private class ConstructorPackageNamespace { val core = ConstructorEncoderNamespace() }
private class ConstructorEncoderNamespace { fun shLiteral(value: String) = value }
