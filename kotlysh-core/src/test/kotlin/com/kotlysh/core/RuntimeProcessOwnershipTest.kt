package com.kotlysh.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Timeout(15)
class RuntimeProcessOwnershipTest {
    @Test
    fun `a captured intermediate parent can reap its forced child before its own termination`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("kotlysh-runtime-tree-")
        val script = directory.resolve("middle.py")
        val ready = directory.resolve("ready")
        val reapAttempted = directory.resolve("reap-attempted")
        Files.writeString(script, """
            import os,pathlib,signal,subprocess,sys,time
            signal.signal(signal.SIGTERM,signal.SIG_IGN)
            child=subprocess.Popen(['/bin/sleep','30'])
            ready=pathlib.Path(sys.argv[1])
            temporary=ready.with_suffix('.tmp')
            temporary.write_text(str(os.getpid())+':'+str(child.pid))
            temporary.replace(ready)
            os.waitid(os.P_PID,child.pid,os.WEXITED|os.WNOWAIT)
            time.sleep(0.075)
            pathlib.Path(sys.argv[2]).write_text('ready to reap')
            os.waitpid(child.pid,0)
            while True:time.sleep(30)
        """.trimIndent())
        val ownerJob = SupervisorJob()
        val runtime = ProcessRuntime(CoroutineScope(ownerJob + Dispatchers.IO), RuntimeSpecs.Bash)
        val owned = mutableListOf<CapturedProcess>()
        try {
            runtime.start()
            val root = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
            owned += root
            val result = runtime.exec(
                """
                    python3 ${shLiteral(script.toString())} ${shLiteral(ready.toString())} ${shLiteral(reapAttempted.toString())} &
                    while [ ! -e ${shLiteral(ready.toString())} ]; do /bin/sleep 0.01; done
                    cat ${shLiteral(ready.toString())}
                """.trimIndent(),
                timeout = 5.seconds,
                output = { line ->
                    assertEquals(RuntimeStream.Stdout, line.stream)
                    owned += line.text.split(':').map { pid -> capture(ProcessHandle.of(pid.toLong()).orElseThrow()) }
                },
            )
            assertTrue(result.ok)
            assertEquals(3, owned.size)
            assertEquals(root.handle.pid(), owned[1].handle.parent().orElseThrow().pid())
            assertEquals(owned[1].handle.pid(), owned[2].handle.parent().orElseThrow().pid())

            runtime.closeSuspending()

            assertTrue(Files.exists(reapAttempted), "The intermediate parent was terminated before it could reap its child")
            assertTrue(owned.none { it.handle.isAlive }, "A captured process survived tree closure")
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                owned.asReversed().forEach(CapturedProcess::destroyIfOwned)
                ownerJob.cancelAndJoin()
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a blocked writer cannot starve deadline cleanup with one ordinary IO permit`() {
        runLowIoFixture("deadline")
    }

    @Test
    fun `a blocked writer cannot starve cancellation cleanup with one ordinary IO permit`() {
        runLowIoFixture("cancellation")
    }

    @Test
    fun `managed runtime deadline stops a blocked writer with one ordinary IO permit`() {
        runLowIoFixture("managed-deadline")
    }

    @Test
    fun `managed runtime cancellation stops a blocked writer with one ordinary IO permit`() {
        runLowIoFixture("managed-cancellation")
    }

    @Test
    fun `managed queued deadline returns while the active writer remains blocked with one IO permit`() {
        runLowIoFixture("managed-queued-timeout")
    }

    @Test
    fun `dispatch preserves explicit callback dispatcher and IO context elements`(): Unit = runBlocking {
        val ownerJob = SupervisorJob()
        val runtime = ProcessRuntime(CoroutineScope(ownerJob + Dispatchers.IO), RuntimeSpecs.Bash)
        val threadLocal = ThreadLocal<String>()
        val custom = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runtime.start()
            withContext(custom + threadLocal.asContextElement("custom-context")) {
                val expectedThread = Thread.currentThread()
                val lines = mutableListOf<String>()
                val result = runtime.exec("printf 'custom\\n'", timeout = 5.seconds, output = { line ->
                    assertTrue(Thread.currentThread() === expectedThread, "An explicit callback dispatcher changed")
                    assertEquals("custom-context", threadLocal.get())
                    lines += line.text
                })
                assertTrue(result.ok)
                assertEquals(listOf("custom"), lines)
            }
            withContext(Dispatchers.IO + threadLocal.asContextElement("io-context")) {
                val lines = mutableListOf<String>()
                val result = runtime.exec("printf 'io\\n'", timeout = 5.seconds, output = { line ->
                    assertEquals("io-context", threadLocal.get())
                    lines += line.text
                })
                assertTrue(result.ok)
                assertEquals(listOf("io"), lines)
            }
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
            custom.close()
        }
    }

    @Test
    fun `ordinary idle closure allows stdin EOF without signalling the parent`(): Unit = runBlocking {
        repeat(5) {
            val directory = Files.createTempDirectory("kotlysh-idle-runtime-")
            val terminated = directory.resolve("parent-terminated")
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(ownerJob + Dispatchers.IO)
            val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
            var root: CapturedProcess? = null
            try {
                runtime.start()
                val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
                root = ownedRoot
                val result = runtime.exec(
                    "terminated=${shLiteral(terminated.toString())}; trap 'printf terminated > \"\$terminated\"; exit 0' TERM",
                    timeout = 5.seconds,
                )
                assertTrue(result.ok)

                runtime.closeSuspending()

                assertFalse(ownedRoot.handle.isAlive)
                assertFalse(Files.exists(terminated), "An idle writer must complete its graceful stdin EOF")
            } finally {
                try {
                    runtime.closeSuspending()
                } finally {
                    root?.destroyIfOwned()
                    ownerJob.cancelAndJoin()
                    directory.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `fatal dispatch start stops and reaps a captured child which ignores termination`(): Unit = runBlocking {
        val fatal = OutOfMemoryError("simulated dispatch start failure")
        val rejectedScript = "printf must-not-submit"
        withOwnedTree(afterTrace = { event ->
            if (event is RuntimeDispatchStarted && event.scriptPreview == rejectedScript) throw fatal
        }) { tree ->
            val actual = runCatching { tree.runtime.exec(rejectedScript, timeout = 5.seconds) }.exceptionOrNull()

            assertFalse(tree.root.handle.isAlive, "The interpreter survived the failed dispatch start")
            assertFalse(tree.child.handle.isAlive, "The signal-ignoring child was not stopped and reaped")
            assertTrue(actual === fatal)
            val events = tree.trace.events
            val failed = events.filterIsInstance<RuntimeDispatchFailed>().single()
            val closed = events.filterIsInstance<RuntimeSessionClosed>().single()
            assertTrue(events.indexOf(failed) < events.indexOf(closed))
            assertEquals(closed, events.last())
            assertTrue(closed.poisoned)
        }
    }

    @Test
    fun `cancelling runtime workers retains stdin until owned tree cleanup and permits safe EOF`(): Unit = runBlocking {
        withOwnedTree { tree ->
            tree.ownerJob.cancelAndJoin()

            assertTrue(tree.root.handle.isAlive, "A cancelled writer closed stdin before descendant cleanup")
            assertTrue(tree.child.handle.isAlive)
            assertTrue(tree.root.handle.descendants().use { descendants ->
                descendants.anyMatch { it.pid() == tree.child.handle.pid() }
            }, "Worker cancellation must retain the child's original owning interpreter")

            tree.runtime.closeSuspending()

            assertFalse(tree.root.handle.isAlive)
            assertFalse(tree.child.handle.isAlive, "The captured signal-ignoring child survived closure")
            assertFalse(Files.exists(tree.parentTerminated), "An idle finished writer should allow EOF after child cleanup")
            assertFalse(tree.trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
        }
    }

    private suspend fun withOwnedTree(
        afterTrace: (RuntimeTraceEvent) -> Unit = {},
        block: suspend (OwnedTree) -> Unit,
    ) {
        val directory = Files.createTempDirectory("kotlysh-owned-runtime-")
        val ready = directory.resolve("child-ready")
        val parentTerminated = directory.resolve("parent-terminated")
        val trace = RuntimeTraceBuffer()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(
            ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
                trace.onEvent(event)
                afterTrace(event)
            }, RuntimeTraceLimits()) + CoroutineExceptionHandler { _, _ -> },
        )
        val runtime = ProcessRuntime(owner, RuntimeSpecs.Bash)
        var root: CapturedProcess? = null
        var child: CapturedProcess? = null
        try {
            runtime.start()
            val ownedRoot = capture(ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow())
            root = ownedRoot
            val result = runtime.exec(
                """
                    parent_terminated=${shLiteral(parentTerminated.toString())}
                    trap 'printf terminated > "${'$'}parent_terminated"; exit 0' TERM
                    /bin/sh -c 'trap "" TERM; printf ready > "${'$'}1"; exec /bin/sleep 30' kotlysh-child ${shLiteral(ready.toString())} &
                    child=${'$'}!
                    while [ ! -s ${shLiteral(ready.toString())} ]; do /bin/sleep 0.01; done
                    printf 'owned-child=%s\n' "${'$'}child"
                """.trimIndent(),
                timeout = 5.seconds,
                output = { line ->
                    assertEquals(RuntimeStream.Stdout, line.stream)
                    child = capture(ProcessHandle.of(line.text.removePrefix("owned-child=").toLong()).orElseThrow())
                },
            )
            assertTrue(result.ok)
            val ownedChild = checkNotNull(child)
            assertTrue(ownedChild.handle.isAlive)
            assertTrue(ownedRoot.handle.descendants().use { descendants ->
                descendants.anyMatch { it.pid() == ownedChild.handle.pid() }
            })
            block(OwnedTree(runtime, ownerJob, ownedRoot, ownedChild, trace, parentTerminated))
        } finally {
            try {
                runtime.closeSuspending()
            } finally {
                child?.destroyIfOwned()
                root?.destroyIfOwned()
                ownerJob.cancelAndJoin()
                directory.toFile().deleteRecursively()
            }
        }
    }

    private fun runLowIoFixture(mode: String) {
        val directory = Files.createTempDirectory("kotlysh-low-io-")
        val identity = directory.resolve("root-identity")
        val log = directory.resolve("fixture.log")
        val classpath = listOf(
            RuntimeLowIoOwnershipFixture::class.java,
            ProcessRuntime::class.java,
            RuntimeSpec::class.java,
            Unit::class.java,
            CoroutineScope::class.java,
            kotlinx.serialization.json.JsonObject::class.java,
            kotlinx.serialization.KSerializer::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val fork = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-ea", "-Dkotlinx.coroutines.io.parallelism=1", "-cp", classpath,
            RuntimeLowIoOwnershipFixture::class.java.name, mode, identity.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        var root: CapturedProcess? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(identity) && fork.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            if (Files.exists(identity)) {
                val fields = Files.readString(identity).lines()
                assertEquals(fork.pid(), fields[2].toLong(), "The fixture must record its own interpreter")
                val handle = ProcessHandle.of(fields[0].toLong()).orElse(null)
                val started = Instant.parse(fields[1])
                if (handle?.isAlive == true && handle.info().startInstant().orElse(null) == started) {
                    root = CapturedProcess(handle, started)
                }
            }

            assertTrue(fork.waitFor(8, TimeUnit.SECONDS), "The $mode fixture stalled:\n${Files.readString(log)}")
            assertEquals(0, fork.exitValue(), "The $mode fixture failed:\n${Files.readString(log)}")
            assertTrue(Files.readString(log).contains("completed:$mode"))
            assertFalse(root?.handle?.isAlive == true, "The fixture returned before its interpreter stopped")
        } finally {
            try {
                val descendants = if (fork.isAlive) OwnedProcessCleanup.captureDescendants(fork) else emptyList()
                OwnedProcessCleanup.finish(fork, descendants, graceful = false)
            } finally {
                root?.destroyIfOwned()
                listOf(fork.outputStream, fork.inputStream, fork.errorStream).forEach { runCatching { it.close() } }
                directory.toFile().deleteRecursively()
            }
        }
    }

    private fun capture(handle: ProcessHandle): CapturedProcess =
        CapturedProcess(handle, handle.info().startInstant().orElseThrow())

    private data class OwnedTree(
        val runtime: ProcessRuntime,
        val ownerJob: kotlinx.coroutines.Job,
        val root: CapturedProcess,
        val child: CapturedProcess,
        val trace: RuntimeTraceBuffer,
        val parentTerminated: Path,
    )

    private data class CapturedProcess(val handle: ProcessHandle, val started: Instant) {
        fun destroyIfOwned() {
            if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
        }
    }
}

/** Separate JVM so the shared test dispatcher and its global properties remain unchanged. */
internal object RuntimeLowIoOwnershipFixture {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val mode = args[0]
        val identity = Path.of(args[1])
        val submissions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val spec = RuntimeSpec("nonreading-ownership", listOf("/bin/sleep", "30")) { script, _ ->
            submissions += script
            script
        }
        if (mode.startsWith("managed-")) {
            val ready = CompletableDeferred<Pair<ProcessHandle, kotlin.time.TimeMark>>()
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                runtime(spec) {
                    val root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    recordIdentity(identity, root)
                    ready.complete(root to TimeSource.Monotonic.markNow())
                    if (mode == "managed-queued-timeout") {
                        val active = async(start = CoroutineStart.UNDISPATCHED) {
                            "x".repeat(200_000)(timeout = 30.seconds)
                        }
                        try {
                            awaitBlockedWriter()
                            val queuedAt = TimeSource.Monotonic.markNow()
                            val queued = "must-not-submit"(timeout = 200.milliseconds)
                            check(queued.timedOut)
                            check(queuedAt.elapsedNow() < 2.seconds) { "Queued deadline took ${queuedAt.elapsedNow()}" }
                            check(active.isActive && root.isAlive) { "Queued expiry stopped the active dispatch" }
                            check(submissions.size == 1 && submissions.single() != "must-not-submit") {
                                "A queued timeout submitted its script"
                            }
                        } finally {
                            active.cancelAndJoin()
                        }
                    } else {
                        val result = "x".repeat(200_000)(
                            timeout = if (mode == "managed-deadline") 200.milliseconds else 30.seconds,
                        )
                        check(result.timedOut)
                    }
                }
            }
            try {
                val (root, started) = withTimeout(3.seconds) { ready.await() }
                if (mode == "managed-cancellation") {
                    awaitBlockedWriter()
                    withTimeout(3.seconds) { work.cancelAndJoin() }
                    check(work.isCancelled)
                } else withTimeout(3.seconds) { work.await() }
                check(started.elapsedNow() < 2.seconds) { "Managed cleanup took ${started.elapsedNow()}" }
                check(!root.isAlive) { "The owned interpreter survived $mode" }
                println("completed:$mode")
            } finally {
                work.cancelAndJoin()
            }
            return@runBlocking
        }
        val ownerJob = SupervisorJob()
        val runtime = ProcessRuntime(
            CoroutineScope(ownerJob + Dispatchers.IO),
            spec,
        )
        try {
            runtime.start()
            val root = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
            recordIdentity(identity, root)
            val started = TimeSource.Monotonic.markNow()
            when (mode) {
                "deadline" -> {
                    val result = runtime.exec("x".repeat(200_000), timeout = 200.milliseconds)
                    check(result.timedOut)
                }
                "cancellation" -> {
                    val work = async(start = CoroutineStart.UNDISPATCHED) {
                        runtime.exec("x".repeat(200_000), timeout = 30.seconds)
                    }
                    awaitBlockedWriter()
                    withTimeout(3.seconds) { work.cancelAndJoin() }
                    check(work.isCancelled)
                }
                else -> error("Unknown fixture mode $mode")
            }
            check(started.elapsedNow() < 2.seconds) { "Cleanup took ${started.elapsedNow()}" }
            check(!root.isAlive) { "The owned interpreter survived $mode" }
            println("completed:$mode")
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }

    private fun recordIdentity(identity: Path, root: ProcessHandle) {
        val temporaryIdentity = identity.resolveSibling("root-identity.tmp")
        Files.writeString(temporaryIdentity,
            "${root.pid()}\n${root.info().startInstant().orElseThrow()}\n${ProcessHandle.current().pid()}\n")
        Files.move(temporaryIdentity, identity)
    }

    private suspend fun awaitBlockedWriter() {
        withTimeout(3.seconds) {
            while (Thread.getAllStackTraces().values.none { stack ->
                stack.any { it.methodName == "writeBytes" }
            }) delay(5)
        }
    }
}
