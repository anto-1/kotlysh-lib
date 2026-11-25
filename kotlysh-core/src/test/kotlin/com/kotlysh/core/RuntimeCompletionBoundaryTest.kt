package com.kotlysh.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class RuntimeCompletionBoundaryTest {
    @Test
    fun `incremental log scans bound malformed delimited statuses and preserve valid frames`(): Unit = runBlocking {
        for (status in listOf("", "+", "-", "1")) {
            val spec = RuntimeSpec("delimited-completion", RuntimeSpecs.Bash.command) { _, marker ->
                "printf '%s' 'visible${marker.take(marker.length / 2)}'; sleep 0.05; " +
                    "printf '%s' '${marker.drop(marker.length / 2)}:$status:partial'; " +
                    "printf '%s' 'diagnostic$marker:$status:partial' >&2; sleep 30\n"
            }
            withRuntimeTrace(RuntimeTraceBuffer(), RuntimeTraceLimits(maxOutputCharactersPerDispatch = 17)) {
                runtime(spec) {
                    val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                    val result = "ignored"(timeout = 300.milliseconds, idleTimeout = 10.seconds)
                    if (status == "1") {
                        assertTrue(result.timedOut)
                        assertFalse(result.outputLimited)
                        assertEquals(listOf("visible"), result.stdout)
                        assertEquals(listOf("diagnostic"), result.stderr)
                    } else {
                        assertFalse(result.timedOut)
                        assertTrue(result.outputLimited)
                        // Both pipes share the budget; either reader can consume it first.
                        val stdout = result.stdout.joinToString("")
                        val stderr = result.stderr.joinToString("")
                        assertTrue(stdout.isNotEmpty() || stderr.isNotEmpty(), "status='$status': $result")
                        assertTrue(stdout.startsWith("visible".take(stdout.length)), "status='$status': $result")
                        assertTrue(stderr.startsWith("diagnostic".take(stderr.length)), "status='$status': $result")
                        assertTrue((result.stdout + result.stderr).sumOf { it.length } <= 17)
                    }
                    assertFalse(runtime.isOpen)
                    assertFalse(process.isAlive)
                }
            }
        }
    }

    @Test
    fun `wall deadline does not expose partial markers or statuses as logs`(): Unit = runBlocking {
        assertIncompleteFrames(idleDeadline = false)
    }

    @Test
    fun `idle deadline does not expose partial markers or statuses as logs`(): Unit = runBlocking {
        assertIncompleteFrames(idleDeadline = true)
    }

    @Test
    fun `interpreter exit does not expose unfinished markers as logs`(): Unit = runBlocking {
        for (fragment in incompleteMarkers()) {
            val trace = RuntimeTraceBuffer()
            val spec = RuntimeSpec("exiting-completion", RuntimeSpecs.Bash.command) { _, marker ->
                "printf '%s' 'visible${fragment(marker)}'; printf '%s' 'diagnostic${fragment(marker)}' >&2; exit 9\n"
            }
            withRuntimeTrace(trace) {
                runtime(spec) {
                    val result = "ignored"(timeout = 5.seconds, idleTimeout = 5.seconds)
                    assertEquals(listOf("visible"), result.stdout)
                    assertEquals(listOf("diagnostic"), result.stderr)
                    assertEquals(9, result.exitCode)
                    assertTrue(result.processExited)
                    assertFalse(result.timedOut)
                    assertFalse(result.idleTimedOut)
                    assertFalse(runtime.isOpen)
                }
            }
            assertEquals(listOf("visible", "diagnostic").sorted(), trace.events
                .filterIsInstance<RuntimeDispatchOutput>().map { it.text }.sorted())
        }
    }

    @Test
    fun `a newline resolves marker-like prefixes as ordinary output`(): Unit = runBlocking {
        val spec = RuntimeSpec("literal-completion", RuntimeSpecs.Bash.command) { _, marker ->
            "printf '%s\\n' 'visible${marker.take(marker.length / 2)}'; printf '%s\\n' '${marker}:0'; printf '%s\\n' '${marker}:0' >&2\n"
        }
        runtime(spec) {
            repeat(2) {
                val result = "ignored"()
                assertTrue(result.ok)
                assertTrue(result.stdout.single().startsWith("visible__KOTLYSH_"))
                assertTrue(runtime.isOpen)
            }
        }
    }

    @Test
    fun `ordinary trailing underscores and short ambiguous prefixes are preserved`(): Unit = runBlocking {
        for (suffix in listOf("_", "__", "__K", "__KOTLYSH", "__KOTLYSH_unrelated")) {
            for (exits in listOf(false, true)) {
                val expected = "ordinary$suffix"
                val spec = RuntimeSpec("ordinary-suffix", RuntimeSpecs.Bash.command) { _, _ ->
                    "printf '%s' '$expected'; " + if (exits) "exit 9\n" else "sleep 30\n"
                }
                runtime(spec) {
                    val result = "ignored"(timeout = 300.milliseconds, idleTimeout = 10.seconds)
                    assertEquals(listOf(expected), result.stdout)
                    assertEquals(exits, result.processExited)
                    assertEquals(!exits, result.timedOut)
                }
            }
        }
    }

    @Test
    fun `nonmatching marker-like text stays ordinary output at a deadline`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val spec = RuntimeSpec("ordinary-marker", RuntimeSpecs.Bash.command) { _, marker ->
            "printf '%s' '${marker}:bad-status'; sleep 30\n"
        }
        withRuntimeTrace(trace) {
            runtime(spec) {
                val result = "ignored"(timeout = 300.milliseconds, idleTimeout = 10.seconds)
                assertTrue(result.timedOut)
                assertTrue(result.stdout.single().endsWith(":bad-status"))
                assertEquals(result.stdout, trace.events.filterIsInstance<RuntimeDispatchOutput>().map { it.text })
            }
        }
    }

    private suspend fun assertIncompleteFrames(idleDeadline: Boolean) {
        for (fragment in incompleteMarkers() + listOf<(String) -> String>(
            { "$it:0:eyJ2" },
        )) {
            for (traced in listOf(false, true)) {
                val trace = RuntimeTraceBuffer()
                val spec = RuntimeSpec("partial-completion", RuntimeSpecs.Bash.command) { _, marker ->
                    "printf '%s' 'visible${fragment(marker)}'; printf '%s' 'diagnostic${fragment(marker)}' >&2; sleep 30\n"
                }
                suspend fun execute() {
                    runtime(spec) {
                        val process = ProcessHandle.of(checkNotNull(runtime.processId)).orElseThrow()
                        val result = "ignored"(
                            timeout = if (idleDeadline) 10.seconds else 300.milliseconds,
                            idleTimeout = if (idleDeadline) 300.milliseconds else 10.seconds,
                        )
                        assertEquals(!idleDeadline, result.timedOut)
                        assertEquals(idleDeadline, result.idleTimedOut)
                        assertEquals(listOf("visible"), result.stdout)
                        assertEquals(listOf("diagnostic"), result.stderr)
                        assertFalse(result.outputLimited)
                        assertFalse(result.ok)
                        assertFalse(runtime.isOpen)
                        assertFalse(process.isAlive)
                        assertFailsWith<IllegalStateException> { "next"() }
                    }
                }
                if (traced) {
                    withRuntimeTrace(trace, RuntimeTraceLimits(maxOutputCharactersPerDispatch = 17)) { execute() }
                    assertEquals(listOf("visible", "diagnostic").sorted(), trace.events
                        .filterIsInstance<RuntimeDispatchOutput>().map { it.text }.sorted())
                    assertEquals(1, trace.events.filterIsInstance<RuntimeDispatchCompleted>().size)
                    assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().single().poisoned)
                } else execute()
            }
        }
    }

    private fun incompleteMarkers(): List<(String) -> String> = listOf<(String) -> String>(
        { it.take(it.length / 2) },
        { it },
        { "$it:" },
        { "$it:+" },
        { "$it:-" },
    ).flatMap { fragment -> listOf(fragment, { marker: String -> fragment(marker) + "\r" }) }
}
