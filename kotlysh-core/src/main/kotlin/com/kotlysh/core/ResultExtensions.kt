package com.kotlysh.core

/** Compatibility alias for [RuntimeResult.stdoutText]; execution and capture are already complete. */
fun RuntimeResult.drain(): String = stdoutText

/** Compatibility helper for transforming [RuntimeResult.stdoutText] without changing failure handling. */
inline fun <T> RuntimeResult.drain(transform: (String) -> T): T = transform(stdoutText)

/**
 * Returns this result when its dispatch succeeded, and otherwise throws a
 * [RuntimeCallException] retaining it. A raw scriptlet never throws by itself;
 * append this where a failed command, deadline, output limit or interpreter
 * exit should stop the task instead of being inspected through [RuntimeResult.ok].
 */
fun RuntimeResult.orThrow(): RuntimeResult =
    if (ok) this else throw RuntimeCallException(this, scriptletFailureDiagnostic(this))
