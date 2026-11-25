package com.kotlysh.core

/** The type of the [hold] execution modifier. */
class HoldExecution internal constructor() {
    override fun toString(): String = "hold"
}

/**
 * Execution modifier for string invocation: `"code"(hold)` keeps the ordinary
 * scriptlet form and returns a [HeldScriptlet] instead of executing it.
 */
val hold: HoldExecution = HoldExecution()

/**
 * A scriptlet whose execution is on hold. It records its normalized source and
 * the runtime of the block it was written in, and owns no session: invoke it as
 * `unit()` inside any live session of that runtime, any number of times.
 */
class HeldScriptlet internal constructor(
    /** Canonical name of the runtime this scriptlet was written for. */
    val runtime: String,
    /** The source that an invocation dispatches. */
    val script: String,
) {
    override fun toString(): String = "HeldScriptlet(runtime=$runtime)"
}
