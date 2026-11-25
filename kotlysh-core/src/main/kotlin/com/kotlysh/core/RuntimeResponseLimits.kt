package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Bounds on a complete structured response, independently of captured stdout and stderr. */
data class RuntimeResponseLimits(
    val maxPayloadBytes: Int = 8 * 1024 * 1024,
    val maxNestingDepth: Int = 128,
) {
    init {
        require(maxPayloadBytes > 0) { "Maximum response payload bytes must be positive" }
        require(maxNestingDepth > 0) { "Maximum response nesting depth must be positive" }
    }

    internal val maxEncodedCharacters: Long get() = ((maxPayloadBytes.toLong() + 2) / 3) * 4

    internal fun checkNesting(json: CharSequence, runtime: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in json) {
            if (quoted) {
                check(character >= ' ') { "Unescaped JSON string control character from $runtime" }
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        depth++
                        check(depth <= maxNestingDepth) {
                            "$runtime response exceeds the nesting depth limit of $maxNestingDepth"
                        }
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
        }
    }
}

internal class RuntimeResponseLimitsContext(val limits: RuntimeResponseLimits) :
    AbstractCoroutineContextElement(RuntimeResponseLimitsContext) {
    companion object Key : CoroutineContext.Key<RuntimeResponseLimitsContext>
}

/** Applies [limits] to each runtime opened by [block] and its child coroutines. */
suspend fun <T> withRuntimeResponseLimits(
    limits: RuntimeResponseLimits,
    block: suspend CoroutineScope.() -> T,
): T = withContext(RuntimeResponseLimitsContext(limits), block)
