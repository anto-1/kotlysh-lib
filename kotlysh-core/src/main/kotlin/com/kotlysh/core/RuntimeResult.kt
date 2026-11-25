package com.kotlysh.core

import kotlinx.serialization.json.JsonObject
import java.util.Collections

data class RuntimeResult @JvmOverloads constructor(
    val runtime: String,
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val idleTimedOut: Boolean = false,
    val outputLimited: Boolean = false,
    val processExited: Boolean = false,
    val sessionId: String = "",
    val dispatchId: Long = 0,
    val elapsedMillis: Long = 0,
    internal val response: JsonObject? = null,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !idleTimedOut && !outputLimited && !processExited
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")
}

/** A detached, read-only JVM snapshot for results produced by the runtime. */
internal fun <T> Collection<T>.immutableSnapshot(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
