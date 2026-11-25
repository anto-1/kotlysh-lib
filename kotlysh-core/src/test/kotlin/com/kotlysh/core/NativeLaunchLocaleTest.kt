package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(30)
class NativeLaunchLocaleTest {
    @Test
    fun `nonrepresentable exec arguments fail before launch`() = probe("arguments")

    @Test
    fun `nonrepresentable environment keys and values fail before launch`() = probe("environment")

    @Test
    fun `nonrepresentable runtime arguments fail before body or startup trace`() = probe("runtime")

    @Test
    fun `UTF8 scriptlets and separate streams still work with an ASCII native locale`() = probe("transport")

    private fun probe(mode: String) {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "Requires Linux native locale behavior")
        val resources = checkNotNull(RuntimeSpecs::class.java.getResource("/com/kotlysh/core/node_driver.js"))
        val resourceRoot = if (resources.protocol == "jar") {
            Path.of((resources.openConnection() as JarURLConnection).jarFileURL.toURI())
        } else {
            var path = Path.of(resources.toURI())
            repeat(4) { path = path.parent }
            path
        }
        val classpath = listOf(
            NativeLaunchLocaleProbe::class.java, RuntimeSpecs::class.java, kotlin.Unit::class.java,
            CoroutineScope::class.java, KSerializer::class.java, JsonElement::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.plus(listOf(resourceRoot))
            .distinct().joinToString(File.pathSeparator)
        val log = Files.createTempFile("kotlysh-native-locale", ".log")
        var child: Process? = null
        try {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", classpath, NativeLaunchLocaleProbe::class.java.name, mode,
            ).apply { environment()["LC_ALL"] = "C" }.redirectErrorStream(true).redirectOutput(log.toFile()).start()
            child = process
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Native locale probe did not terminate")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("NATIVE_LOCALE_OK mode=$mode"), output)
        } finally {
            child?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            Files.deleteIfExists(log)
        }
    }
}

/** A fresh JVM is necessary: the JDK captures its native encoding during startup. */
object NativeLaunchLocaleProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        check(java.nio.charset.Charset.defaultCharset() == Charsets.UTF_8)
        check(java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding")) == Charsets.US_ASCII)
        // Public properties can change after startup; native encoding cannot.
        System.setProperty("sun.jnu.encoding", "UTF-8")
        System.setProperty("native.encoding", "UTF-8")
        val root = Files.createTempDirectory("kotlysh-native-locale-child")
        try {
            val marker = root.resolve("launched")
            val command = listOf("/bin/bash", "-c", "printf launched > \"\$1\"; printf '%s' \"\$2\"", "_", marker.toString())
            suspend fun rejected(operation: suspend () -> Unit) {
                val failure = runCatching { operation() }.exceptionOrNull()
                check(failure is IllegalArgumentException) { "Expected local rejection, got $failure" }
                check(failure.message.orEmpty().contains("native process encoding")) { "$failure" }
                check(!Files.exists(marker)) { "Lossy launch acquired a process" }
            }
            when (args.single()) {
                "arguments" -> for (text in listOf("café", "日本語", "😀")) {
                    rejected { exec(command + text) }
                }
                "environment" -> for (environment in listOf(mapOf("KOTLYSH_LOCALE" to "café"), mapOf("KOTLYSH_café" to "value"))) {
                    rejected { exec(command, env = environment) }
                }
                "runtime" -> {
                    val trace = RuntimeTraceBuffer()
                    withRuntimeTrace(trace) {
                        rejected {
                            runtime(RuntimeSpecs.Bash.copy(command = command + "café")) {
                                error("Lossy runtime reached its body")
                            }
                        }
                    }
                    check(trace.events.none { it is RuntimeSessionStarted || it is RuntimeDispatchStarted })
                }
                "transport" -> {
                    val text = "café 日本語 😀"
                    val result = exec(listOf("python3", "-c",
                        "import sys; text=bytes.fromhex('" + text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) } +
                            "').decode('utf-8'); print(text); print(text,file=sys.stderr)"),
                        env = mapOf("PYTHONIOENCODING" to "utf-8"))
                    check(result.ok && result.stdout == listOf(text) && result.stderr == listOf(text))
                    sh { check("printf '%s\\n' '$text'"().stdout == listOf(text)) }
                    py { check("print('$text')"().stdout == listOf(text)) }
                    node { check("console.log('$text')"().stdout == listOf(text)) }
                }
                else -> error("Unknown probe mode")
            }
            println("NATIVE_LOCALE_OK mode=${args.single()}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
