package com.kotlysh.core

import java.util.Base64
import java.util.Collections
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Captured wire data and reference identities. Live-session admission belongs to the dispatcher. */
internal class RuntimeExpressionRequest private constructor(
    val payload: JsonObject,
    val references: List<RuntimeRef>,
) {
    companion object {
        fun prepare(
            operation: String,
            expression: String,
            bindings: Array<out Pair<String, RuntimeArgument>>,
            numberPolicy: RuntimeJsonNumberPolicy,
        ): RuntimeExpressionRequest {
            // Keep both validation passes on the same caller-independent binding sequence.
            val captured = if (bindings.isEmpty()) bindings else bindings.copyOf()
            if (captured.size > 1) {
                val names = HashSet<String>(captured.size)
                for ((name, _) in captured) {
                    require(names.add(name)) { "Duplicate binding names" }
                }
            }
            // Preserve rejection order: duplicate names, executable source, then each binding.
            val source = expression.toRuntimeSourceUtf8()
            val encoded = LinkedHashMap<String, JsonElement>(captured.size)
            var references: MutableList<RuntimeRef>? = null
            for ((name, argument) in captured) {
                require(validBindingName(name)) { "Invalid binding name: $name" }
                val member = when (argument) {
                    is JsonArgument -> Collections.singletonMap(
                        "json", validatedJsonSnapshot(argument.json, numberPolicy),
                    )
                    is RuntimeRef -> {
                        if (references == null) references = ArrayList()
                        references.add(argument)
                        Collections.singletonMap("ref", JsonPrimitive(argument.id))
                    }
                }
                encoded[name] = JsonObject(member)
            }
            val request = LinkedHashMap<String, JsonElement>(4)
            request["op"] = JsonPrimitive(operation)
            request["code"] = JsonPrimitive(Base64.getEncoder().encodeToString(source))
            request["bindings"] = JsonObject(Collections.unmodifiableMap(encoded))
            return RuntimeExpressionRequest(
                JsonObject(Collections.unmodifiableMap(request)),
                references?.immutableSnapshot() ?: emptyList(),
            )
        }

        private fun validBindingName(name: String): Boolean {
            if (name.isEmpty() || !identifierStart(name[0])) return false
            for (index in 1 until name.length) {
                val character = name[index]
                if (!identifierStart(character) && character !in '0'..'9') return false
            }
            return true
        }

        private fun identifierStart(character: Char): Boolean =
            character == '_' || character in 'a'..'z' || character in 'A'..'Z'
    }
}
