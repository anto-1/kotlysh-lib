package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(20)
class ExecWorkingDirectoryTest {
    @Test
    fun `missing working directory prevents process acquisition`() = rejected { it.resolve("missing") }

    @Test
    fun `a regular file working directory prevents process acquisition`() = rejected {
        Files.writeString(it.resolve("regular-file"), "file")
    }

    @Test
    fun `an empty explicit working directory prevents process acquisition`() = rejected { Path.of("") }

    @Test
    fun `a directory symlink selects its configuration and preserves separate streams`(): Unit = runBlocking {
        assumeTrue(File.separatorChar == '/', "Requires Unix directory symlink behavior")
        val root = Files.createTempDirectory("kotlysh-exec-directory-link-")
        try {
            val directory = Files.createDirectory(root.resolve("café-日本語-😀"))
            val link = Files.createSymbolicLink(root.resolve("project-link"), directory)
            val configuration = "configured: café 日本語 😀"
            Files.writeString(directory.resolve("tool.conf"), configuration)
            val result = exec(listOf("python3", "-u", "-c", CONFIGURATION), cwd = link)
            assertTrue(result.ok)
            assertEquals(listOf(configuration), result.stdout)
            assertEquals(listOf(directory.toRealPath().toString()), result.stderr)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `omitting the directory still allows the inherited directory`(): Unit = runBlocking {
        val root = Files.createTempDirectory("kotlysh-exec-directory-default-")
        try {
            val marker = root.resolve("launched")
            val result = exec(command(marker))
            assertTrue(result.ok)
            assertTrue(Files.exists(marker))
            assertEquals(listOf("run"), result.stdout)
            assertEquals(listOf(Path.of(".").toRealPath().toString()), result.stderr)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relative directories retain native symlink parent semantics and separate streams`(): Unit = runBlocking {
        assumeTrue(File.separatorChar == '/', "Requires Unix directory symlink behavior")
        val root = Files.createTempDirectory("kotlysh-exec-relative-directory-")
        try {
            val directory = Files.createDirectories(root.resolve("configured-café-日本語/child"))
            val configured = directory.parent
            val link = Files.createSymbolicLink(root.resolve("alias"), directory)
            val configuration = "relative configuration: café 日本語"
            Files.writeString(configured.resolve("tool.conf"), configuration)
            Files.writeString(root.resolve("tool.conf"), "decoy configuration")
            val inheritedDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            val pids = mutableListOf<Long>()
            for (relativeDirectory in listOf(
                inheritedDirectory.relativize(configured),
                inheritedDirectory.relativize(link).resolve(".."),
            )) {
                assertFalse(relativeDirectory.isAbsolute)
                val result = exec(listOf("python3", "-u", "-c",
                    "import os,pathlib,sys; " +
                        "sys.stdout.write(pathlib.Path('tool.conf').read_text(encoding='utf-8') + '\\n'); " +
                        "sys.stderr.write(os.getcwd() + '\\n' + str(os.getpid()) + '\\n')"), cwd = relativeDirectory)
                assertTrue(result.ok)
                assertEquals(listOf(configuration), result.stdout)
                assertEquals(2, result.stderr.size)
                assertEquals(configured.toRealPath().toString(), result.stderr.first())
                val pid = result.stderr.last().toLong()
                pids += pid
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            }
            println("Relative cwd ownership: roots=$pids")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun rejected(directory: (Path) -> Path): Unit = runBlocking {
        val root = Files.createTempDirectory("kotlysh-exec-directory-rejected-")
        try {
            val marker = root.resolve("launched")
            val selected = directory(root)
            assertFailsWith<IOException> { exec(command(marker), cwd = selected) }
            assertFalse(Files.exists(marker), "The process started outside its selected directory '$selected'")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun command(marker: Path) = listOf("python3", "-u", "-c",
        "import os,pathlib,sys; pathlib.Path(sys.argv[1]).write_text('launched'); " +
            "sys.stdout.buffer.write(b'run'); sys.stderr.buffer.write(os.getcwd().encode('utf-8'))",
        marker.toString(),
    )

    companion object {
        private const val CONFIGURATION = "import os,pathlib,sys; " +
            "sys.stdout.buffer.write(pathlib.Path('tool.conf').read_text(encoding='utf-8').encode('utf-8')); " +
            "sys.stderr.buffer.write(os.getcwd().encode('utf-8'))"
    }
}
