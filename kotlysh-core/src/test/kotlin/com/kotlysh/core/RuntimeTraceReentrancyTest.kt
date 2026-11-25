package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

@Timeout(20)
class RuntimeTraceReentrancyTest {
    @Test
    fun `startup trace can close its owned tree without deadlocking or returning a live session`() = runFixture("startup")

    @Test
    fun `dispatch output trace can synchronously close its owned tree`() = runFixture("output")

    @Test
    fun `closure trace can idempotently close its already stopped session`() = runFixture("closure")

    @Test
    fun `trace can await closure on another thread without blocking owned cleanup`() = runFixture("cross-thread")

    @Test
    fun `dispatch start trace can close before the wrapper is submitted`() = runFixture("dispatch-start")

    @Test
    fun `queued deadline trace can close without holding the dispatch facts lock`() = runFixture("queued-timeout")

    private fun runFixture(mode: String) {
        // Isolate the negative control: a cleanup lock deadlock cannot strand
        // the Gradle test worker or another test's coroutine dispatcher.
        val directory = Files.createTempDirectory("kotlysh-reentrant-trace-")
        val identity = directory.resolve("owned-identities")
        val log = directory.resolve("fixture.log")
        val code = listOf(
            RuntimeTraceReentrancyFixture::class.java, ProcessRuntime::class.java,
            Unit::class.java, CoroutineScope::class.java,
            kotlinx.serialization.json.JsonObject::class.java,
            kotlinx.serialization.KSerializer::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        val driver = checkNotNull(ProcessRuntime::class.java.getResource("/com/kotlysh/core/python_driver.py"))
        val resources = if (driver.protocol == "file") {
            Path.of(driver.toURI()).parent.parent.parent.parent.toString()
        } else {
            Path.of((driver.openConnection() as java.net.JarURLConnection).jarFileURL.toURI()).toString()
        }
        val classpath = (code + resources).distinct().joinToString(java.io.File.pathSeparator)
        val fork = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-ea", "-cp", classpath, RuntimeTraceReentrancyFixture::class.java.name,
            mode, identity.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val owned = mutableListOf<Pair<ProcessHandle, Instant>>()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(identity) && fork.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(Files.exists(identity), "The $mode fixture did not acquire its tree:\n${Files.readString(log)}")
            val lines = Files.readAllLines(identity)
            assertEquals(fork.pid().toString(), lines.first(), "The fixture must record its own JVM")
            for (line in lines.drop(1)) {
                val fields = line.split(' ', limit = 2)
                val handle = ProcessHandle.of(fields[0].toLong()).orElse(null)
                val started = Instant.parse(fields[1])
                if (handle != null && handle.info().startInstant().orElse(null) == started) owned += handle to started
            }

            assertTrue(fork.waitFor(8, TimeUnit.SECONDS), "The $mode trace callback stalled:\n${Files.readString(log)}")
            assertEquals(0, fork.exitValue(), "The $mode trace callback failed:\n${Files.readString(log)}")
            assertTrue(Files.readString(log).contains("completed:$mode"))
            owned.forEach { (handle, _) -> assertFalse(handle.isAlive, "The callback returned before owned PID ${handle.pid()} stopped") }
            println(Files.readString(log))
        } finally {
            try {
                val descendants = if (fork.isAlive) OwnedProcessCleanup.captureDescendants(fork) else emptyList()
                OwnedProcessCleanup.finish(fork, descendants, graceful = false)
            } finally {
                owned.asReversed().forEach { (handle, started) ->
                    if (handle.isAlive && handle.info().startInstant().orElse(null) == started) handle.destroyForcibly()
                }
                listOf(fork.outputStream, fork.inputStream, fork.errorStream).forEach { runCatching { it.close() } }
                directory.toFile().deleteRecursively()
            }
        }
    }
}

