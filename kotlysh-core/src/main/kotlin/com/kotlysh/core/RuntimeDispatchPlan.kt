package com.kotlysh.core

/**
 * Mechanism-neutral work admitted by [ProcessRuntime]. Operation policy stays
 * with the caller that prepares the dispatch; the process engine only enforces
 * lifecycle and ordering.
 */
internal class RuntimeDispatchPlan(
    val wire: (marker: String) -> String,
    val validateBeforeSubmission: () -> Unit = {},
    val traceScript: String? = null,
    val traceOperation: String = "exec",
    val traceInputSessionIds: List<String> = emptyList(),
    val responseRequired: Boolean = false,
    val expectedResponseKind: String? = null,
    val onSuccessfulResult: (RuntimeResult) -> Unit = {},
)
