package com.kotlysh.samples

import com.kotlysh.core.exec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The README's opening example is the [hotspots] sample: shown as it ships, and working. */
@Timeout(60)
class ReadmeOpeningExampleTest {
    private val sample = "kotlysh-samples/src/main/kotlin/com/kotlysh/samples/Hotspots.kt"

    @Test
    fun `the opening example ranks the files of the directory it runs in`(): Unit = runBlocking {
        assumeTrue(
            runCatching { exec(listOf("git", "log", "-1", "--format=%H")).ok }.getOrDefault(false),
            "The opening example reads the history of the repository it runs in",
        )
        val found = hotspots()
        assertTrue(found.size in 1..5, found.toString())
        assertTrue(found.all { it.commits > 0 && it.lines > 0 && File(it.file).isFile }, found.toString())
        val scores = found.map { it.commits.toLong() * it.lines }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `the README shows the sample exactly as it ships`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "README.md").isFile && File(it, sample).isFile }
        val shipped = File(root, sample).readText()
        val shown = "@Serializable" + shipped.substringAfter("@Serializable").substringBefore("\nfun main").trimEnd()
        assertTrue(shown.lines().size > 30, "Expected the whole example between @Serializable and main")
        assertTrue(shown in File(root, "README.md").readText(), "README.md no longer shows $sample as it ships")
    }
}
