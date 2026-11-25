package com.kotlysh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface RuntimeArgument

/** JSON data sent separately from executable source. */
class JsonArgument internal constructor(internal val json: JsonElement) : RuntimeArgument

fun jsonValue(json: JsonElement): JsonArgument = JsonArgument(json)
inline fun <reified T> value(value: T): JsonArgument = jsonValue(Json.encodeToJsonElement(value))

/** A returned value and the ordinary stdout, stderr and status of its dispatch. */
data class Evaluation<T>(val value: T, val execution: RuntimeResult)

class RuntimeCallException internal constructor(
    val execution: RuntimeResult,
    diagnostic: String,
) : IllegalStateException(diagnostic) {
    constructor(execution: RuntimeResult) : this(execution, callFailureDiagnostic(execution))

    /** Full foreign type, independent of the bounded exception message. */
    val errorType: String? get() = execution.errorDiagnostic("type")

    /** Full foreign message, independent of the bounded exception message. */
    val errorMessage: String? get() = execution.errorDiagnostic("message")
}

private fun RuntimeResult.errorDiagnostic(name: String): String? =
    (response?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A raw scriptlet has no structured error, so its status is the primary diagnostic. */
internal fun scriptletFailureDiagnostic(execution: RuntimeResult): String = callFailureDiagnostic(
    execution,
    execution.exitCode?.takeIf { it != 0 }?.let { " scriptlet failed with exit code $it" } ?: " scriptlet failed",
)

private fun callFailureDiagnostic(
    execution: RuntimeResult,
    outcome: String = " request failed",
): String = boundedFailureDiagnostic {
    appendDiagnostic(execution.runtime, outcome)
    if (execution.timedOut) appendDiagnostic(" after timeout")
    if (execution.idleTimedOut) appendDiagnostic(" after idle timeout")
    if (execution.outputLimited) appendDiagnostic(" after exceeding the output limit")
    if (execution.processExited) appendDiagnostic(" because the interpreter exited")
    val type = execution.errorDiagnostic("type")
    val message = execution.errorDiagnostic("message")
    if (type != null) appendDiagnostic(": ", type)
    if (message != null) appendDiagnostic(": ", message)
    else if (length <= MAX_FAILURE_DIAGNOSTIC_CHARACTERS && execution.stderr.isNotEmpty()) {
        appendDiagnostic(": ", stderrDiagnosticPrefix(execution.stderr))
    }
}

class RuntimeDecodeException(val execution: RuntimeResult, cause: Exception) : IllegalArgumentException(
    decodeFailureDiagnostic(execution, cause),
    cause,
)

/** Read critical getters before truncation; bound copies while preserving the original cause. */
private fun decodeFailureDiagnostic(execution: RuntimeResult, cause: Exception): String {
    val detail = decodeFailureMessage(cause)
    return boundedFailureDiagnostic {
        appendDiagnostic(
            execution.runtime,
            " returned a value incompatible with the requested Kotlin type",
            if (detail != null) ": " else "",
            detail ?: "",
        )
    }
}

private const val MAX_FAILURE_DIAGNOSTIC_CHARACTERS = 4_096

private inline fun boundedFailureDiagnostic(build: StringBuilder.() -> Unit): String {
    val limit = MAX_FAILURE_DIAGNOSTIC_CHARACTERS
    val text = buildString(limit + 1, build)
    if (text.length <= limit) return text
    var end = limit - 1
    if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end) + "…"
}

private fun StringBuilder.appendDiagnostic(vararg parts: String) {
    for (part in parts) {
        append(part, 0, minOf(part.length, MAX_FAILURE_DIAGNOSTIC_CHARACTERS + 1 - length))
        if (length > MAX_FAILURE_DIAGNOSTIC_CHARACTERS) break
    }
}

