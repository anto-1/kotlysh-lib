package com.kotlysh.samples

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class MaintainedDslSyntaxTest {
    private val maintainedSources = listOf(
        "kotlysh-samples/src/main/kotlin/com/kotlysh/samples/PolyglotDemo.kt",
        "kotlysh-samples/src/main/kotlin/com/kotlysh/samples/Hotspots.kt",
        "README.md",
    )

    @Test
    fun `maintained literal dispatches do not pre-normalize with trimIndent`() {
        val libraryRoot = libraryRoot()
        val redundantNormalization = Regex("""\.trimIndent\(\)\s*\(""")

        for (relative in maintainedSources) {
            val source = Files.readString(libraryRoot.resolve(relative))
            assertFalse(redundantNormalization.containsMatchIn(source),
                "$relative must preserve the direct multiline-literal invocation shape")
        }
    }

    private fun libraryRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        for (ancestor in generateSequence(start) { it.parent }) {
            for (candidate in listOf(ancestor, ancestor.resolve("kotlysh-lib"))) {
                if (maintainedSources.all { Files.isRegularFile(candidate.resolve(it)) }) return candidate
            }
        }
        error("Cannot locate the kotlysh-lib source tree from $start")
    }
}
