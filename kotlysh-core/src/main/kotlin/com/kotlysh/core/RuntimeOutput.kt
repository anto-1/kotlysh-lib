package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Bounds on captured stdout and stderr combined, for each dispatch or [exec] call.
 * Characters are UTF-16 units excluding line separators; empty lines count toward
 * [maxLinesPerDispatch]. Structured response payloads have separate limits.
 */
data class RuntimeOutputLimits(
    val maxLinesPerDispatch: Int = 10_000,
    val maxCharactersPerDispatch: Int = 1_000_000,
) {
    init {
        require(maxLinesPerDispatch > 0) { "Maximum output lines must be positive" }
        require(maxCharactersPerDispatch > 0) { "Maximum output characters must be positive" }
    }
}

internal class RuntimeOutputLimitsContext(val limits: RuntimeOutputLimits) :
    AbstractCoroutineContextElement(RuntimeOutputLimitsContext) {
    companion object Key : CoroutineContext.Key<RuntimeOutputLimitsContext>
}

/**
 * Applies [limits] to runtimes opened and [exec] calls made by [block] and its
 * child coroutines. This policy takes precedence over legacy trace output limits.
 */
suspend fun <T> withRuntimeOutputLimits(
    limits: RuntimeOutputLimits,
    block: suspend CoroutineScope.() -> T,
): T = withContext(RuntimeOutputLimitsContext(limits), block)

internal fun CoroutineContext.runtimeOutputLimits(): RuntimeOutputLimits {
    this[RuntimeOutputLimitsContext]?.let { return it.limits }
    // Preserve explicitly installed trace contexts as a compatibility adapter.
    // Automatic tracing never selects the process output policy.
    this[RuntimeTraceContext]?.limits?.let {
        return RuntimeOutputLimits(it.maxOutputLinesPerDispatch, it.maxOutputCharactersPerDispatch)
    }
    return RuntimeOutputLimits()
}

internal fun String.utf16PrefixWithin(maxCharacters: Int): String {
    var end = minOf(length, maxCharacters)
    if (end > 0 && end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return substring(0, end)
}

enum class RuntimeStream {
    Stdout,
    Stderr,
}

data class RuntimeOutputLine(
    val runtime: String,
    val stream: RuntimeStream,
    val text: String,
)

fun interface RuntimeOutputSink {
    fun onLine(line: RuntimeOutputLine)
}

class RuntimeOutputBuffer : RuntimeOutputSink {
    private val collected = mutableListOf<RuntimeOutputLine>()

    val lines: List<RuntimeOutputLine>
        get() = synchronized(collected) { collected.toList() }

    val stdout: List<String>
        get() = lines.filter { it.stream == RuntimeStream.Stdout }.map { it.text }

    val stderr: List<String>
        get() = lines.filter { it.stream == RuntimeStream.Stderr }.map { it.text }

    val stdoutText: String
        get() = stdout.joinToString("\n")

    val stderrText: String
        get() = stderr.joinToString("\n")

    override fun onLine(line: RuntimeOutputLine) {
        synchronized(collected) { collected += line }
    }
}
