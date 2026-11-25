package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(40)
class RuntimeCustomPathLookupTest {
    @Test
    fun `named custom runtimes use inherited task PATH and retain exact trace identity`() {
        assumeTrue(File.separatorChar == '/' && Files.isExecutable(Path.of("/bin/bash")),
            "Requires Unix executable PATH, directory symlinks and Bash")
        val root = Files.createTempDirectory("kotlysh-custom-runtime-path-")
        var fork: Process? = null
        try {
            val configured = Files.createDirectories(root.resolve("configured-café-日本語/child")).parent
            val alias = Files.createSymbolicLink(root.resolve("alias"), configured.resolve("child"))
            val marker = root.resolve("lua-invoked")
            for ((directory, label) in listOf(configured to "configured", root to "decoy")) {
                for (relative in listOf("lua", "bin/lua")) {
                    val tool = directory.resolve(relative)
                    Files.createDirectories(tool.parent)
                    Files.writeString(tool, "#!/bin/sh\nprintf '%s' '$label' > '${marker.toString().replace("'", "'\\''")}'\nexec /bin/bash --noprofile --norc -s\n")
                    assertTrue(tool.toFile().setExecutable(true))
                }
            }
            val driver = checkNotNull(RuntimeSpecs::class.java.getResource("/com/kotlysh/core/python_driver.py"))
            val resourceRoot = if (driver.protocol == "jar") {
                Path.of((driver.openConnection() as JarURLConnection).jarFileURL.toURI())
            } else {
                var path = Path.of(driver.toURI())
                repeat(4) { path = path.parent }
                path
            }
            val classpath = (listOf(
                RuntimeCustomPathLookupProbe::class.java, RuntimeSpec::class.java, kotlin.Unit::class.java,
                CoroutineScope::class.java, KSerializer::class.java, JsonElement::class.java,
            ).map { Path.of(it.protectionDomain.codeSource.location.toURI()) } + listOf(resourceRoot))
                .distinct().joinToString(File.pathSeparator)
            for (directory in listOf(configured, alias.resolve(".."))) {
                for (path in listOf("bin", "", ":missing")) {
                    Files.deleteIfExists(marker)
                    val log = root.resolve("probe.log")
                    val process = ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dfile.encoding=UTF-8", "-cp", classpath, RuntimeCustomPathLookupProbe::class.java.name,
                        configured.toRealPath().toString(),
                    ).directory(directory.toFile()).apply { environment()["PATH"] = path }
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                    fork = process
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Custom runtime lookup probe did not terminate")
                    val output = Files.readString(log)
                    assertEquals(0, process.exitValue(), output)
                    assertTrue(output.contains("CUSTOM_RUNTIME_PATH_OK"), output)
                    assertEquals("configured", Files.readString(marker))
                    println(output.trimEnd())
                }
            }
        } finally {
            fork?.takeIf { it.isAlive }?.let { process ->
                val owned = process.toHandle().descendants().use { it.toList() } + process.toHandle()
                owned.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
                process.waitFor(5, TimeUnit.SECONDS)
            }
            root.toFile().deleteRecursively()
        }
    }
}

object RuntimeCustomPathLookupProbe {
    private suspend fun <T> lua(block: suspend RuntimeScope.() -> T): T =
        runtime(RuntimeSpec("lua", listOf("lua"), RuntimeSpecs.Bash.wrap), block = block)

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val trace = RuntimeTraceBuffer()
        val owned = AtomicReference<ProcessHandle>()
        val sink = RuntimeTraceSink { event ->
            trace.onEvent(event)
            if (event is RuntimeSessionStarted) owned.set(ProcessHandle.of(event.processId).orElseThrow())
        }
        val script = "printf '%s\\n' 'custom-ready' \"${'$'}PATH\"\npwd -P >&2"
        val result = withTimeout(10.seconds) { withRuntimeTrace(sink) { lua { script() } } }
        check(result.ok && result.stdout == listOf("custom-ready", System.getenv("PATH"))) { "$result" }
        check(result.stderr == listOf(args.single())) { "$result" }
        val events = trace.events
        check(events.all { it.runtime == "lua" && it.sessionId == result.sessionId })
        val session = events.filterIsInstance<RuntimeSessionStarted>().single()
        check(session.command == listOf("lua") && session.processId == owned.get().pid())
        val start = events.filterIsInstance<RuntimeDispatchStarted>().single()
        val hash = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        check(start.scriptSha256 == hash && start.dispatchId == result.dispatchId && start.operation == "exec")
        val output = events.filterIsInstance<RuntimeDispatchOutput>()
        check(output.map { it.sequence } == listOf(0, 1, 2))
        check(output.filter { it.stream == RuntimeStream.Stdout }.map { it.text } == result.stdout)
        check(output.filter { it.stream == RuntimeStream.Stderr }.map { it.text } == result.stderr)
        val terminal = events.filterIsInstance<RuntimeDispatchCompleted>().single()
        check(terminal.exitCode == 0 && terminal.stdoutLines == 2 && terminal.stderrLines == 1)
        val closed = events.last() as RuntimeSessionClosed
        check(!closed.poisoned && closed.processId == session.processId && !owned.get().isAlive)
        events.forEach { println("CUSTOM_RUNTIME_TRACE " + it.toTraceJson()) }
        println("CUSTOM_RUNTIME_PATH_OK root=${owned.get().pid()} start=${owned.get().info().startInstant().orElse(null)}")
    }
}
