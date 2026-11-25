package com.kotlysh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class RuntimeSourceUtf8Test {
    private val malformed = listOf("\uD800", "\uDC00", "\uDC00\uD800", "\uD800\uD800\uDC00", "😀\uD800")

    @Test
    fun `raw malformed UTF16 source fails locally without changing text or closing sessions`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachRuntime {
                for (text in malformed) {
                    val failure = assertFailsWith<IllegalArgumentException> { rawPrint("bad:$text")() }
                    assertTrue(failure.message.orEmpty().contains("unpaired UTF-16 surrogate"))
                    assertTrue(runtime.isOpen)
                }
                val result = rawPrint("café 😀")()
                assertTrue(result.ok, result.stderrText)
                assertEquals(listOf("café 😀"), result.stdout)
            }
        }
        assertEquals(4, trace.events.filterIsInstance<RuntimeDispatchStarted>().size)
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
        assertTrue(trace.events.filterIsInstance<RuntimeSessionClosed>().none { it.poisoned })
    }

    @Test
    fun `malformed expressions fail before evaluation or reference retention`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            eachStructuredRuntime {
                for (text in malformed) {
                    val source = "\"bad:$text\""
                    assertFailsWith<IllegalArgumentException> { evaluateJson(source) }
                    assertFailsWith<IllegalArgumentException> { hold(source) { error("Malformed source must not retain a reference") } }
                    assertTrue(runtime.isOpen)
                }
                assertEquals(42, eval<Int>("42"))
            }
        }
        assertEquals(listOf("eval", "eval"), trace.events.filterIsInstance<RuntimeDispatchStarted>().map { it.operation })
        assertTrue(trace.events.filterIsInstance<RuntimeDispatchFailed>().isEmpty())
    }

    @Test
    fun `malformed source is rejected before waiting behind a busy dispatch`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        withRuntimeTrace(trace) {
            sh {
                val busy = CompletableDeferred<Unit>()
                val active = async {
                    "printf 'busy\\n'; sleep 0.3"(timeout = 5.seconds, output = { busy.complete(Unit) })
                }
                withTimeout(5.seconds) { busy.await() }
                assertFailsWith<IllegalArgumentException> {
                    runtime.exec("printf '\uD800'", timeout = 50.milliseconds)
                }
                assertTrue(active.await().ok)
                assertEquals(listOf("ready"), "printf 'ready\\n'"().stdout)
            }
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchStarted>().size)
    }

    @Test
    fun `valid pairs and escaped surrogate data preserve source hashes and values`(): Unit = runBlocking {
        val trace = RuntimeTraceBuffer()
        val sources = mutableListOf<String>()
        val text = "café 😀 high:\uD800 low:\uDC00"
        withRuntimeTrace(trace) {
            eachStructuredRuntime {
                val source = if (runtime.name == "py") pythonLiteral(text) else jsLiteral(text)
                sources += source
                assertEquals(text, eval<String>(source))
                assertEquals(text, eval<String>("payload", "payload" to value(text)))
                store(text) { assertEquals(text, read<String>(it)) }
            }
        }
        for ((source, event) in sources.zip(trace.events.filterIsInstance<RuntimeDispatchStarted>().filter { it.scriptPreview in sources })) {
            val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(hash, event.scriptSha256)
            assertEquals(source, event.scriptPreview)
        }
        assertEquals(2, trace.events.filterIsInstance<RuntimeDispatchStarted>().count { it.scriptPreview in sources })
    }

    private fun RuntimeScope.rawPrint(text: String): String = when (runtime.name) {
        "py" -> "print('$text', flush=True)"
        "node" -> "console.log('$text')"
        else -> "printf '%s\\n' '$text'"
    }

    private suspend fun eachRuntime(block: suspend RuntimeScope.() -> Unit) {
        sh { block() }
        py { block() }
        node { block() }
        runtime(RuntimeSpecs.Bash.copy(name = "custom")) { block() }
    }

    private suspend fun eachStructuredRuntime(block: suspend StructuredRuntimeScope.() -> Unit) {
        py { block() }
        node { block() }
    }
}
