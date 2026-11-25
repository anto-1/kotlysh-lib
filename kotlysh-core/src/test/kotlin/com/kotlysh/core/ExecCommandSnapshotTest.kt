package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(15)
class ExecCommandSnapshotTest {
    @Test
    fun `exec launches and reports one owned argument vector despite caller mutation at capture`(): Unit = runBlocking {
        assumeTrue(java.io.File.separatorChar == '/', "Requires Unix argv snapshot fixture")
        val original = listOf("/bin/bash", "--noprofile", "--norc", "-c", "printf original")
        val supplied = MutatingCapture(original)

        val result = exec(supplied)

        assertTrue(result.ok)
        assertEquals(listOf("original"), result.stdout, "Launch mixed arguments from different caller states")
        assertEquals(original, result.command)
        assertEquals(1, supplied.mutations)
        assertEquals(1, supplied.copies, "The caller's whole command must be captured once")
        assertEquals(0, supplied.elementReads, "Preparation reread the original list outside its owned copy")
        supplied.clear()
        assertEquals(original, result.command)
        assertFailsWith<UnsupportedOperationException> { (result.command as MutableList<String>).clear() }
    }

    /** Both historical element reads and the owned-copy boundary trigger the same caller update. */
    private class MutatingCapture(initial: List<String>) : java.util.ArrayList<String>(initial) {
        var mutations = 0
            private set
        var copies = 0
            private set
        var elementReads = 0
            private set

        override fun get(index: Int): String {
            val captured = super.get(index)
            elementReads++
            changeCallerState()
            return captured
        }

        override fun toArray(): Array<Any?> {
            val captured = super.toArray()
            copies++
            changeCallerState()
            return captured
        }

        override fun <T> toArray(array: Array<T>): Array<T> {
            val captured = super.toArray(array)
            copies++
            changeCallerState()
            return captured
        }

        private fun changeCallerState() {
            if (mutations != 0) return
            mutations++
            this[0] = "/bin/false"
            this[lastIndex] = "printf changed"
        }
    }
}
