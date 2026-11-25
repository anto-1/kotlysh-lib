package com.kotlysh.samples

import com.kotlysh.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

// The README's opening example, exactly as it is shown there: which files under
// the working directory of a git repository are both large and changed often?

@Serializable
data class Hotspot(val file: String, val commits: Int, val lines: Int)

suspend fun hotspots(): List<Hotspot> = withTimeout(10.seconds) {
    val top = "sort -rn | head -500"    // the 500 largest counts are plenty

    // Two questions for git, asked at the same time, each in its own shell:
    // how often did each file change, and how long is each text file?
    val churn = async {
        sh { "git log --format= --name-only --relative | sort | uniq -c | $top"().orThrow().stdout }
    }
    val sizes = async {
        sh { "git grep -Ilz '' | xargs -0 wc -l | $top"().orThrow().stdout }
    }

    // Python is taught once, then asked. The lists go in as data, not as text.
    py {
        """
        def table(lines):
            rows = (line.split(None, 1) for line in lines)
            return {row[1]: int(row[0]) for row in rows if len(row) == 2}

        def rank(churn, sizes):
            churn, sizes = table(churn), table(sizes)
            files = sorted(churn.keys() & sizes.keys(), key=lambda f: (-churn[f] * sizes[f], f))
            return [{"file": f, "commits": churn[f], "lines": sizes[f]} for f in files[:5]]
        """().orThrow()

        eval<List<Hotspot>>(
            "rank(churn, sizes)",
            "churn" to value(churn.await()),
            "sizes" to value(sizes.await()),
        )
    }
}

fun main() = runBlocking {
    hotspots().forEach { println("${it.commits} commits, ${it.lines} lines  ${it.file}") }
}
