package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeResultSnapshotTest {
    @Test
    fun shellResultsRejectMutationAndRemainStableAfterLaterDispatches() {
        nativeSh {
            val result = "printf 'one\\ntwo\\n'; printf 'error-one\\nerror-two\\n' >&2"()
            assertTrue(result.ok)
            assertReadOnly(result.stdout, listOf("one", "two"))
            assertReadOnly(result.stderr, listOf("error-one", "error-two"))
            assertTrue("printf later"().ok)
            assertEquals(listOf("one", "two"), result.stdout)
            assertEquals(listOf("error-one", "error-two"), result.stderr)
            assertEquals(result, result.copy())
        }
    }

    @Test
    fun directResultsRejectMutationOfBothStreams() {
        val result = NativeExecScope().execute("/bin/sh", "-c",
            "printf 'one\\ntwo\\n'; printf 'error-one\\nerror-two\\n' >&2")
        assertTrue(result.ok)
        assertReadOnly(result.stdout, listOf("one", "two"))
        assertReadOnly(result.stderr, listOf("error-one", "error-two"))
        assertEquals(result, result.copy())
    }

    @Test
    fun terminalCapturePrefixesRemainReadOnly() {
        val limits = NativeOutputLimits(maxLinesPerDispatch = 2)
        val direct = NativeExecScope().execute("/usr/bin/printf", "one\ntwo\nthree\n", outputLimits = limits)
        assertTrue(direct.outputLimited)
        assertReadOnly(direct.stdout, listOf("one", "two"))
        nativeSh {
            val shell = "printf 'one\\ntwo\\nthree\\n'"(outputLimits = limits)
            assertTrue(shell.outputLimited)
            assertReadOnly(shell.stdout, listOf("one", "two"))
        }
    }

    private fun assertReadOnly(values: List<String>, expected: List<String>) {
        assertEquals(expected, values)
        rejectMutation { (values as MutableList<String>).clear() }
        rejectMutation {
            val iterator = values.listIterator() as MutableListIterator<String>
            iterator.next()
            iterator.set("changed")
        }
        rejectMutation { (values.subList(0, values.size) as MutableList<String>).clear() }
        assertEquals(expected, values)
    }

    private fun rejectMutation(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull(failure, "Captured output accepted mutation")
        assertTrue(failure is ClassCastException || failure is UnsupportedOperationException,
            "Mutation failed for an unrelated reason: $failure")
    }
}
