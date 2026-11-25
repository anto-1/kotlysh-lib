package com.kotlysh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class RuntimeContext(
    val runtime: ProcessRuntime,
) : AbstractCoroutineContextElement(RuntimeContext) {
    companion object Key : CoroutineContext.Key<RuntimeContext>
}

open class RuntimeScope(
    private val context: CoroutineContext,
    internal val runtimeContext: RuntimeContext,
) : CoroutineScope {
    val runtime: ProcessRuntime get() = runtimeContext.runtime

    override val coroutineContext: CoroutineContext
        get() = context + runtimeContext

    suspend operator fun String.invoke(
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        output: RuntimeOutputSink? = null,
    ): RuntimeResult {
        val context = coroutineContext[RuntimeContext]
            ?: throw IllegalStateException("RuntimeContext is missing")

        return context.runtime.exec(
            script = normalizeKotlyshScript(),
            timeout = timeout,
            idleTimeout = idleTimeout,
            output = output,
        )
    }

    /**
     * Holds this scriptlet instead of executing it: `"code"(hold)`. Nothing is
     * dispatched and the session is not used; invoke the returned unit to run it.
     */
    @Suppress("UNUSED_PARAMETER")
    operator fun String.invoke(modifier: HoldExecution): HeldScriptlet {
        val script = normalizeKotlyshScript()
        script.requireUtf8Source()
        return HeldScriptlet(runtime.name, script)
    }

    /**
     * Executes a held scriptlet in this session, exactly as invoking its source
     * here would. The unit must have been written for this session's runtime.
     */
    suspend operator fun HeldScriptlet.invoke(
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        output: RuntimeOutputSink? = null,
    ): RuntimeResult {
        val session = this@RuntimeScope.runtime
        require(runtime == session.name) {
            "A scriptlet held for $runtime cannot run in a ${session.name} session"
        }
        return session.exec(script = script, timeout = timeout, idleTimeout = idleTimeout, output = output)
    }
}

class ShScope(context: CoroutineContext, runtimeContext: RuntimeContext) : RuntimeScope(context, runtimeContext)
class PyScope(context: CoroutineContext, runtimeContext: RuntimeContext) : StructuredRuntimeScope(context, runtimeContext)
class NodeScope(context: CoroutineContext, runtimeContext: RuntimeContext) : StructuredRuntimeScope(context, runtimeContext)
class RubyScope(context: CoroutineContext, runtimeContext: RuntimeContext) : RuntimeScope(context, runtimeContext)

suspend fun <T> runtime(
    spec: RuntimeSpec,
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend RuntimeScope.() -> T,
): T = runRuntime(spec, context, ::RuntimeScope, block)

fun CoroutineScope.launchRuntime(
    spec: RuntimeSpec,
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend RuntimeScope.() -> Unit,
): Job = launch(context) {
    runtime(spec, EmptyCoroutineContext, block)
}

suspend fun <T> sh(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend ShScope.() -> T,
): T = runRuntime(RuntimeSpecs.Bash, context, ::ShScope, block)

fun CoroutineScope.launchSh(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend ShScope.() -> Unit,
): Job = launch(context) {
    sh(EmptyCoroutineContext, block)
}

suspend fun <T> py(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend PyScope.() -> T,
): T = runRuntime(RuntimeSpecs.Python, context, ::PyScope, block)

fun CoroutineScope.launchPy(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend PyScope.() -> Unit,
): Job = launch(context) {
    py(EmptyCoroutineContext, block)
}

suspend fun <T> node(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend NodeScope.() -> T,
): T = runRuntime(RuntimeSpecs.Node, context, ::NodeScope, block)

fun CoroutineScope.launchNode(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend NodeScope.() -> Unit,
): Job = launch(context) {
    node(EmptyCoroutineContext, block)
}

suspend fun <T> ruby(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend RubyScope.() -> T,
): T = runRuntime(RuntimeSpecs.Ruby, context, ::RubyScope, block)

fun CoroutineScope.launchRuby(
    context: CoroutineContext = EmptyCoroutineContext + Dispatchers.IO,
    block: suspend RubyScope.() -> Unit,
): Job = launch(context) {
    ruby(EmptyCoroutineContext, block)
}

private suspend fun <S : RuntimeScope, T> runRuntime(
    spec: RuntimeSpec,
    context: CoroutineContext,
    createScope: (CoroutineContext, RuntimeContext) -> S,
    block: suspend S.() -> T,
): T = withContext(context) {
    val processRuntime = ProcessRuntime(this, spec)
    var failure: Throwable? = null
    try {
        processRuntime.start()
        // Child coroutines in the user's block finish before its session closes.
        coroutineScope {
            createScope(coroutineContext, RuntimeContext(processRuntime)).block()
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        val pendingFailure = failure
        if (pendingFailure == null) processRuntime.closeSuspending()
        else processRuntime.closeAfterFailure(pendingFailure)
    }
}
