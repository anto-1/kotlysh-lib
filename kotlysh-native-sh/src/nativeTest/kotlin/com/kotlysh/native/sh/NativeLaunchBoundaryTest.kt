package com.kotlysh.nativesh

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import platform.posix.chmod
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class NativeLaunchBoundaryTest {
    @Test
    fun explicitPathAndEnvironmentArePreparedTogether() {
        val root = nativeTestTempDirectory("launch")
        val executable = "$root/probe"
        try {
            script(executable, "#!/bin/sh\ncommand printf '%s\\n' \"${'$'}TOKEN:${'$'}1\"\n")
            val result = NativeExecScope().execute("probe", "雪; literal", env = mapOf("PATH" to root, "TOKEN" to "é"))
            assertTrue(result.ok)
            assertEquals(listOf("é:雪; literal"), result.stdout)
        } finally { unlink(executable); rmdir(root) }
    }

    @Test
    fun relativeAndEmptyPathComponentsUseTheRequestedWorkingDirectory() {
        val root = nativeTestTempDirectory("launch")
        val executable = "$root/probe"
        try {
            script(executable, "#!/bin/sh\ncommand printf relative\n")
            for (path in listOf(".", "")) {
                val result = NativeExecScope().execute("probe", cwd = root, env = mapOf("PATH" to path))
                assertTrue(result.ok)
                assertEquals(listOf("relative"), result.stdout)
            }
        } finally { unlink(executable); rmdir(root) }
    }

    @Test
    fun deniedPathCandidateDoesNotHideALaterExecutable() {
        val root = nativeTestTempDirectory("launch")
        val first = "$root/denied"
        val second = "$root/allowed"
        val denied = "$first/probe"
        val allowed = "$second/probe"
        try {
            assertEquals(0, mkdir(first, 493u))
            assertEquals(0, mkdir(second, 493u))
            writeNativeTestFile(denied, "#!/bin/sh\nprintf wrong\n")
            assertEquals(0, chmod(denied, 420u))
            script(allowed, "#!/bin/sh\ncommand printf allowed\n")
            val result = NativeExecScope().execute("probe", env = mapOf("PATH" to "$first:$second"))
            assertTrue(result.ok)
            assertEquals(listOf("allowed"), result.stdout)
        } finally {
            unlink(denied); unlink(allowed)
            rmdir(first); rmdir(second); rmdir(root)
        }
    }

    @Test
    fun executableTextFallbackKeepsArgumentsLiteral() {
        val root = nativeTestTempDirectory("launch")
        val executable = "$root/probe"
        try {
            script(executable, "command printf '%s\\n' \"${'$'}1\"\n")
            val result = NativeExecScope().execute(executable, "雪; stays literal")
            assertTrue(result.ok)
            assertEquals(listOf("雪; stays literal"), result.stdout)
        } finally { unlink(executable); rmdir(root) }
    }

    @Test
    fun workingDirectoryFailureKeepsItsExitCodeAndDiagnostic() {
        val result = NativeExecScope().execute("/bin/true", cwd = "/definitely/not/a/kotlysh/cwd")
        assertEquals(126, result.exitCode)
        assertTrue(result.stderrText.contains("chdir failed: /definitely/not/a/kotlysh/cwd"))
    }

    @Test
    fun launchRemainsResponsiveWhileAnotherWorkerAllocates() = runBlocking {
        val ready = CompletableDeferred<String>()
        val allocator = launch(Dispatchers.Default) {
            while (isActive) {
                ready.complete("é雪😀".repeat(8192))
                yield()
            }
        }
        try {
            ready.await()
            val text = "é雪😀".repeat(3000)
            nativeSh {
                repeat(20) {
                    val direct = NativeExecScope().execute("/usr/bin/printf", "%s", text, timeout = 1.seconds)
                    assertTrue(direct.ok, "Concurrent direct launch stalled")
                    assertEquals(listOf(text), direct.stdout)
                    val shell = "command printf '%s' '$text'"(timeout = 1.seconds)
                    assertTrue(shell.ok, "Concurrent shell launch stalled")
                    assertEquals(listOf(text), shell.stdout)
                }
            }
        } finally { allocator.cancelAndJoin() }
    }

    private fun script(path: String, source: String) {
        writeNativeTestFile(path, source)
        assertEquals(0, chmod(path, 493u))
    }
}
