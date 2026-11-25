package com.kotlysh.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Structured operation policy layered over the mechanism-neutral process engine. */
internal suspend fun ProcessRuntime.request(
    request: JsonObject,
    references: List<RuntimeRef> = emptyList(),
    timeout: Duration = 30.seconds,
    idleTimeout: Duration = timeout,
    output: RuntimeOutputSink? = null,
    traceScript: String? = null,
    traceInputSessionIds: List<String> = emptyList(),
): RuntimeResult = structuredRequest(
    request, references, timeout, idleTimeout, output, traceScript, traceInputSessionIds,
)

internal suspend fun ProcessRuntime.releaseReference(reference: RuntimeRef): RuntimeResult = structuredRequest(
    buildJsonObject { put("op", "release"); put("id", reference.id) },
    onSuccessfulResult = { reference.markReleased() },
)

private suspend fun ProcessRuntime.structuredRequest(
    request: JsonObject,
    references: List<RuntimeRef> = emptyList(),
    timeout: Duration = 30.seconds,
    idleTimeout: Duration = timeout,
    output: RuntimeOutputSink? = null,
    traceScript: String? = null,
    traceInputSessionIds: List<String> = emptyList(),
    onSuccessfulResult: (RuntimeResult) -> Unit = {},
): RuntimeResult {
    val runtime = this
    val operation = request["op"]?.jsonPrimitive?.contentOrNull ?: "request"
    return dispatchPrepared(timeout, idleTimeout, output) {
        val wrapper = checkNotNull(requestWrapper) { "$name does not support structured requests" }
        RuntimeDispatchPlan(
            wire = { marker -> wrapper(request, marker) },
            validateBeforeSubmission = { references.forEach { it.requireUsableBy(runtime) } },
            traceScript = traceScript,
            traceOperation = operation,
            traceInputSessionIds = (references.map { it.ownerSessionId } + traceInputSessionIds).distinct(),
            responseRequired = true,
            expectedResponseKind = when (operation) {
                "exec", "release" -> "unit"
                "eval", "get" -> "value"
                "hold", "store" -> "ref"
                else -> null
            },
            onSuccessfulResult = onSuccessfulResult,
        )
    }
}
