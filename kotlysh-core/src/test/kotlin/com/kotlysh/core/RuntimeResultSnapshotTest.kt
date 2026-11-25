package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeResultSnapshotTest {
    @Test
    fun `persistent output snapshots reject JVM mutation and survive later dispatches`(): Unit = runBlocking {
        sh {
            val result = "printf 'one\\ntwo\\n'; printf 'err1\\nerr2\\n' >&2"()
            assertTrue(result.ok)
            assertFailsWith<UnsupportedOperationException> { (result.stdout as MutableList<String>).clear() }
            assertFailsWith<UnsupportedOperationException> { (result.stderr as MutableList<String>)[0] = "changed" }
            assertTrue("printf later"().ok)
            assertEquals(listOf("one", "two"), result.stdout)
            assertEquals(listOf("err1", "err2"), result.stderr)
            assertEquals(result, result.copy())
        }
    }

    @Test
    fun `exec command and output snapshots reject JVM mutation`(): Unit = runBlocking {
        val command = mutableListOf("/bin/bash", "-c", "printf 'one\\ntwo\\n'; printf 'err1\\nerr2\\n' >&2")
        val original = command.toList()
        val result = exec(command)
        assertTrue(result.ok)
        command.clear()
        assertFailsWith<UnsupportedOperationException> { (result.command as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.stdout as MutableList<String>)[0] = "changed" }
        assertFailsWith<UnsupportedOperationException> { (result.stderr as MutableList<String>).clear() }
        assertEquals(original, result.command)
        assertEquals(listOf("one", "two"), result.stdout)
        assertEquals(listOf("err1", "err2"), result.stderr)
    }
}
