package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ShellCommandStdinTest {
    @Test
    fun `commands reading stdin see end of input instead of the session transport`(): Unit = runBlocking {
        sh {
            val cat = "cat; echo \"status=\$?\""(timeout = 5.seconds)
            assertTrue(cat.ok, cat.toString())
            assertEquals(listOf("status=0"), cat.stdout)

            val read = "read -r line; echo \"status=\$? line=[\$line]\""(timeout = 5.seconds)
            assertTrue(read.ok, read.toString())
            assertEquals(listOf("status=1 line=[]"), read.stdout)

            // Explicit input and session state are unaffected.
            assertEquals("piped", "printf 'piped\\n' | cat"(timeout = 5.seconds).stdoutText)
            assertEquals("here", "cat <<EOF\nhere\nEOF"(timeout = 5.seconds).stdoutText)
            "cd /tmp; export KOTLYSH_STDIN_TEST=kept"()
            assertEquals("/tmp kept", "echo \"\$PWD \$KOTLYSH_STDIN_TEST\""(timeout = 5.seconds).stdoutText)
        }
    }

    @Test
    fun `the session process is the launched shell`(): Unit = runBlocking {
        sh {
            assertEquals(runtime.processId.toString(), "echo \$\$"().stdoutText)
        }
    }
}
