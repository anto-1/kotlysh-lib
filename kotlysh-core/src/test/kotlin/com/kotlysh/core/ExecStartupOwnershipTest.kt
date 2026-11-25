package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(20)
class ExecStartupOwnershipTest {
    @Test
    fun `tracker construction failure stops the acquired process before propagating`(): Unit = runBlocking {
        assertStartupCleanup(StartupFailure())
    }

    @Test
    fun `fatal tracker construction failure stops the acquired process before propagating`(): Unit = runBlocking {
        assertStartupCleanup(StartupFatal())
    }

    @Test
    fun `capture failure stops the process when cleanup lookup also fails`(): Unit = runBlocking {
        assertStartupCleanup(StartupFailure(), StartupFailure(3))
    }

    @Test
    fun `fatal capture failure remains primary when cleanup lookup also fails`(): Unit = runBlocking {
        assertStartupCleanup(StartupFatal(), StartupFatal(4))
    }

    private suspend fun assertStartupCleanup(failure: Throwable, cleanupFailure: Throwable? = null) {
        assumeTrue(java.io.File.separatorChar == '/', "Requires Unix process initialization fixture")
        val directory = Files.createTempDirectory("kotlysh-exec-startup-")
        var acquired: Process? = null
        try {
            val source = directory.resolve("OwnedProcessCleanup\$Tracker.java")
            Files.writeString(source, """
                package com.kotlysh.core;
                public final class OwnedProcessCleanup${'$'}Tracker {
                    public static Throwable failure;
                    public static Throwable cleanupFailure;
                    public static Process acquired;
                    public static boolean wasAlive;
                    public OwnedProcessCleanup${'$'}Tracker(Process process) {
                        acquired = process;
                        wasAlive = process.isAlive();
                        if (cleanupFailure == null) fail(failure);
                    }
                    private static void fail(Throwable error) {
                        if (error instanceof Error) throw (Error) error;
                        throw (RuntimeException) error;
                    }
                    public void capture() { fail(failure); }
                    public java.util.List<?> snapshot() { fail(cleanupFailure); return null; }
                }
            """.trimIndent())
            val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "Requires the supported JDK")
            assertEquals(0, compiler.run(null, null, null, "-d", directory.toString(), source.toString()))
            val core = ExecResult::class.java.protectionDomain.codeSource.location
            IsolatedCoreLoader(arrayOf(directory.toUri().toURL(), core), javaClass.classLoader).use { loader ->
                val tracker = loader.loadClass("com.kotlysh.core.OwnedProcessCleanup\$Tracker")
                tracker.getField("failure").set(null, failure)
                tracker.getField("cleanupFailure").set(null, cleanupFailure)

                val actual = runCatching {
                    invokeExec(loader, listOf("/bin/bash", "--noprofile", "--norc", "-c", "exec /bin/sleep 30"))
                }.exceptionOrNull()

                val process = assertNotNull(tracker.getField("acquired").get(null) as Process?,
                    "The injected constructor must receive a genuinely acquired process")
                acquired = process
                assertTrue(tracker.getField("wasAlive").getBoolean(null), "The acquired process was not live at the failure boundary")
                assertFalse(process.isAlive, "Initialization failure left its acquired process running")
                assertSame(failure, actual)
                if (cleanupFailure != null) assertEquals(listOf(cleanupFailure), actual!!.suppressed.toList())
                val healthy = exec(listOf("/bin/printf", "%s", "healthy"))
                assertTrue(healthy.ok)
                assertEquals(listOf("healthy"), healthy.stdout)
            }
        } finally {
            try {
                acquired?.let { process ->
                    OwnedProcessCleanup.finish(process, emptyList(), graceful = false)
                    listOf(process.outputStream, process.inputStream, process.errorStream).forEach { runCatching { it.close() } }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    /** Dependencies keep their normal identity; only this fixture's core classes are isolated. */
    private class IsolatedCoreLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("com.kotlysh.core.")) return super.loadClass(name, resolve)
            return synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
    }

    private suspend fun invokeExec(loader: ClassLoader, command: List<String>): Any? = suspendCoroutine { continuation ->
        try {
            val facade = loader.loadClass("com.kotlysh.core.ProcessExecKt")
            val method = facade.methods.single { it.name.startsWith("exec") && it.parameterCount == 6 }
            val result = method.invoke(null, command, null, emptyMap<String, String>(), null, null, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
        } catch (failure: InvocationTargetException) {
            continuation.resumeWithException(failure.targetException)
        } catch (failure: Throwable) {
            continuation.resumeWithException(failure)
        }
    }

    // Keep original failure identities through coroutine stack recovery.
    private class StartupFailure(val boundary: Int = 1) : IllegalStateException("Injected tracker construction failure")
    private class StartupFatal(val boundary: Int = 2) : Error("Injected fatal tracker construction failure")
}
