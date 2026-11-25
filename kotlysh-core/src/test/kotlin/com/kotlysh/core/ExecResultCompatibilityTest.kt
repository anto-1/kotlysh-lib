package com.kotlysh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecResultCompatibilityTest {
    private val parameters = arrayOf(List::class.java, List::class.java, List::class.java, Int::class.javaObjectType, Boolean::class.javaPrimitiveType!!)

    @Test
    fun `legacy direct and default constructors remain available`() {
        val command = listOf("unused")
        val direct = ExecResult::class.java.getConstructor(*parameters)
            .newInstance(command, emptyList<String>(), emptyList<String>(), 0, false)
        assertTrue(direct.ok)
        assertFalse(direct.outputLimited)
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val default = ExecResult::class.java.getConstructor(*parameters, Integer.TYPE, marker)
            .newInstance(command, emptyList<String>(), emptyList<String>(), 0, true, 16, null)
        assertTrue(default.ok)
        assertFalse(default.timedOut)
        assertFalse(default.outputLimited)
    }

    @Test
    fun `legacy copy and all default masks retain output limit status`() {
        val original = ExecResult(listOf("original"), listOf("out"), listOf("err"), 0, outputLimited = true)
        val command = listOf("replacement")
        val stdout = listOf("next-out")
        val stderr = listOf("next-err")
        val copy = ExecResult::class.java.getMethod("copy", *parameters)
        val direct = copy.invoke(original, command, stdout, stderr, 7, true) as ExecResult
        assertTrue(direct.outputLimited)
        assertFalse(direct.ok)
        val defaults = ExecResult::class.java.getMethod("copy\$default", ExecResult::class.java, *parameters, Integer.TYPE, Any::class.java)
        for (mask in 0..31) {
            for (exitCode in listOf(7, null)) {
                val actual = defaults.invoke(null, original,
                    if (mask and 1 != 0) null else command,
                    if (mask and 2 != 0) null else stdout,
                    if (mask and 4 != 0) null else stderr,
                    exitCode, true, mask, null) as ExecResult
                assertEquals(original.copy(
                    command = if (mask and 1 != 0) original.command else command,
                    stdout = if (mask and 2 != 0) original.stdout else stdout,
                    stderr = if (mask and 4 != 0) original.stderr else stderr,
                    exitCode = if (mask and 8 != 0) original.exitCode else exitCode,
                    timedOut = if (mask and 16 != 0) original.timedOut else true,
                    outputLimited = true,
                ), actual, "mask=$mask, exitCode=$exitCode")
                assertFalse(actual.ok)
            }
        }
    }
}
