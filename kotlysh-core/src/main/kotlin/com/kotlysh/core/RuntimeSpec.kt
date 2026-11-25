package com.kotlysh.core

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RuntimeSpec(
    val name: String,
    val command: List<String>,
    val wrap: (script: String, marker: String) -> String,
    val requestWrapper: ((request: JsonObject, marker: String) -> String)?,
    val jsonNumberPolicy: RuntimeJsonNumberPolicy = RuntimeJsonNumberPolicy.Json,
) {
    constructor(name: String, command: List<String>, wrap: (String, String) -> String,
                requestWrapper: ((JsonObject, String) -> String)?) :
        this(name, command, wrap, requestWrapper, RuntimeJsonNumberPolicy.Json)

    constructor(name: String, command: List<String>, wrap: (String, String) -> String) :
        this(name, command, wrap, null)

    init {
        require(name.isNotBlank()) { "Runtime name must not be blank" }
        require(command.isNotEmpty()) { "Runtime command must not be empty" }
    }

    /** Keep the original copy overload callable from Java source and existing bytecode. */
    fun copy(name: String, command: List<String>, wrap: (String, String) -> String,
             requestWrapper: ((JsonObject, String) -> String)?): RuntimeSpec =
        copy(name, command, wrap, requestWrapper, jsonNumberPolicy)

    companion object {
        @Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
        @JvmStatic
        @JvmName("copy\$default")
        @Suppress("UNUSED_PARAMETER")
        fun legacyCopyDefault(
            original: RuntimeSpec, name: String?, command: List<String>?,
            wrap: ((String, String) -> String)?, requestWrapper: ((JsonObject, String) -> String)?,
            mask: Int, marker: Any?,
        ): RuntimeSpec = original.copy(
            if (mask and 1 != 0) original.name else checkNotNull(name),
            if (mask and 2 != 0) original.command else checkNotNull(command),
            if (mask and 4 != 0) original.wrap else checkNotNull(wrap),
            if (mask and 8 != 0) original.requestWrapper else requestWrapper,
        )
    }
}

object RuntimeSpecs {
    val Bash = RuntimeSpec(
        name = "sh",
        // The session reads its scriptlets from descriptor 3 so that commands
        // inherit an empty stdin. A command reading stdin (`cat`, `read`, `ssh`)
        // would otherwise consume the following completion frame as its input.
        // `exec` keeps the launched process identity for ownership and cleanup.
        command = listOf(
            "/bin/bash", "--noprofile", "--norc", "-c",
            "exec /bin/bash --noprofile --norc /dev/fd/3 3<&0 </dev/null",
        ).immutableSnapshot(),
        wrap = { script, marker ->
            // The runtime supplies a unique ASCII marker for each dispatch.
            // Avoid writing a fixed user-visible status variable between fragments.
            val status = "__kotlysh_status_" + marker.filter {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_'
            }
            buildString {
                // Evaluate the scriptlet as one quoted word. Trailing blanks,
                // escapes and Unicode whitespace remain its own source, and a
                // syntax error or unfinished quote, here-document or compound
                // command fails this dispatch instead of consuming the
                // completion bookkeeping that follows.
                append("builtin eval '")
                for (character in script) {
                    if (character == '\'') append("'\\''") else append(character)
                }
                appendLine("'")
                appendLine("$status=$?")
                appendLine("builtin printf '%s:%s\\n' '$marker' \"\$$status\"")
                appendLine("builtin printf '%s:%s\\n' '$marker' \"\$$status\" >&2")
                appendLine("builtin unset $status")
            }
        },
    )

    val Python = RuntimeSpec(
        name = "py",
        command = listOf("python3", "-u", "-c", driverSource("python_driver.py")).immutableSnapshot(),
        wrap = ::driverRequest,
        requestWrapper = ::structuredRequest,
        jsonNumberPolicy = RuntimeJsonNumberPolicy.Python,
    )

    val Node = RuntimeSpec(
        name = "node",
        command = listOf("node", "-e", driverSource("node_driver.js")).immutableSnapshot(),
        wrap = ::driverRequest,
        requestWrapper = ::structuredRequest,
        jsonNumberPolicy = RuntimeJsonNumberPolicy.JavaScript,
    )

    val Ruby = RuntimeSpec(
        name = "ruby",
        command = listOf("ruby", "-e", driverSource("ruby_driver.rb")).immutableSnapshot(),
        wrap = ::driverRequest,
    )

    private fun driverRequest(script: String, marker: String): String =
        structuredRequest(buildJsonObject {
            put("op", "exec")
            put("code", base64(script))
        }, marker)

    private fun structuredRequest(request: JsonObject, marker: String): String =
        JsonObject(request + mapOf("version" to JsonPrimitive(1), "marker" to JsonPrimitive(marker)))
            .toJsonWireString() + "\n"

    private fun base64(value: String): String {
        return Base64.getEncoder().encodeToString(value.toRuntimeSourceUtf8())
    }

    private fun driverSource(name: String): String =
        checkNotNull(RuntimeSpecs::class.java.getResource("/com/kotlysh/core/$name")) {
            "Missing packaged runtime driver: $name"
        }.readText()
}
