package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(40)
class ExecPathLookupTest {
    @Test
    fun `inherited relative and empty PATH entries follow the selected native directory`() {
        probe(overlay = false)
    }

    @Test
    fun `explicit PATH overlays change child environment while explicit executable paths select binaries`() {
        probe(overlay = true)
    }

    private fun probe(overlay: Boolean) {
        assumeTrue(File.separatorChar == '/', "Requires Unix executable PATH lookup and directory symlinks")
        val root = Files.createTempDirectory("kotlysh-exec-path-")
        var child: Process? = null
        try {
            val inherited = Files.createDirectory(root.resolve("inherited"))
            val configured = Files.createDirectories(root.resolve("configured-café-日本語/child")).parent
            val link = Files.createSymbolicLink(root.resolve("alias"), configured.resolve("child"))
            val override = Files.createDirectory(root.resolve("override"))
            for ((directory, label) in listOf(inherited to "inherited", configured to "configured", root to "decoy", override to "override")) {
                for (name in listOf("python3", "node")) {
                    for (relative in listOf(name, "bin/$name")) {
                        val tool = directory.resolve(relative)
                        Files.createDirectories(tool.parent)
                        Files.writeString(tool, "#!/bin/sh\nprintf '%s\\n' '$label' \"\$PATH\"\npwd -P >&2\nprintf '%s\\n' \"\$\$\" >&2\n")
                        assertTrue(tool.toFile().setExecutable(true))
                    }
                }
            }
            val classpath = listOf(
                ExecPathLookupProbe::class.java, ExecResult::class.java, kotlin.Unit::class.java,
                CoroutineScope::class.java, KSerializer::class.java, JsonElement::class.java,
            ).map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
            val directories = listOf(inherited.relativize(configured), inherited.relativize(link).resolve(".."))
            val cases = if (overlay) listOf(directories.first() to inherited.resolve("bin").toString())
                else directories.flatMap { directory -> listOf("bin", "", ":missing").map { directory to it } }
            for ((directory, path) in cases) {
                val log = root.resolve("probe.log")
                val process = ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8", "-cp", classpath, ExecPathLookupProbe::class.java.name,
                    directory.toString(), configured.toRealPath().toString(), if (overlay) override.toString() else "",
                ).directory(inherited.toFile()).apply { environment()["PATH"] = path }
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                child = process
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "PATH lookup probe did not terminate")
                val output = Files.readString(log)
                assertEquals(0, process.exitValue(), output)
                assertTrue(output.contains("EXEC_PATH_OK"), output)
                println(output.trimEnd())
            }
        } finally {
            child?.takeIf { it.isAlive }?.let { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            root.toFile().deleteRecursively()
        }
    }
}

object ExecPathLookupProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val directory = Path.of(args[0])
        val configured = args[1]
        val override = args[2].takeIf(String::isNotEmpty)
        val pids = mutableListOf<Long>()
        suspend fun checked(command: String, label: String, env: Map<String, String> = emptyMap()) {
            val result = exec(listOf(command), cwd = directory, env = env)
            check(result.ok && result.stdout == listOf(label, env["PATH"] ?: System.getenv("PATH"))) { "$result" }
            check(result.stderr.size == 2 && result.stderr.first() == configured) { "$result" }
            val pid = result.stderr.last().toLong()
            pids += pid
            check(!ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
        for (name in listOf("python3", "node")) {
            if (override == null) checked(name, "configured")
            else {
                val env = mapOf("PATH" to override)
                checked(name, "inherited", env)
                checked(Path.of(override, name).toString(), "override", env)
                checked("bin/$name", "configured", env)
            }
        }
        println("EXEC_PATH_OK directory=$directory overlay=${override != null} roots=$pids")
    }
}
