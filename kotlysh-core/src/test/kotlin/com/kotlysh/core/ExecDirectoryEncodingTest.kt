package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(30)
class ExecDirectoryEncodingTest {
    @Test
    fun `ASCII native locale cannot redirect an existing Unicode directory`() = probe("ascii", "C")

    @Test
    fun `UTF8 native locale cannot redirect an opaque filesystem byte name`() = probe("opaque", "C.UTF-8")

    @Test
    fun `a literal replacement character directory remains valid`() = probe("literal", "C.UTF-8")

    private fun probe(mode: String, locale: String) {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "Requires Linux native path-byte and locale behavior")
        val classpath = listOf(
            ExecDirectoryEncodingProbe::class.java, ExecResult::class.java, kotlin.Unit::class.java,
            CoroutineScope::class.java, KSerializer::class.java, JsonElement::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
        val log = Files.createTempFile("kotlysh-exec-directory-encoding-", ".log")
        var child: Process? = null
        try {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", classpath, ExecDirectoryEncodingProbe::class.java.name, mode,
            ).apply { environment()["LC_ALL"] = locale }.redirectErrorStream(true).redirectOutput(log.toFile()).start()
            child = process
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Directory encoding probe did not terminate")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("DIRECTORY_ENCODING_OK mode=$mode"), output)
        } finally {
            child?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            Files.deleteIfExists(log)
        }
    }
}

/** Real native names can be obtained from directory iteration even when their text is lossy. */
object ExecDirectoryEncodingProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val mode = args.single()
        check(Charset.defaultCharset() == Charsets.UTF_8)
        val nativeEncoding = Charset.forName(System.getProperty("sun.jnu.encoding"))
        check(nativeEncoding == if (mode == "ascii") Charsets.US_ASCII else Charsets.UTF_8)
        System.setProperty("sun.jnu.encoding", "UTF-8")
        System.setProperty("native.encoding", "UTF-8")
        val root = Files.createTempDirectory("kotlysh-exec-directory-bytes-")
        try {
            val marker = root.resolve("launched")
            val command = listOf("/bin/bash", "--noprofile", "--norc", "-c",
                "printf launched > \"\$1\"; cat tool.conf; printf diagnostic >&2", "_", marker.toString())
            val fallback = Files.createDirectory(root.resolve(if (mode == "ascii") "caf??" else "bad\uFFFD"))
            Files.writeString(fallback.resolve("tool.conf"), "fallback")
            if (mode != "literal") {
                val suffix = if (mode == "ascii") "caf%C3%A9" else "bad%FF"
                val original = Files.createDirectory(Path.of(URI.create(root.toUri().toASCIIString() + suffix)))
                Files.writeString(original.resolve("tool.conf"), "selected")
                val selected = Files.list(root).use { paths ->
                    paths.filter { it.toUri() == original.toUri() }.findFirst().orElseThrow()
                }
                val failure = runCatching { exec(command, cwd = selected) }.exceptionOrNull()
                check(failure is IllegalArgumentException) {
                    "Expected local directory rejection; acquired=${Files.exists(marker)}, failure=$failure, selected=${selected.toUri()}"
                }
                check(failure.message.orEmpty().contains("Working directory")) { "$failure" }
                check(!Files.exists(marker)) { "Lossy working directory acquired a process" }
            }
            // The lookalike is also a legitimate explicit directory when selected exactly.
            val healthy = exec(command, cwd = fallback)
            check(healthy.ok && healthy.stdout == listOf("fallback") && healthy.stderr == listOf("diagnostic"))
            check(Files.exists(marker))
            println("DIRECTORY_ENCODING_OK mode=$mode")
        } finally {
            // NIO retains native bytes; File-based deletion would repeat the lossy conversion.
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
