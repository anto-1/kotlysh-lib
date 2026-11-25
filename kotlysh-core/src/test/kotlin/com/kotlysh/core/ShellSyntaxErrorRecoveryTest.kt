package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class ShellSyntaxErrorRecoveryTest {
    @Test
    fun `malformed scriptlets fail their own dispatch and keep the session`(): Unit = runBlocking {
        sh {
            "cd /tmp; kept=state"(timeout = 3.seconds)
            val malformed = listOf(
                "if then fi (",
                "echo 'unterminated",
                "echo \"unterminated",
                "if true; then echo open",
                "echo \$(",
                "for x in 1 2; do",
            )
            for (source in malformed) {
                val failed = source(timeout = 3.seconds)
                assertFalse(failed.ok, source)
                assertFalse(failed.timedOut, source)
                assertFalse(failed.processExited, source)
                assertEquals(2, failed.exitCode, source)
                assertTrue(failed.stderr.isNotEmpty(), source)
                assertEquals(emptyList(), failed.stdout, source)
                assertTrue(runtime.isOpen, source)
            }
            assertEquals("/tmp state", "echo \"\$PWD \$kept\""(timeout = 3.seconds).stdoutText)
        }
    }

    @Test
    fun `an unfinished here-document cannot consume completion bookkeeping`(): Unit = runBlocking {
        sh {
            val result = "cat <<EOF\nbody"(timeout = 3.seconds)
            assertFalse(result.timedOut)
            assertEquals(listOf("body"), result.stdout)
            assertEquals(listOf("ready"), "echo ready"(timeout = 3.seconds).stdout)
        }
    }

    @Test
    fun `quotes escapes functions and multiline constructs evaluate as written`(): Unit = runBlocking {
        sh {
            val source = """
                greet() { printf '%s|%s\n' "${'$'}1" 'it'\''s'; }
                greet "a 'quoted' \"word\""
                for item in one two; do
                    printf '%s\n' "${'$'}item"
                done
                cat <<'EOF'
                literal ${'$'}HOME 'single' \n
                EOF
            """
            val result = source(timeout = 3.seconds)
            assertTrue(result.ok, result.stderrText)
            assertEquals(
                listOf("a 'quoted' \"word\"|it's", "one", "two", "literal \$HOME 'single' \\n"),
                result.stdout,
            )
            assertEquals(listOf("again|it's"), "greet again"(timeout = 3.seconds).stdout)
            assertEquals(3, "(exit 3)"(timeout = 3.seconds).exitCode)
        }
    }
}
