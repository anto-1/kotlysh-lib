package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(20)
class StringLiteralNestedScopeTest {
    private val inputs = NestedLiteralValues(
        "\$(printf injected >&2)",
        "(print('injected'), 'payload')[1]",
        "(console.log('injected'), 'payload')",
    )

    @Test
    fun `static nested scopes use genuine imports despite outer instance encoder names`(): Unit = runBlocking {
        verifySafe(listOf(
            NestedFunctionEncoders.Nested().scripts(inputs),
            NestedFunctionEncoders.Named.scripts(inputs),
            NestedFunctionEncoders.scripts(inputs),
            NestedPropertyEncoders.Nested().scripts(inputs),
            NestedPropertyEncoders.Named.scripts(inputs),
            NestedPropertyEncoders.scripts(inputs),
        ))
    }

    @Test
    fun `inner local and anonymous scopes capture unsafe outer instance encoders`(): Unit = runBlocking {
        val functions = NestedFunctionEncoders()
        val properties = NestedPropertyEncoders({ it }, { it }, { it })
        verifyUnsafe(listOf(
            functions.Inner().scripts(inputs),
            functions.localScripts(inputs),
            functions.anonymousScripts(inputs),
            properties.Inner().scripts(inputs),
        ))
    }

    @Test
    fun `static scopes use the canonical package while inner scopes capture a package shaped member`(): Unit = runBlocking {
        verifySafe(listOf(NestedPackageEncoders.Nested().scripts(inputs), NestedPackageEncoders.scripts(inputs)))
        verifyUnsafe(listOf(NestedPackageEncoders(NestedPackageRoot()).Inner().scripts(inputs)))
    }

    @Test
    fun `singleton and companion encoder bindings remain visible to nested classes`(): Unit = runBlocking {
        verifyUnsafe(listOf(NestedSingletonEncoders.Nested().scripts(inputs), NestedCompanionEncoders.Nested().scripts(inputs)))
    }

    private suspend fun verifySafe(scripts: List<NestedLiteralValues>) {
        sh {
            for (source in scripts) {
                val result = source.shell()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf(inputs.shell), result.stdout)
                assertTrue(result.stderr.isEmpty())
            }
        }
        py {
            for (source in scripts) {
                val result = source.python()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf(inputs.python), result.stdout)
            }
        }
        node {
            for (source in scripts) {
                val result = source.javascript()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf(inputs.javascript), result.stdout)
            }
        }
    }

    private suspend fun verifyUnsafe(scripts: List<NestedLiteralValues>) {
        sh {
            for (source in scripts) {
                val result = source.shell()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf("injected"), result.stderr)
            }
        }
        py {
            for (source in scripts) {
                val result = source.python()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf("injected", "payload"), result.stdout)
            }
        }
        node {
            for (source in scripts) {
                val result = source.javascript()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf("injected", "payload"), result.stdout)
            }
        }
    }
}

private data class NestedLiteralValues(val shell: String, val python: String, val javascript: String)

private class NestedFunctionEncoders {
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
    class Nested {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    object Named {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    companion object {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    inner class Inner {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    fun localScripts(data: NestedLiteralValues): NestedLiteralValues {
        class Local {
            fun scripts() = NestedLiteralValues(
                "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
            )
        }
        return Local().scripts()
    }
    fun anonymousScripts(data: NestedLiteralValues) = object {
        fun scripts() = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }.scripts()
}

private class NestedPropertyEncoders(
    val shLiteral: (String) -> String, val pythonLiteral: (String) -> String, val jsLiteral: (String) -> String,
) {
    class Nested {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    object Named {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    companion object {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
    inner class Inner {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
}

private class NestedPackageEncoders(val com: NestedPackageRoot) {
    class Nested {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${com.kotlysh.core.shLiteral(data.shell)}", "print(${com.kotlysh.core.pythonLiteral(data.python)})", "console.log(${com.kotlysh.core.jsLiteral(data.javascript)})",
        )
    }
    companion object {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${com.kotlysh.core.shLiteral(data.shell)}", "print(${com.kotlysh.core.pythonLiteral(data.python)})", "console.log(${com.kotlysh.core.jsLiteral(data.javascript)})",
        )
    }
    inner class Inner {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${com.kotlysh.core.shLiteral(data.shell)}", "print(${com.kotlysh.core.pythonLiteral(data.python)})", "console.log(${com.kotlysh.core.jsLiteral(data.javascript)})",
        )
    }
}

private class NestedPackageRoot { val kotlysh = NestedPackageNamespace() }
private class NestedPackageNamespace { val core = NestedLiteralNamespace() }
private class NestedLiteralNamespace {
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
}

private object NestedSingletonEncoders {
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
    class Nested {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
}

private class NestedCompanionEncoders {
    companion object {
        fun shLiteral(value: String) = value
        fun pythonLiteral(value: String) = value
        fun jsLiteral(value: String) = value
    }
    class Nested {
        fun scripts(data: NestedLiteralValues) = NestedLiteralValues(
            "printf '%s' ${shLiteral(data.shell)}", "print(${pythonLiteral(data.python)})", "console.log(${jsLiteral(data.javascript)})",
        )
    }
}
