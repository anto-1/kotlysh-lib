package com.kotlysh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RuntimeFailureTraceCompatibilityTest {
    private val legacyParameters = arrayOf(
        String::class.java, String::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE,
        java.lang.Long.TYPE, String::class.java, String::class.java,
    )

    @Test
    fun `legacy constructor keeps absent counts and its original wire shape`() {
        val event = RuntimeDispatchFailed::class.java.getConstructor(*legacyParameters)
            .newInstance("s", "sh", 1L, 2L, 3L, "Error", "message")
        assertNull(event.stdoutLines)
        assertNull(event.stderrLines)
        assertFalse(event.toTraceJson().containsKey("stdoutLines"))
        assertFalse(event.toTraceJson().containsKey("stderrLines"))
    }

    @Test
    fun `legacy copy and default copy descriptors preserve new captured counts`() {
        val event = RuntimeDispatchFailed("s", "sh", 1, 2, 3, "Error", "message", 4, 5)
        val copied = RuntimeDispatchFailed::class.java.getMethod("copy", *legacyParameters)
            .invoke(event, "new", "sh", 1L, 2L, 3L, "Error", "message") as RuntimeDispatchFailed
        assertEquals(event.copy(sessionId = "new"), copied)
        val parameters = arrayOf(RuntimeDispatchFailed::class.java, *legacyParameters, java.lang.Integer.TYPE, Any::class.java)
        val defaults = RuntimeDispatchFailed::class.java.getMethod("copy\$default", *parameters)
            .invoke(null, event, null, null, 0L, 0L, 0L, null, null, 127, null) as RuntimeDispatchFailed
        assertEquals(event, defaults)
        val explicitNull = RuntimeDispatchFailed::class.java.getMethod("copy\$default", *parameters)
            .invoke(null, event, null, null, 0L, 0L, 0L, null, null, 63, null) as RuntimeDispatchFailed
        assertEquals(event.copy(message = null), explicitNull)
    }
}
