package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.SIGKILL
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.kill
import platform.posix.unlink
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class NativeAndroidRuntimeTest {
    @Test
    fun defaultSessionCapturesStreams() {
        NativeShellSession().use { session -> with(session) {
            val result = "printf 'android-ready\\n'; printf 'android-stderr\\n' >&2"()
            assertTrue(result.ok)
            assertEquals(listOf("android-ready"), result.stdout)
            assertEquals(listOf("android-stderr"), result.stderr)
        } }
    }

    @Test
    fun defaultBlockRetainsStateAndNonzeroExit() {
        nativeSh {
            assertTrue("export KOTLYSH_ANDROID_STATE='é😀'; greet() { printf '%s' \"\$KOTLYSH_ANDROID_STATE\"; }"().ok)
            assertEquals(1, "false"().exitCode)
            val result = "greet"()
            assertTrue(result.ok)
            assertEquals(listOf("é😀"), result.stdout)
        }
    }

    @Test
    fun directArgumentsRemainLiteralUnicode() {
        val payload = "é😀; $(printf wrong) ' spaces"
        val result = NativeExecScope().execute("/system/bin/sh", "-c", "printf '%s' \"\$1\"", "--", payload)
        assertTrue(result.ok)
        assertEquals(listOf(payload), result.stdout)
        assertEquals(emptyList(), result.stderr)
    }

    @Test
    fun directWorkingDirectoryAndEnvironmentAreScoped() {
        val root = fixtureDirectory()
        val result = NativeExecScope().execute("/system/bin/sh", "-c", "pwd; printf '%s' \"\$KOTLYSH_ANDROID_ENV\"",
            cwd = root, env = mapOf("KOTLYSH_ANDROID_ENV" to "literal é😀"))
        assertTrue(result.ok)
        assertEquals(listOf(root, "literal é😀"), result.stdout)
        val healthy = NativeExecScope().execute("/system/bin/sh", "-c", "printf '%s' \"\${KOTLYSH_ANDROID_ENV-unset}\"")
        assertEquals(listOf("unset"), healthy.stdout)
        assertTrue(healthy.ok)
    }

    @Test
    fun directOutputLimitCleansGroup() = Fixture("direct-limit").use { fixture ->
        val result = NativeExecScope().execute("/system/bin/sh", "-c", fixture.source("printf abcdefghijklmnop; wait"),
            outputLimits = NativeOutputLimits(10, 6))
        assertTrue(result.outputLimited)
        assertFalse(result.ok)
        assertEquals(listOf("abcdef"), result.stdout)
        fixture.assertStopped()
    }

    @Test
    fun shellOutputLimitPoisonsSessionAndCleansGroup() = Fixture("shell-limit").use { fixture ->
        NativeShellSession().use { session -> with(session) {
            val result = fixture.source("printf abcdefghijklmnop; wait")(outputLimits = NativeOutputLimits(10, 6))
            assertTrue(result.outputLimited)
            assertEquals(listOf("abcdef"), result.stdout)
            assertFailsWith<IllegalStateException> { "printf wrong"() }
        } }
        fixture.assertStopped()
    }

    @Test
    fun wallDeadlineCleansGroup() = Fixture("wall-deadline").use { fixture ->
        val result = NativeExecScope().execute("/system/bin/sh", "-c", fixture.source("wait"),
            timeout = 1.seconds, idleTimeout = Duration.INFINITE)
        assertTrue(result.timedOut)
        assertFalse(result.idleTimedOut)
        fixture.assertStopped()
    }

    @Test
    fun idleDeadlineCleansGroup() = Fixture("idle-deadline").use { fixture ->
        val result = NativeExecScope().execute("/system/bin/sh", "-c", fixture.source("wait"),
            timeout = 5.seconds, idleTimeout = 500.milliseconds)
        assertTrue(result.idleTimedOut)
        assertFalse(result.timedOut)
        fixture.assertStopped()
    }

    @Test
    fun shellDeadlinePoisonsSessionAndCleansGroup() = Fixture("shell-deadline").use { fixture ->
        NativeShellSession().use { session -> with(session) {
            val result = fixture.source("wait")(timeout = 1.seconds, idleTimeout = Duration.INFINITE)
            assertTrue(result.timedOut)
            assertFailsWith<IllegalStateException> { "printf wrong"() }
        } }
        fixture.assertStopped()
    }

    @Test
    fun callerCancellationCleansQuietGroup() = cancelRunning("wait")

    @Test
    fun callerCancellationCleansClosedOutputGroup() = cancelRunning("exec 1>/dev/null 2>/dev/null; wait")

    @Test
    fun callerCancellationPreservesIndependentCall() = cancelRunning("wait", sibling = true)

    private fun cancelRunning(tail: String, sibling: Boolean = false) = runBlocking {
        Fixture(if (sibling) "cancel-independent" else if (tail.startsWith("exec")) "cancel-closed" else "cancel-quiet").use { fixture ->
            val release = "${fixture.marker}-release"
            val independentStarted = "${fixture.marker}-independent"
            val independent = if (sibling) async {
                nativeExecSuspending("/system/bin/sh", "-c",
                    "printf ready > '$independentStarted'; while [ ! -e '$release' ]; do sleep 0.01; done; printf survivor", timeout = 10.seconds)
            } else null
            val work = async {
                nativeExecSuspending("/system/bin/sh", "-c", fixture.source(tail), timeout = 10.seconds)
            }
            try {
                val startup = TimeSource.Monotonic.markNow()
                while ((!fixture.ready() || (independent != null && readFile(independentStarted) != "ready")) && startup.elapsedNow() < 3.seconds) delay(5)
                assertTrue(fixture.ready(), "Fixture did not publish its running root and child")
                if (independent != null) assertEquals("ready", readFile(independentStarted))
                val cancelled = TimeSource.Monotonic.markNow()
                work.cancel(CancellationException("android caller cancelled"))
                work.join()
                assertTrue(cancelled.elapsedNow() < 2.seconds, "Cancellation waited for the process deadline")
                val failure = assertFailsWith<CancellationException> { work.await() }
                assertEquals("android caller cancelled", failure.message)
                fixture.assertStopped()
                if (independent != null) {
                    assertFalse(independent.isCompleted)
                    assertTrue(NativeExecScope().execute("/system/bin/touch", release).ok)
                    val survivor = independent.await()
                    assertTrue(survivor.ok)
                    assertEquals(listOf("survivor"), survivor.stdout)
                }
                assertTrue(nativeExecSuspending("/system/bin/sh", "-c", "printf healthy").ok)
            } finally {
                work.cancel(); independent?.cancel()
                withContext(NonCancellable) { work.join(); independent?.join() }
                unlink(release)
                unlink(independentStarted)
            }
        }
    }

    private class Fixture(name: String) : AutoCloseable {
        val marker = "${fixtureDirectory()}/$name-owned"
        private var owned = emptyList<OwnedProcess>()

        fun source(tail: String) = """
            sleep 30 & child=${'$'}!
            root_birth=${'$'}(/system/bin/cut -d ' ' -f 22 /proc/${'$'}${'$'}/stat)
            child_birth=${'$'}(/system/bin/cut -d ' ' -f 22 /proc/${'$'}child/stat)
            command printf '%s:%s\n%s:%s\n' "${'$'}${'$'}" "${'$'}root_birth" "${'$'}child" "${'$'}child_birth" > '$marker'
            $tail
        """.trimIndent()

        private fun capture() {
            if (owned.size == 2) return
            owned = readFile(marker).orEmpty().lineSequence().mapNotNull { line ->
                val fields = line.split(':')
                if (fields.size != 2 || fields[1].isEmpty() || fields[1].any { !it.isDigit() }) null
                else fields[0].toIntOrNull()?.takeIf { it > 1 }?.let { OwnedProcess(it, fields[1]) }
            }.toList()
        }

        fun ready(): Boolean {
            capture()
            return owned.size == 2 && owned.all { it.running() }
        }

        fun assertStopped() {
            capture()
            assertEquals(2, owned.size, "Missing process identities at $marker")
            val wait = TimeSource.Monotonic.markNow()
            while (owned.any { it.running() } && wait.elapsedNow() < 1.seconds) usleep(10_000u)
            assertTrue(owned.none { it.running() }, "Owned root or child remained running")
            assertFalse(owned.first().exists(), "Owned root was not reaped")
        }

        override fun close() {
            capture()
            owned.filter { it.running() }.forEach { kill(it.pid, SIGKILL) }
            unlink(marker)
        }
    }

    private data class OwnedProcess(val pid: Int, val birth: String) {
        private fun fields() = readFile("/proc/$pid/stat")?.substringAfterLast(") ")?.trim()?.split(' ')
        fun exists() = fields()?.getOrNull(19) == birth
        fun running(): Boolean {
            val stat = fields() ?: return false
            return stat.getOrNull(19) == birth && stat[0] !in listOf("Z", "X")
        }
    }

    companion object {
        private fun fixtureDirectory(): String {
            val root = checkNotNull(getenv("KOTLYSH_ANDROID_TEST_DIR")?.toKString()) { "Set KOTLYSH_ANDROID_TEST_DIR to the owned device fixture directory" }
            require(root.startsWith("/data/local/tmp/kotlysh-native-check-") && root.none { it == '\'' || it.isWhitespace() })
            return root
        }

        private fun readFile(path: String): String? {
            val file = fopen(path, "r") ?: return null
            return try {
                val buffer = ByteArray(4096)
                val result = StringBuilder()
                while (result.length < 8192) {
                    if (!buffer.usePinned { fgets(it.addressOf(0), buffer.size, file) != null }) break
                    result.append(buffer.decodeToString().substringBefore('\u0000'))
                }
                result.toString()
            } finally { fclose(file) }
        }
    }
}