/** Capture only the fallback's existing 1,000-unit prefix, without joining the full output. */
private fun stderrDiagnosticPrefix(lines: List<String>): String {
    val limit = 1_000
    val text = buildString(limit + 1) {
        val iterator = lines.iterator()
        var first = true
        while (length <= limit && iterator.hasNext()) {
            if (!first) append('\n')
            if (length > limit) break
            val line = iterator.next()
            append(line, 0, minOf(line.length, limit + 1 - length))
            first = false
        }
    }
    if (text.length <= limit) return text
    val end = if (text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit - 1 else limit
    return text.substring(0, end)
}

private fun decodeFailureMessage(cause: Exception): String? = try {
    cause.message
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    null
}

open class StructuredRuntimeScope(
    context: CoroutineContext,
    runtimeContext: RuntimeContext,
) : RuntimeScope(context, runtimeContext) {
    /** Evaluate an expression and return framed JSON; print/console output stays separate. */
    suspend fun evaluateJson(
        expression: String,
        vararg bindings: Pair<String, RuntimeArgument>,
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        output: RuntimeOutputSink? = null,
    ): Evaluation<JsonElement> {
        val result = expressionRequest("eval", expression, bindings, timeout, idleTimeout, output)
        val response = result.expectResponse("value")
        return Evaluation(checkNotNull(response["value"]) { "Missing value in runtime response" }, result)
    }

    /** Keep a foreign object for the duration of [block]. */
    suspend fun <T> hold(
        expression: String,
        vararg bindings: Pair<String, RuntimeArgument>,
        timeout: Duration = 30.seconds,
        idleTimeout: Duration = timeout,
        output: RuntimeOutputSink? = null,
        block: suspend (RuntimeRef) -> T,
    ): T {
        val result = expressionRequest("hold", expression, bindings, timeout, idleTimeout, output)
        return scopedReference(reference(result), block)
    }

    /** Store JSON data for the duration of [block]. */
    suspend fun <T> storeJson(
        json: JsonElement,
        timeout: Duration = 30.seconds,
        block: suspend (RuntimeRef) -> T,
    ): T = storeJsonInternal(json, timeout, emptyList(), block)

    private suspend fun <T> storeJsonInternal(
        json: JsonElement,
        timeout: Duration,
        inputSessionIds: List<String>,
        block: suspend (RuntimeRef) -> T,
    ): T {
        val snapshot = validatedJsonSnapshot(json, runtimeContext.runtime.jsonNumberPolicy)
        val reference = reference(runtimeContext.runtime.request(buildJsonObject {
            put("op", "store")
            put("value", snapshot)
        }, timeout = timeout, traceInputSessionIds = inputSessionIds))
        return scopedReference(reference, block)
    }

    /** Read a JSON snapshot from a reference owned by its live session. */
    suspend fun readJson(reference: RuntimeRef, timeout: Duration = 30.seconds): JsonElement =
        reference.readJsonInternal(timeout)

    /** Copy a JSON snapshot into this session for the duration of [block]. */
    suspend fun <T> copy(
        reference: RuntimeRef,
        timeout: Duration = 30.seconds,
        block: suspend (RuntimeRef) -> T,
    ): T = storeJsonInternal(
        reference.readJsonInternal(timeout),
        timeout,
        listOf(reference.ownerSessionId),
        block,
    )

    private suspend fun <T> scopedReference(
        reference: RuntimeRef,
        block: suspend (RuntimeRef) -> T,
    ): T {
        var failure: Throwable? = null
        try {
            return block(reference)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    reference.releaseInternal()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    failure.addSuppressed(cleanup)
                }
            }
        }
    }

    private fun reference(result: RuntimeResult): RuntimeRef = RuntimeRef(
        runtimeContext.runtime,
        checkNotNull(result.expectResponse("ref")["id"]?.jsonPrimitive?.contentOrNull) { "Missing reference ID" },
    )

    private suspend fun expressionRequest(
        operation: String,
        expression: String,
        bindings: Array<out Pair<String, RuntimeArgument>>,
        timeout: Duration,
        idleTimeout: Duration,
        output: RuntimeOutputSink?,
    ): RuntimeResult {
        val runtime = runtimeContext.runtime
        val prepared = RuntimeExpressionRequest.prepare(operation, expression, bindings, runtime.jsonNumberPolicy)
        return runtime.request(
            prepared.payload, prepared.references, timeout, idleTimeout, output, traceScript = expression,
        )
    }
}

suspend inline fun <reified V, T> StructuredRuntimeScope.store(
    value: V,
    timeout: Duration = 30.seconds,
    noinline block: suspend (RuntimeRef) -> T,
): T = storeJson(Json.encodeToJsonElement(value), timeout, block)

suspend inline fun <reified T> StructuredRuntimeScope.evaluate(
    expression: String,
    vararg bindings: Pair<String, RuntimeArgument>,
    timeout: Duration = 30.seconds,
    idleTimeout: Duration = timeout,
    output: RuntimeOutputSink? = null,
): Evaluation<T> {
    val result = evaluateJson(expression, *bindings, timeout = timeout, idleTimeout = idleTimeout, output = output)
    return try {
        Evaluation(Json.decodeFromJsonElement<T>(result.value), result.execution)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw RuntimeDecodeException(result.execution, failure)
    }
}

suspend inline fun <reified T> StructuredRuntimeScope.eval(
    expression: String,
    vararg bindings: Pair<String, RuntimeArgument>,
    timeout: Duration = 30.seconds,
    idleTimeout: Duration = timeout,
    output: RuntimeOutputSink? = null,
): T = evaluate<T>(expression, *bindings, timeout = timeout, idleTimeout = idleTimeout, output = output).value

/** Identity belongs to one live interpreter. Cross-session transfer requires an explicit copy. */
class RuntimeRef internal constructor(
    private val owner: ProcessRuntime,
    internal val id: String,
) : RuntimeArgument {
    internal val ownerSessionId: String get() = owner.sessionId
    private val released = AtomicBoolean(false)
    internal val isReleased: Boolean get() = released.get() || !owner.isOpen
    internal fun markReleased() { released.set(true) }

    internal fun requireUsableBy(runtime: ProcessRuntime) {
        require(owner === runtime) { "Reference belongs to another session; use copy(reference) or readJson(reference) to transfer data" }
        check(!isReleased) { "Reference has been released or its session is closed" }
    }

    internal suspend fun readJsonInternal(timeout: Duration = 30.seconds): JsonElement {
        val result = owner.request(buildJsonObject { put("op", "get"); put("id", id) }, listOf(this), timeout)
        return checkNotNull(result.expectResponse("value")["value"]) { "Missing value in runtime response" }
    }

    internal suspend fun releaseInternal() {
        if (isReleased) {
            released.set(true)
            return
        }
        // Closing invalidates this handle, but it cannot erase a failure of
        // the release being attempted (for example, a malformed response).
        owner.releaseReference(this).expectResponse("unit")
    }

    override fun toString(): String = "RuntimeRef(runtime=${owner.name}, released=$isReleased)"
}

suspend inline fun <reified T> StructuredRuntimeScope.read(
    reference: RuntimeRef,
    timeout: Duration = 30.seconds,
): T = Json.decodeFromJsonElement(readJson(reference, timeout))

internal fun RuntimeResult.expectResponse(kind: String): JsonObject {
    if (!ok) throw RuntimeCallException(this)
    val result = checkNotNull(response) { "Missing structured response from $runtime" }
    result.validateRuntimeResponse(checkNotNull(exitCode), kind, runtime)
    return result
}
