package com.kotlysh.nativesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NativeShellComplexScenarioTest {
    @Test
    fun runsStatefulFileProcessingWorkflowWithQuotedPaths() {
        nativeSh {
            val root = "mktemp -d '/tmp/kotlysh native.XXXXXX'"().stdout.single()
            try {
                "cd '$root'"()
                "mkdir -p 'input files' 'output files'"()
                """
                cat > 'input files/events.txt' <<'EOF'
                beta:3
                alpha:1
                beta:2
                gamma:5
                EOF
                """.trimIndent()()

                val aggregation = """
                    awk -F: '{ totals[$1] += $2 } END { for (key in totals) print key ":" totals[key] }' 'input files/events.txt' \
                        | sort > 'output files/summary.txt'
                    cat 'output files/summary.txt'
                """.trimIndent()()

                assertTrue(aggregation.ok)
                assertEquals(listOf("alpha:1", "beta:5", "gamma:5"), aggregation.stdout)

                val persisted = "cat 'output files/summary.txt'"()
                assertEquals(aggregation.stdout, persisted.stdout)
            } finally {
                "rm -rf '$root'"()
            }
        }
    }

    @Test
    fun preservesLiteralHeredocContentWithShellSyntaxCharacters() {
        nativeSh {
            val root = "mktemp -d /tmp/kotlysh-heredoc.XXXXXX"().stdout.single()
            try {
                "cd '$root'"()
                val write = """
                    cat > literal.txt <<'EOF'
                    ${'$'}HOME stays literal
                    backticks `date` stay literal
                    braces ${'$'}{value} stay literal
                    single quote: '
                    double quote: "
                    EOF
                """.trimIndent()()
                val read = "cat literal.txt"()

                assertTrue(write.ok)
                assertEquals(
                    listOf(
                        "${'$'}HOME stays literal",
                        "backticks `date` stay literal",
                        "braces ${'$'}{value} stay literal",
                        "single quote: '",
                        "double quote: \"",
                    ),
                    read.stdout,
                )
            } finally {
                "rm -rf '$root'"()
            }
        }
    }

    @Test
    fun capturesLargeStdoutAndLargeStderrWithoutDeadlock() {
        nativeSh {
            val result = """
                i=0
                while [ "${'$'}i" -lt 1500 ]; do
                    printf 'out-%04d\n' "${'$'}i"
                    printf 'err-%04d\n' "${'$'}i" >&2
                    i=${'$'}((i + 1))
                done
            """.trimIndent()(
                timeout = 5.seconds,
                idleTimeout = 1.seconds,
            )

            assertTrue(result.ok)
            assertEquals(1500, result.stdout.size)
            assertEquals(1500, result.stderr.size)
            assertEquals("out-0000", result.stdout.first())
            assertEquals("out-1499", result.stdout.last())
            assertEquals("err-0000", result.stderr.first())
            assertEquals("err-1499", result.stderr.last())
        }
    }

    @Test
    fun capturesSingleLongLineLargerThanReadBuffer() {
        nativeSh {
            val result = """
                i=0
                while [ "${'$'}i" -lt 9000 ]; do
                    printf x
                    i=${'$'}((i + 1))
                done
            """.trimIndent()(
                timeout = 5.seconds,
                idleTimeout = 1.seconds,
            )

            assertTrue(result.ok)
            assertEquals(1, result.stdout.size)
            assertEquals(9000, result.stdout.single().length)
            assertTrue(result.stdout.single().all { it == 'x' })
        }
    }

    @Test
    fun streamingOutputKeepsIdleTimeoutAlive() {
        nativeSh {
            val result = """
                i=0
                while [ "${'$'}i" -lt 5 ]; do
                    printf 'pulse-%s\n' "${'$'}i"
                    i=${'$'}((i + 1))
                    sleep 0.05
                done
            """.trimIndent()(
                timeout = 2.seconds,
                idleTimeout = 250.milliseconds,
            )

            assertTrue(result.ok)
            assertEquals(listOf("pulse-0", "pulse-1", "pulse-2", "pulse-3", "pulse-4"), result.stdout)
        }
    }

    @Test
    fun backgroundJobCanBeCoordinatedWithinOneDispatch() {
        nativeSh {
            val result = """
                (
                    sleep 0.05
                    printf 'background done\n'
                ) &
                child=${'$'}!
                printf 'child=%s\n' "${'$'}child"
                wait "${'$'}child"
                printf 'wait status=%s\n' "${'$'}?"
            """.trimIndent()(
                timeout = 2.seconds,
                idleTimeout = 1.seconds,
            )

            assertTrue(result.ok)
            assertTrue(result.stdout.first().startsWith("child="))
            assertEquals("background done", result.stdout[1])
            assertEquals("wait status=0", result.stdout[2])
        }
    }

    @Test
    fun pipelineFailureExitCodeReflectsLastCommandWithoutPoisoningSession() {
        nativeSh {
            val failed = "printf 'payload\\n' | grep 'missing'"()
            val alive = "printf 'alive\\n'"()

            assertFalse(failed.ok)
            assertEquals(1, failed.exitCode)
            assertTrue(alive.ok)
            assertEquals("alive", alive.stdout.single())
        }
    }

    @Test
    fun shellOptionThatExitsBeforeMarkerPoisonsSession() {
        val session = NativeShellSession()
        try {
            with(session) {
                val result = "set -e; false; printf 'unreachable\\n'"(
                    timeout = 2.seconds,
                    idleTimeout = 1.seconds,
                )

                assertFalse(result.ok)
                assertTrue(result.processExited)
                assertFailsWith<IllegalStateException> {
                    "printf 'should-not-run\\n'"()
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun complexSessionStateCanDriveLaterDispatches() {
        nativeSh {
            """
            mode=debug
            make_record() {
                name="$1"
                score="$2"
                printf '%s:%s:%s\n' "${'$'}mode" "${'$'}name" "${'$'}score"
            }
            """.trimIndent()()

            val one = "make_record alpha 7"()
            "mode=release"()
            val two = "make_record beta 11"()

            assertEquals("debug:alpha:7", one.stdout.single())
            assertEquals("release:beta:11", two.stdout.single())
        }
    }
}
