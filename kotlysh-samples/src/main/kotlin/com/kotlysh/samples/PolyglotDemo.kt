package com.kotlysh.samples

import com.kotlysh.core.node
import com.kotlysh.core.py
import com.kotlysh.core.pythonLiteral
import com.kotlysh.core.jsLiteral
import com.kotlysh.core.sh
import kotlinx.coroutines.runBlocking
import java.io.IOException

fun main(): Unit = runBlocking {
    val (projectRoot, fileCount) = sh {
        val root = "pwd"().stdout.first()
        val count = "find . -type f | wc -l"().stdout.first().trim().toInt()
        root to count
    }

    val pythonSummary = py {
        """
            root = ${pythonLiteral(projectRoot)}
            count = $fileCount
            print(f"{root} has {count} files")
        """().stdout.first()
    }

    println(pythonSummary)

    try {
        node {
            val result = """
                const summary = ${jsLiteral(pythonSummary)};
                console.log(`node saw ${'$'}{summary.length} characters from python`);
            """()
            result.stdout.forEach(::println)
        }
    } catch (error: IOException) {
        println("node runtime I/O unavailable: ${error.message}; shell and python sample completed")
    }
}
