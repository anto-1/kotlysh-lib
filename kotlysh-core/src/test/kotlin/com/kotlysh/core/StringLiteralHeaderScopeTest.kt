package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(20)
class StringLiteralHeaderScopeTest {
    private val inputs = HeaderScriptValues("\$(printf injected >&2)", "(print('injected'), 'payload')[1]", "(console.log('injected'), 'payload')")

    @Test
    fun `primary headers use imported helpers and secondary delegation accepts canonical qualification`(): Unit = runBlocking {
        val defaults = HeaderDefaults(inputs)
        val qualified = HeaderQualified(inputs)
        val sources = listOf(HeaderScriptValues(defaults.shell, defaults.python, defaults.javascript), HeaderDerived(inputs), HeaderSecondary(inputs), qualified.scripts)
        sh {
            for (source in sources) {
                val result = source.shell()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf(inputs.shell), result.stdout)
                assertTrue(result.stderr.isEmpty())
            }
        }
        py {
            for (source in sources) { val result = source.python(); assertTrue(result.ok, result.stderrText); assertEquals(listOf(inputs.python), result.stdout) }
        }
        node {
            for (source in sources) { val result = source.javascript(); assertTrue(result.ok, result.stderrText); assertEquals(listOf(inputs.javascript), result.stdout) }
        }
    }

    @Test
    fun `constructor headers retain genuinely visible companion helper bindings`(): Unit = runBlocking {
        val sources = HeaderCompanion(inputs).scripts
        sh { val result = sources.shell(); assertTrue(result.ok, result.stderrText); assertEquals(listOf("injected"), result.stderr) }
        py { val result = sources.python(); assertTrue(result.ok, result.stderrText); assertEquals(listOf("injected", "payload"), result.stdout) }
        node { val result = sources.javascript(); assertTrue(result.ok, result.stderrText); assertEquals(listOf("injected", "payload"), result.stdout) }
    }
}

private open class HeaderScriptValues(val shell: String, val python: String, val javascript: String)
private class HeaderDefaults(
    input: HeaderScriptValues,
    val shell: String = "printf '%s' ${shLiteral(input.shell)}",
    val python: String = "print(${pythonLiteral(input.python)})",
    val javascript: String = "console.log(${jsLiteral(input.javascript)})",
) {
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
}
private class HeaderDerived(input: HeaderScriptValues) : HeaderScriptValues(
    "printf '%s' ${shLiteral(input.shell)}", "print(${pythonLiteral(input.python)})", "console.log(${jsLiteral(input.javascript)})",
) {
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
}
private class HeaderSecondary(shell: String, python: String, javascript: String) : HeaderScriptValues(shell, python, javascript) {
    constructor(input: HeaderScriptValues) : this(
        "printf '%s' ${com.kotlysh.core.shLiteral(input.shell)}", "print(${com.kotlysh.core.pythonLiteral(input.python)})", "console.log(${com.kotlysh.core.jsLiteral(input.javascript)})",
    )
    fun shLiteral(value: String) = value
    fun pythonLiteral(value: String) = value
    fun jsLiteral(value: String) = value
}
private class HeaderQualified(input: HeaderScriptValues, val scripts: HeaderScriptValues = HeaderScriptValues(
    "printf '%s' ${com.kotlysh.core.shLiteral(input.shell)}", "print(${com.kotlysh.core.pythonLiteral(input.python)})", "console.log(${com.kotlysh.core.jsLiteral(input.javascript)})",
)) { val com = Any() }
private class HeaderCompanion(input: HeaderScriptValues, val scripts: HeaderScriptValues = HeaderScriptValues(
    "printf '%s' ${shLiteral(input.shell)}", "print(${pythonLiteral(input.python)})", "console.log(${jsLiteral(input.javascript)})",
)) {
    companion object {
        fun shLiteral(value: String) = value
        fun pythonLiteral(value: String) = value
        fun jsLiteral(value: String) = value
    }
}
