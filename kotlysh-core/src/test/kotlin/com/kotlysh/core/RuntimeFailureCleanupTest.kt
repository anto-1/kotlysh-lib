package com.kotlysh.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(15)
class RuntimeFailureCleanupTest {
    @Test
    fun `missing interpreter emits closure without claiming a started process`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kotlysh-missing-runtime")
        val trace = RuntimeTraceBuffer()
        var bodyEntered = false
        try {
            assertFailsWith<IOException> {
                withRuntimeTrace(trace) {
                    runtime(RuntimeSpec("missing-shell", listOf(directory.resolve("missing-interpreter").toString()), RuntimeSpecs.Bash.wrap)) {
                        bodyEntered = true
                    }
                }
            }
            assertFalse(bodyEntered)
            assertTrue(trace.events.filterIsInstance<RuntimeSessionStarted>().isEmpty())
            assertEquals(1, trace.events.size)
            val closed = trace.events.filterIsInstance<RuntimeSessionClosed>().single()
            assertEquals("missing-shell", closed.runtime)
            assertNull(closed.processId)
            assertFalse(closed.poisoned)
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `closing before startup emits only one closure and never acquires a process`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        try {
            runtime.closeSuspending()
            assertFailsWith<IllegalStateException> { runtime.start() }
            runtime.closeSuspending()
            assertNull(runtime.processId)
            assertFalse(runtime.isOpen)
            assertEquals(1, trace.events.size)
            val closed = trace.events.filterIsInstance<RuntimeSessionClosed>().single()
            assertEquals(runtime.sessionId, closed.sessionId)
            assertNull(closed.processId)
            assertFalse(closed.poisoned)
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `runtime body failure remains primary when closure telemetry also fails`(): Unit = runBlocking {
        assertBodyAndClosureFailure(BodyBoundaryException(1))
    }

    @Test
    fun `runtime body fatal failure remains primary when closure telemetry also fails`(): Unit = runBlocking {
        assertBodyAndClosureFailure(TraceBoundaryError(6))
    }

    @Test
    fun `runtime body cancellation remains primary when closure telemetry also fails`(): Unit = runBlocking {
        assertBodyAndClosureFailure(BodyBoundaryCancellation(1))
    }

    @Test
    fun `startup failure remains primary when closure telemetry also fails`(): Unit = runBlocking {
        assertStartupAndClosureFailure(TraceBoundaryError(1), TraceBoundaryError(2))
    }

    @Test
    fun `startup and closure throwing the same error cannot self suppress`(): Unit = runBlocking {
        val failure = TraceBoundaryError(1)
        assertStartupAndClosureFailure(failure, failure)
    }

    @Test
    fun `standalone closure telemetry failures remain observable after owned tree cleanup`(): Unit = runBlocking {
        assertStartupAndClosureFailure(null, TraceBoundaryError(2))
    }

    @Test
    fun `ordinary output failure remains primary when closure telemetry also fails`(): Unit = runBlocking {
        assertOutputAndClosureFailure(IllegalStateException("Output observer failed"))
    }

    @Test
    fun `output cancellation remains primary when closure telemetry also fails`(): Unit = runBlocking {
        assertOutputAndClosureFailure(CancellationException("Output observer cancelled"))
    }

    @Test
    fun `fatal diagnostic getter remains primary when closure telemetry also fails`(): Unit = runBlocking {
        val failure = TraceBoundaryError(3)
        assertOutputAndClosureFailure(HostileMessageException(failure), expected = failure)
    }

    @Test
    fun `cancelling diagnostic getter remains primary when closure telemetry also fails`(): Unit = runBlocking {
        val failure = CancellationException("Diagnostic getter cancelled")
        assertOutputAndClosureFailure(HostileMessageException(failure), expected = failure)
    }

    @Test
    fun `fatal failure telemetry remains primary when closure telemetry also fails`(): Unit = runBlocking {
        val failure = TraceBoundaryError(4)
        assertOutputAndClosureFailure(IllegalStateException("Output observer failed"), expected = failure) {
            if (it is RuntimeDispatchFailed) throw failure
        }
    }

    @Test
    fun `dispatch admission failure remains primary when closure telemetry also fails`(): Unit = runBlocking {
        val failure = TraceBoundaryError(5)
        val cleanup = TraceBoundaryError(2)
        assertDispatchStartFailureStopsOwnedTree(failure, cleanup)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }

    @Test
    fun `an already cancelled worker scope cannot launch an interpreter`(): Unit = runBlocking {
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        try {
            ownerJob.cancel()

            val actual = runCatching { runtime.start() }.exceptionOrNull()

            assertTrue(actual is CancellationException, "Startup must reject a cancelled worker scope")
            assertEquals(null, runtime.processId, "No interpreter may launch without active I/O workers")
            assertTrue(trace.events.isEmpty())
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `worker cancellation during command capture prevents process launch`(): Unit = runBlocking {
        val ownerJob = SupervisorJob()
        val command = object : java.util.ArrayList<String>(RuntimeSpecs.Bash.command) {
            var cancelOnCopy = false
            override fun toArray(): Array<Any?> = super.toArray().also {
                if (cancelOnCopy) ownerJob.cancel()
            }
            override fun <T> toArray(array: Array<T>): Array<T> = super.toArray(array).also {
                if (cancelOnCopy) ownerJob.cancel()
            }
        }
        val trace = RuntimeTraceBuffer()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpec("cancelled-command-copy", command, RuntimeSpecs.Bash.wrap))
        try {
            command.cancelOnCopy = true

            val actual = runCatching { runtime.start() }.exceptionOrNull()

            assertTrue(actual is CancellationException, "Command capture must not hide worker cancellation")
            assertEquals(null, runtime.processId, "Cancelled command capture must fail before ProcessBuilder.start")
            assertTrue(trace.events.isEmpty())
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `caller cancellation during command capture prevents process launch with independent active workers`(): Unit = runBlocking {
        val callerJob = SupervisorJob()
        val ownerJob = SupervisorJob()
        val command = object : java.util.ArrayList<String>(RuntimeSpecs.Bash.command) {
            var cancelOnCopy = false
            override fun toArray(): Array<Any?> = super.toArray().also {
                if (cancelOnCopy) callerJob.cancel()
            }
            override fun <T> toArray(array: Array<T>): Array<T> = super.toArray(array).also {
                if (cancelOnCopy) callerJob.cancel()
            }
        }
        val trace = RuntimeTraceBuffer()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpec("cancelled-startup-caller", command, RuntimeSpecs.Bash.wrap))
        try {
            command.cancelOnCopy = true
            val startup = async(callerJob) { runtime.start() }
            startup.join()
            val actual = runCatching { startup.await() }.exceptionOrNull()

            assertTrue(actual is CancellationException)
            assertTrue(ownerJob.isActive, "Only the startup caller was cancelled")
            assertEquals(null, runtime.processId, "Caller cancellation must prevent launch even with active workers")
            assertTrue(trace.events.isEmpty())
        } finally {
            runtime.closeSuspending()
            callerJob.cancelAndJoin()
            ownerJob.cancelAndJoin()
        }
    }

    @Test
    fun `worker cancellation during startup trace closes the launched owned tree before returning`(): Unit = runBlocking {
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeSessionStarted) {
                val ownedRoot = capture(ProcessHandle.of(event.processId).orElseThrow())
                root = ownedRoot
                val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
                while (child == null && System.nanoTime() < deadline) {
                    child = ownedRoot.handle.descendants().use { descendants ->
                        descendants.findFirst().orElse(null)?.let(::capture)
                    }
                    if (child == null) Thread.sleep(10)
                }
                assertTrue(child != null, "The startup fixture must create an owned child")
                ownerJob.cancel()
            }
        }, RuntimeTraceLimits()) + CoroutineExceptionHandler { _, _ -> })
        val runtime = ProcessRuntime(owner, RuntimeSpec(
            "cancelled-startup-workers",
            listOf("/bin/bash", "--noprofile", "--norc", "-c", "sleep 30 & wait"),
            RuntimeSpecs.Bash.wrap,
        ))
        try {
            val actual = runCatching { runtime.start() }.exceptionOrNull()

            assertFalse(checkNotNull(root).handle.isAlive, "The interpreter survived cancelled startup")
            assertFalse(checkNotNull(child).handle.isAlive, "The owned child survived cancelled startup")
            assertTrue(actual is CancellationException, "Startup must not return success with cancelled workers")
            assertFalse(runtime.isOpen)
            assertEquals(2, trace.events.size)
            assertTrue(trace.events.first() is RuntimeSessionStarted)
            assertTrue((trace.events.last() as RuntimeSessionClosed).poisoned)
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                child?.destroyIfOwned()
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `untraced output failures never read an ordinary hostile diagnostic getter`(): Unit = runBlocking {
        assertUntracedFailureStopsOwnedTree(IllegalArgumentException("diagnostic getter failed"))
    }

    @Test
    fun `untraced output failures never read a cancelling diagnostic getter`(): Unit = runBlocking {
        assertUntracedFailureStopsOwnedTree(CancellationException("diagnostic getter cancelled"))
    }

    @Test
    fun `untraced output failures never read a fatal diagnostic getter`(): Unit = runBlocking {
        assertUntracedFailureStopsOwnedTree(OutOfMemoryError("simulated diagnostic failure"))
    }

    @Test
    fun `hostile output failure diagnostics preserve the original exception after tree cleanup`(): Unit = runBlocking {
        val failure = HostileMessageException(IllegalArgumentException("diagnostic getter failed"))

        val events = assertOutputFailureStopsOwnedTree(failure)

        val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
        assertTrue(failed.errorType.endsWith(".HostileMessageException"))
        assertFailedBeforeClosed(events)
    }

    @Test
    fun `fatal output failure diagnostic getters propagate only after tree cleanup`(): Unit = runBlocking {
        val fatal = OutOfMemoryError("simulated diagnostic failure")

        val events = assertOutputFailureStopsOwnedTree(HostileMessageException(fatal), expected = fatal)

        assertFailedBeforeClosed(events)
    }

    @Test
    fun `cancellation from output failure diagnostic getters propagates after tree cleanup`(): Unit = runBlocking {
        val cancellation = CancellationException("diagnostic getter cancelled")

        val events = assertOutputFailureStopsOwnedTree(HostileMessageException(cancellation), expected = cancellation)

        assertFailedBeforeClosed(events)
    }

    @Test
    fun `fatal failed trace callbacks propagate only after tree cleanup`(): Unit = runBlocking {
        val failure = IllegalStateException("output consumer failed")
        val fatal = OutOfMemoryError("simulated failed trace callback")

        val events = assertOutputFailureStopsOwnedTree(failure, expected = fatal) { event ->
            if (event is RuntimeDispatchFailed) throw fatal
        }

        assertFailedBeforeClosed(events)
    }

    @Test
    fun `fatal startup trace callbacks propagate only after interpreter cleanup`(): Unit = runBlocking {
        val fatal = OutOfMemoryError("simulated startup trace callback")
        val trace = RuntimeTraceBuffer()
        var root: CapturedProcess? = null
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(
            ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
                trace.onEvent(event)
                if (event is RuntimeSessionStarted) {
                    root = capture(ProcessHandle.of(event.processId).orElseThrow())
                    throw fatal
                }
            }, RuntimeTraceLimits()) + CoroutineExceptionHandler { _, _ -> },
        )
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        try {
            val actual = runCatching { runtime.start() }.exceptionOrNull()

            assertFalse(checkNotNull(root).handle.isAlive, "The owned interpreter survived a failed start")
            // Coroutine stack recovery can copy this error across withContext(IO).
            assertTrue(actual?.javaClass == fatal.javaClass, "The startup callback must propagate the fatal error type")
            assertTrue(actual === fatal || actual?.cause === fatal, "The original fatal error must remain the cause")
            assertEquals(2, trace.events.size)
            assertTrue(trace.events.first() is RuntimeSessionStarted)
            val closed = trace.events.last() as RuntimeSessionClosed
            assertTrue(closed.poisoned)
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `rejecting a second start preserves the existing interpreter and usable session`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(trace, RuntimeTraceLimits()))
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        var root: CapturedProcess? = null
        try {
            runtime.start()
            val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
            root = ownedRoot

            val failure = runCatching { runtime.start() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(ownedRoot.handle.isAlive)
            assertEquals(ownedRoot.handle.pid(), runtime.processId)
            val result = runtime.exec("printf usable", timeout = 5.seconds)
            assertTrue(result.ok)
            assertEquals(listOf("usable"), result.stdout)
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionStarted>().size)
            assertTrue(trace.events.none { it is RuntimeSessionClosed })
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `fatal dispatch start trace callbacks stop the existing owned tree before propagating`(): Unit = runBlocking {
        val fatal = OutOfMemoryError("simulated dispatch start callback")
        assertDispatchStartFailureStopsOwnedTree(fatal)
    }

    private suspend fun assertDispatchStartFailureStopsOwnedTree(fatal: Throwable, cleanup: Throwable? = null) {
        val trace = RuntimeTraceBuffer()
        val rejectedScript = "printf must-not-submit"
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(
            ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
                trace.onEvent(event)
                if (event is RuntimeDispatchStarted && event.scriptPreview == rejectedScript) throw fatal
                if (event is RuntimeSessionClosed && cleanup != null) throw cleanup
            }, RuntimeTraceLimits()) + CoroutineExceptionHandler { _, _ -> },
        )
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        try {
            runtime.start()
            val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
            root = ownedRoot
            val setup = runtime.exec(
                "sleep 30 & child=\$!; printf 'owned-child=%s\\n' \"\$child\"",
                timeout = 5.seconds,
                output = { line ->
                    val ownedChild = capture(ProcessHandle.of(line.text.removePrefix("owned-child=").toLong()).orElseThrow())
                    child = ownedChild
                    assertTrue(ownedRoot.handle.descendants().use { descendants ->
                        descendants.anyMatch { it.pid() == ownedChild.handle.pid() }
                    })
                },
            )
            assertTrue(setup.ok)
            assertTrue(checkNotNull(child).handle.isAlive)

            val actual = runCatching { runtime.exec(rejectedScript, timeout = 5.seconds) }.exceptionOrNull()

            assertFalse(ownedRoot.handle.isAlive, "The interpreter survived a failed dispatch start")
            assertFalse(checkNotNull(child).handle.isAlive, "An existing owned child survived a failed dispatch start")
            assertTrue(actual === fatal, "The fatal dispatch start must preserve its failure object")
            val events = trace.events
            assertFailedBeforeClosed(events)
            val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
            val started = events.filterIsInstance<RuntimeDispatchStarted>().single { it.scriptPreview == rejectedScript }
            assertEquals(started.dispatchId, failed.dispatchId)
            assertTrue(events.filterIsInstance<RuntimeDispatchOutput>().none { it.dispatchId == started.dispatchId })
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                child?.destroyIfOwned()
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    private suspend fun assertOutputAndClosureFailure(
        failure: Throwable,
        expected: Throwable = failure,
        afterTrace: (RuntimeTraceEvent) -> Unit = {},
    ) {
        val cleanup = TraceBoundaryError(2)
        val events = assertOutputFailureStopsOwnedTree(failure, expected = expected, verifyFailure = { actual ->
            // withTimeout may recover standard exceptions into copies. The
            // original must remain their cause and cleanup stays on the error
            // selected for propagation; neither may turn into the cleanup error.
            assertTrue(actual === expected || actual?.cause === expected)
            assertEquals(listOf(cleanup), checkNotNull(actual).suppressed.toList())
        }) { event ->
            afterTrace(event)
            if (event is RuntimeSessionClosed) throw cleanup
        }
        assertFailedBeforeClosed(events)
    }

    private suspend fun assertBodyAndClosureFailure(failure: Throwable) {
        val cleanup = TraceBoundaryError(2)
        val trace = RuntimeTraceBuffer()
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        try {
            val actual = runCatching {
                withRuntimeTrace(RuntimeTraceSink { event ->
                    trace.onEvent(event)
                    if (event is RuntimeSessionClosed) throw cleanup
                }) {
                    sh {
                        val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
                        root = ownedRoot
                        val setup = "sleep 30 & child=\$!; printf 'owned-child=%s\\n' \"\$child\""(output = { line ->
                            val ownedChild = capture(ProcessHandle.of(line.text.removePrefix("owned-child=").toLong()).orElseThrow())
                            child = ownedChild
                            assertTrue(ownedRoot.handle.descendants().use { descendants ->
                                descendants.anyMatch { it.pid() == ownedChild.handle.pid() }
                            })
                        })
                        assertTrue(setup.ok)
                        assertTrue(checkNotNull(child).handle.isAlive)
                        throw failure
                    }
                }
            }.exceptionOrNull()

            assertFalse(checkNotNull(root).handle.isAlive, "Interpreter survived failed DSL body")
            assertFalse(checkNotNull(child).handle.isAlive, "Owned child survived failed DSL body")
            assertTrue(actual === failure, "The body's pending failure object must survive cleanup")
            assertEquals(listOf(cleanup), failure.suppressed.toList())
            assertEquals(1, trace.events.filterIsInstance<RuntimeSessionClosed>().size)
            assertTrue(trace.events.last() is RuntimeSessionClosed)
        } finally {
            child?.destroyIfOwned()
            root?.destroyIfOwned()
        }
    }

    private suspend fun assertStartupAndClosureFailure(startFailure: Throwable?, cleanup: Throwable) {
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeSessionStarted) {
                val ownedRoot = capture(ProcessHandle.of(event.processId).orElseThrow())
                root = ownedRoot
                val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
                while (child == null && System.nanoTime() < deadline) {
                    child = ownedRoot.handle.descendants().use { descendants ->
                        descendants.findFirst().orElse(null)?.let(::capture)
                    }
                    if (child == null) Thread.sleep(10)
                }
                assertTrue(child != null, "Startup fixture must create an owned child")
                startFailure?.let { throw it }
            }
            if (event is RuntimeSessionClosed) throw cleanup
        }, RuntimeTraceLimits()) + CoroutineExceptionHandler { _, _ -> })
        val runtime = ProcessRuntime(owner, RuntimeSpec(
            "startup-double-failure",
            listOf("/bin/bash", "--noprofile", "--norc", "-c", "sleep 30 & wait"),
            RuntimeSpecs.Bash.wrap,
        ))
        try {
            val actual = runCatching {
                runtime.start()
                if (startFailure == null) runtime.closeSuspending()
            }.exceptionOrNull()

            assertFalse(checkNotNull(root).handle.isAlive, "Interpreter survived failed startup/closure")
            assertFalse(checkNotNull(child).handle.isAlive, "Owned child survived failed startup/closure")
            assertTrue(actual === (startFailure ?: cleanup), "The pending failure object must survive cleanup")
            val suppressed = if (startFailure != null && startFailure !== cleanup) listOf(cleanup) else emptyList()
            assertEquals(suppressed, checkNotNull(actual).suppressed.toList())
            assertTrue(trace.events.first() is RuntimeSessionStarted)
            assertTrue(trace.events.last() is RuntimeSessionClosed)
            assertEquals(startFailure != null, (trace.events.last() as RuntimeSessionClosed).poisoned)
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                child?.destroyIfOwned()
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    // The extra field prevents coroutine stack recovery from copying this
    // fixture error, allowing exact primary/suppressed identity assertions.
    private class TraceBoundaryError(val boundary: Int) : Error("Simulated trace boundary $boundary")
    private class BodyBoundaryException(val boundary: Int) : IllegalStateException("Simulated body boundary $boundary")
    private class BodyBoundaryCancellation(val boundary: Int) : CancellationException("Simulated body cancellation $boundary")

    private suspend fun assertUntracedFailureStopsOwnedTree(getterFailure: Throwable) {
        assertTrue(defaultRuntimeTraceContext() == null, "This regression requires automatic tracing to be disabled")
        val failure = HostileMessageException(getterFailure)

        val events = assertOutputFailureStopsOwnedTree(failure, traced = false)

        assertTrue(events.isEmpty())
        assertEquals(0, failure.getterReads, "Untraced dispatch failures must not request diagnostic text")
    }

    private suspend fun assertOutputFailureStopsOwnedTree(
        failure: Throwable,
        expected: Throwable = failure,
        traced: Boolean = true,
        verifyFailure: ((Throwable?) -> Unit)? = null,
        afterTrace: (RuntimeTraceEvent) -> Unit = {},
    ): List<RuntimeTraceEvent> {
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        var ownerContext = ownerJob + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        if (traced) {
            ownerContext += RuntimeTraceContext(RuntimeTraceSink { event ->
                trace.onEvent(event)
                afterTrace(event)
            }, RuntimeTraceLimits())
        }
        val owner = CoroutineScope(ownerContext)
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        try {
            runtime.start()
            val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
            root = ownedRoot
            val actual = runCatching {
                runtime.exec(
                    "sleep 30 & child=\$!; printf 'owned-child=%s\\n' \"\$child\"; wait \"\$child\"",
                    timeout = 5.seconds,
                    output = { line ->
                        assertEquals(RuntimeStream.Stdout, line.stream)
                        val ownedChild = capture(ProcessHandle.of(line.text.removePrefix("owned-child=").toLong()).orElseThrow())
                        child = ownedChild
                        assertTrue(ownedRoot.handle.descendants().use { descendants ->
                            descendants.anyMatch { it.pid() == ownedChild.handle.pid() }
                        }, "The captured child must be owned by this runtime")
                        throw failure
                    },
                )
            }.exceptionOrNull()

            // Check before the caller's finally block: direct runtime callers
            // must not have to clean a tree left behind by a failed dispatch.
            assertFalse(ownedRoot.handle.isAlive, "The owned interpreter survived the failed dispatch")
            assertFalse(checkNotNull(child).handle.isAlive, "The owned child survived the failed dispatch")
            if (verifyFailure != null) verifyFailure(actual)
            else assertTrue(actual === expected, "Expected the original failure object; got ${actual?.javaClass?.name}")
            return trace.events
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                child?.destroyIfOwned()
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
            }
        }
    }

    private fun assertFailedBeforeClosed(events: List<RuntimeTraceEvent>) {
        val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
        val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
        assertTrue(events.indexOf(failed) < events.indexOf(closed))
        assertEquals(closed, events.last())
        assertTrue(closed.poisoned)
    }

    private fun capture(handle: ProcessHandle): CapturedProcess =
        CapturedProcess(handle, handle.info().startInstant().orElseThrow())

    private data class CapturedProcess(val handle: ProcessHandle, val started: Instant) {
        fun destroyIfOwned() {
            if (handle.isAlive && handle.info().startInstant().orElse(null) == started) {
                handle.destroyForcibly()
            }
        }
    }

    private class HostileMessageException(private val getterFailure: Throwable) : RuntimeException() {
        var getterReads: Int = 0
            private set

        override val message: String?
            get() {
                getterReads++
                throw getterFailure
            }
    }
}
