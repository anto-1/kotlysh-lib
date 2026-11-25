package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*

@Timeout(15)
class RuntimeSpecSnapshotTest {
    @Test
    fun `mutation immediately after argument capture cannot falsify launched command metadata`(): Unit = runBlocking {
        val original = command("original")
        val supplied = MutatingAfterCopy(original)
        val spec = RuntimeSpec("mutable-command", supplied, RuntimeSpecs.Bash.wrap)
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, spec)
        try {
            supplied.mutateAfterNextCopy = true
            runtime.start()
            assertEquals(1, supplied.mutations)
            assertEquals("changed-after-capture", supplied.last())

            val result = runtime.exec("printf '%s' \"\$1\"")
            assertTrue(result.ok)
            assertEquals(listOf("original"), result.stdout)
            val started = trace.events.filterIsInstance<RuntimeSessionStarted>().single()
            assertEquals(original, started.command)
            assertEquals(runtime.processId, started.processId)
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `each session captures intentional specification updates when starting`(): Unit = runBlocking {
        val supplied = command("initial").toMutableList()
        val firstSpec = RuntimeSpec("first", supplied, RuntimeSpecs.Bash.wrap)
        val secondSpec = RuntimeSpec("second", supplied, RuntimeSpecs.Bash.wrap, null)
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val first = ProcessRuntime(owner, firstSpec)
        val second = ProcessRuntime(owner, secondSpec)
        try {
            supplied[supplied.lastIndex] = "first-update"
            first.start()
            supplied[supplied.lastIndex] = "second-update"
            second.start()
            supplied[supplied.lastIndex] = "later-caller-update"

            assertEquals(listOf("first-update"), first.exec("printf '%s' \"\$1\"").stdout)
            assertEquals(listOf("second-update"), second.exec("printf '%s' \"\$1\"").stdout)
            assertEquals(listOf(command("first-update"), command("second-update")),
                trace.events.filterIsInstance<RuntimeSessionStarted>().map { it.command })
            assertEquals(2, trace.events.filterIsInstance<RuntimeSessionStarted>().map { it.sessionId }.distinct().size)
        } finally {
            first.closeSuspending()
            second.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `started trace command remains immutable after its caller clears the specification`(): Unit = runBlocking {
        val original = command("retained")
        val supplied = original.toMutableList()
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpec("mutable-command", supplied, RuntimeSpecs.Bash.wrap))
        try {
            runtime.start()
            val started = trace.events.filterIsInstance<RuntimeSessionStarted>().single()
            supplied.clear()
            assertEquals(original, started.command)
            assertFailsWith<UnsupportedOperationException> { (started.command as MutableList<String>).clear() }
            assertEquals(listOf("retained"), runtime.exec("printf '%s' \"\$1\"").stdout)
            assertEquals(original, started.command)
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `command emptied before startup fails before launch and can be corrected for retry`(): Unit = runBlocking {
        val supplied = command("repaired").toMutableList()
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpec("mutable-command", supplied, RuntimeSpecs.Bash.wrap))
        try {
            supplied.clear()
            val failure = assertFailsWith<IllegalArgumentException> { runtime.start() }
            assertTrue(failure.message.orEmpty().contains("runtime command must not be empty"))
            assertNull(runtime.processId)
            assertTrue(trace.events.isEmpty())

            supplied.addAll(command("repaired"))
            runtime.start()
            assertEquals(listOf("repaired"), runtime.exec("printf '%s' \"\$1\"").stdout)
            assertEquals(command("repaired"), trace.events.filterIsInstance<RuntimeSessionStarted>().single().command)
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    private fun command(argument: String) =
        listOf("/bin/bash", "--noprofile", "--norc", "-s", "--", argument)

    /** Models a caller mutation at the exact collection-copy boundary without timing guesses. */
    private class MutatingAfterCopy(initial: List<String>) : java.util.ArrayList<String>(initial) {
        var mutateAfterNextCopy = false
        var mutations = 0
            private set

        override fun toArray(): Array<Any?> {
            val captured = super.toArray()
            mutateAfterCopy()
            return captured
        }

        override fun <T> toArray(array: Array<T>): Array<T> {
            val captured = super.toArray(array)
            mutateAfterCopy()
            return captured
        }

        private fun mutateAfterCopy() {
            if (mutateAfterNextCopy) {
                mutateAfterNextCopy = false
                mutations++
                this[lastIndex] = "changed-after-capture"
            }
        }
    }
}