/** Fresh JVM fixture so a regression in synchronous callback cleanup has a bounded lifetime. */
object RuntimeTraceReentrancyFixture {
    @JvmStatic
    fun main(arguments: Array<String>): Unit = runBlocking {
        val mode = arguments[0]
        val identity = Path.of(arguments[1])
        val ownerJob = SupervisorJob()
        val trace = RuntimeTraceBuffer()
        val owned = mutableListOf<ProcessHandle>()
        val callbackProblem = AtomicReference<String?>()
        val activeReady = CompletableDeferred<Unit>()
        var wrapperCalls = 0
        lateinit var runtime: ProcessRuntime
        val owner = CoroutineScope(ownerJob + Dispatchers.IO + RuntimeTraceContext(RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeSessionStarted) {
                val root = ProcessHandle.of(event.processId).orElseThrow()
                owned += root
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (owned.size < 3 && System.nanoTime() < deadline) {
                    val descendants = root.descendants().use { it.toList() }
                    if (descendants.size >= 2) owned += descendants else Thread.sleep(5)
                }
                check(owned.size >= 3) { "The fixture must own its shell and background child" }
                val receipt = ProcessHandle.current().pid().toString() + "\n" + owned.joinToString("\n") {
                    "${it.pid()} ${it.info().startInstant().orElseThrow()}"
                } + "\n"
                val temporary = identity.resolveSibling("owned-identities.tmp")
                Files.writeString(temporary, receipt)
                Files.move(temporary, identity, StandardCopyOption.ATOMIC_MOVE)
            }
            val closeNow = mode == "startup" && event is RuntimeSessionStarted ||
                mode == "output" && event is RuntimeDispatchOutput ||
                mode == "closure" && event is RuntimeSessionClosed ||
                mode == "dispatch-start" && event is RuntimeDispatchStarted ||
                mode == "queued-timeout" && event is RuntimeDispatchStarted && event.dispatchId == 2L
            if (mode == "queued-timeout" && event is RuntimeDispatchOutput) activeReady.complete(Unit)
            if (closeNow) {
                println("entered:$mode")
                runtime.close()
                check(owned.none { it.isAlive }) { "Callback closure left an owned process alive" }
            }
            if (mode == "cross-thread" && event is RuntimeDispatchOutput) {
                println("entered:$mode")
                val done = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                Thread({
                    try { runtime.close() } catch (error: Throwable) { failure.set(error) }
                    finally { done.countDown() }
                }, "Kotlysh callback-owned closer").apply { isDaemon = true; start() }
                if (!done.await(3, TimeUnit.SECONDS)) {
                    callbackProblem.set("Trace callback blocked cleanup on another thread")
                    error(checkNotNull(callbackProblem.get()))
                }
                check(failure.get() == null) { "The closer failed" }
                check(owned.none { it.isAlive })
            }
        }, RuntimeTraceLimits()))
        // The outer shell owns both a sleeping child and the actual stdin
        // interpreter, so every phase exercises a real multi-process tree.
        runtime = ProcessRuntime(owner, RuntimeSpec(
            "trace-reentrant-shell",
            listOf("/bin/bash", "--noprofile", "--norc", "-c", "sleep 30 & bash --noprofile --norc -s; wait"),
            { script, marker -> wrapperCalls++; RuntimeSpecs.Bash.wrap(script, marker) },
        ))
        try {
            val startup = runCatching { runtime.start() }.exceptionOrNull()
            if (mode == "startup") {
                check(startup is IllegalStateException) { "A callback-closed startup must not return a session" }
            } else {
                check(startup == null)
                if (mode == "closure") runtime.closeSuspending()
                else if (mode == "queued-timeout") {
                    val active = async(Dispatchers.Default) {
                        runCatching { runtime.exec("printf 'ready\\n'; sleep 30", timeout = 5.seconds) }
                    }
                    kotlinx.coroutines.withTimeout(3.seconds) { activeReady.await() }
                    val queued = runtime.exec("printf must-not-submit", timeout = 100.milliseconds)
                    check(queued.timedOut)
                    active.join()
                    check(wrapperCalls == 1) { "A queued deadline must not invoke its wrapper" }
                } else {
                    runCatching { runtime.exec("printf 'ready\\n'", timeout = 5.seconds) }
                    if (mode == "dispatch-start") check(wrapperCalls == 0) { "A callback-closed dispatch must not invoke its wrapper" }
                }
            }
            check(callbackProblem.get() == null) { checkNotNull(callbackProblem.get()) }
            check(owned.none { it.isAlive }) { "Fixture returned before the owned tree stopped" }
            check(!runtime.isOpen)
            val events = trace.events
            check(events.first() is RuntimeSessionStarted)
            check(events.last() is RuntimeSessionClosed)
            check(events.filterIsInstance<RuntimeSessionClosed>().size == 1)
            if (mode in listOf("output", "cross-thread", "dispatch-start", "queued-timeout")) {
                check(events.filterIsInstance<RuntimeDispatchFailed>().size == if (mode == "queued-timeout") 2 else 1)
                check(events.none { it is RuntimeDispatchCompleted })
            }
            println("completed:$mode owned=${owned.size}")
        } finally {
            runtime.closeSuspending()
            ownerJob.cancelAndJoin()
        }
    }
}
