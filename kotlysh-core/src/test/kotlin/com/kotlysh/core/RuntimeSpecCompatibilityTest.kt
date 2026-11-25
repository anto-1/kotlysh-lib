package com.kotlysh.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class RuntimeSpecCompatibilityTest {
    private val function = kotlin.jvm.functions.Function2::class.java
    private val parameters = arrayOf(String::class.java, List::class.java, function, function)

    @Test
    fun `legacy constructors remain available and choose generic JSON semantics`() {
        val raw = RuntimeSpecs.Bash.wrap
        val request = RuntimeSpecs.Python.requestWrapper
        val three = RuntimeSpec::class.java.getConstructor(*parameters.take(3).toTypedArray())
            .newInstance("node", listOf("unused"), raw)
        val four = RuntimeSpec::class.java.getConstructor(*parameters)
            .newInstance("py", listOf("unused"), raw, request)
        assertEquals(RuntimeJsonNumberPolicy.Json, three.jsonNumberPolicy)
        assertEquals(RuntimeJsonNumberPolicy.Json, four.jsonNumberPolicy)
        assertNull(three.requestWrapper)
        val (name, command, wrap, requestWrapper) = four
        assertEquals("py", name)
        assertEquals(listOf("unused"), command)
        assertSame(raw, wrap)
        assertSame(request, requestWrapper)
    }

    @Test
    fun `legacy copy descriptors and every default mask preserve explicit policy`() {
        val original = RuntimeSpecs.Python
        val command = listOf("replacement")
        val wrap: (String, String) -> String = { source, _ -> source }
        val request: (JsonObject, String) -> String = { value, _ -> value.toString() }
        val copy = RuntimeSpec::class.java.getMethod("copy", *parameters)
        val copied = copy.invoke(original, "node", command, wrap, request) as RuntimeSpec
        assertEquals(original.copy("node", command, wrap, request), copied)
        assertEquals(RuntimeJsonNumberPolicy.Python, copied.jsonNumberPolicy)
        val defaults = RuntimeSpec::class.java.getMethod("copy\$default",
            RuntimeSpec::class.java, *parameters, Integer.TYPE, Any::class.java)
        for (mask in 0..15) {
            for (newRequest in listOf(request, null)) {
                val actual = defaults.invoke(null, original,
                    if (mask and 1 != 0) null else "node",
                    if (mask and 2 != 0) null else command,
                    if (mask and 4 != 0) null else wrap,
                    newRequest, mask, null) as RuntimeSpec
                assertEquals(original.copy(
                    if (mask and 1 != 0) original.name else "node",
                    if (mask and 2 != 0) original.command else command,
                    if (mask and 4 != 0) original.wrap else wrap,
                    if (mask and 8 != 0) original.requestWrapper else newRequest,
                ), actual, "mask=$mask, nullRequest=${newRequest == null}")
                assertEquals(RuntimeJsonNumberPolicy.Python, actual.jsonNumberPolicy)
            }
        }
    }
}
