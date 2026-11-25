package com.kotlysh.core

import kotlinx.serialization.json.*

/** Checks keys before tree parsing can discard duplicates. JSON grammar is validated by the parser. */
internal fun checkRuntimeJsonObjectKeys(json: String, runtime: String) {
    val containers = ArrayDeque<ResponseObjectKeys?>()
    var cursor = 0
    while (cursor < json.length) {
        when (json[cursor]) {
            '{' -> containers.addLast(ResponseObjectKeys())
            '[' -> containers.addLast(null)
            '}', ']' -> if (containers.isNotEmpty()) containers.removeLast()
            ',' -> containers.lastOrNull()?.expectName = true
            '"' -> {
                val start = cursor++
                while (cursor < json.length && json[cursor] != '"') {
                    if (json[cursor] == '\\') cursor++
                    cursor++
                }
                check(cursor < json.length) { "Unterminated JSON string from $runtime" }
                containers.lastOrNull()?.takeIf { it.expectName }?.let { objectKeys ->
                    val name = Json.decodeFromString<String>(json.substring(start, cursor + 1))
                    check(objectKeys.names.add(name)) { "Duplicate JSON object key from $runtime" }
                    objectKeys.expectName = false
                }
            }
        }
        cursor++
    }
}

private class ResponseObjectKeys {
    val names = HashSet<String>()
    var expectName = true
}

internal fun JsonObject.validateRuntimeResponse(exitCode: Int, expectedKind: String?, runtime: String) {
    val version = this["version"] as? JsonPrimitive
    check(version != null && !version.isString && version.intOrNull == 1) {
        "Unsupported or malformed response version from $runtime"
    }
    val kind = (this["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    check(kind in setOf("unit", "value", "ref", "error")) { "Unknown or malformed response kind from $runtime" }
    when (kind) {
        "value" -> check(containsKey("value")) { "Missing value in response from $runtime" }
        "ref" -> check((this["id"] as? JsonPrimitive)?.isString == true) { "Malformed reference ID from $runtime" }
        "error" -> {
            check((this["type"] as? JsonPrimitive)?.isString == true) { "Malformed error type from $runtime" }
            check((this["message"] as? JsonPrimitive)?.isString == true) { "Malformed error message from $runtime" }
        }
    }
    check((exitCode == 0) == (kind != "error")) { "Response kind conflicts with completion status from $runtime" }
    if (exitCode == 0 && expectedKind != null) {
        check(kind == expectedKind) { "Expected $expectedKind response from $runtime" }
    }
}

private val jsonNumberSyntax = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** The JSON tree parser also accepts bare tokens; validate every unquoted literal. */
internal fun JsonElement.validateRuntimeJsonLiterals(runtime: String) {
    val cursors = ArrayDeque<Iterator<JsonElement>>()
    cursors.addLast(listOf(this).iterator())
    while (cursors.isNotEmpty()) {
        val cursor = cursors.last()
        if (!cursor.hasNext()) {
            cursors.removeLast()
            continue
        }
        when (val element = cursor.next()) {
            is JsonObject -> cursors.addLast(element.values.iterator())
            is JsonArray -> cursors.addLast(element.iterator())
            is JsonPrimitive -> check(
                element.isString || element == JsonNull ||
                    element.content == "true" || element.content == "false" ||
                    jsonNumberSyntax.matches(element.content),
            ) { "Malformed JSON literal from $runtime" }
        }
    }
}

/** Validate the owned snapshot that will be serialized after dispatch admission. */
internal fun validatedJsonSnapshot(
    element: JsonElement,
    policy: RuntimeJsonNumberPolicy,
): JsonElement = element.immutableJsonSnapshot { validateJsonPrimitive(it, policy) }

private fun validateJsonPrimitive(element: JsonPrimitive, policy: RuntimeJsonNumberPolicy) {
    if (!element.isString && element != JsonNull && element.booleanOrNull == null) {
        require(jsonNumberSyntax.matches(element.content)) { "JSON numbers must use standard JSON syntax" }
        policy.validate(element.content)
    }
}
