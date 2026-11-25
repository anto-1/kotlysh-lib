package com.kotlysh.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.IdentityHashMap

/** Write containers iteratively so caller data depth cannot exhaust the JVM stack. */
internal fun JsonElement.toJsonWireString(): String = buildString {
    val active: MutableSet<JsonElement> = Collections.newSetFromMap(IdentityHashMap())
    val frames = ArrayDeque<JsonWriteFrame>()
    fun appendValue(value: JsonElement) {
        when (value) {
            is JsonObject, is JsonArray -> {
                require(active.add(value)) { "JSON cannot represent cyclic objects" }
                append(if (value is JsonObject) '{' else '[')
                frames.addLast(JsonWriteFrame(value))
            }
            is JsonPrimitive -> append(value.toString())
        }
    }
    appendValue(this@toJsonWireString)
    while (frames.isNotEmpty()) {
        val frame = frames.last()
        if (!frame.hasNext()) {
            append(if (frame.source is JsonObject) '}' else ']')
            frames.removeLast()
            active.remove(frame.source)
        } else {
            if (!frame.first) append(',')
            frame.first = false
            if (frame.members != null) {
                val member = frame.members.next()
                append(JsonPrimitive(member.key).toString())
                append(':')
                appendValue(member.value)
            } else {
                appendValue(checkNotNull(frame.items).next())
            }
        }
    }
}.escapeUnpairedSurrogates()

private class JsonWriteFrame(val source: JsonElement) {
    val members = (source as? JsonObject)?.entries?.iterator()
    val items = (source as? JsonArray)?.iterator()
    var first = true
    fun hasNext(): Boolean = members?.hasNext() ?: checkNotNull(items).hasNext()
}

/** Own every container and its JVM collection views without recursively walking caller data. */
internal fun JsonElement.immutableJsonSnapshot(
    validatePrimitive: (JsonPrimitive) -> Unit = {},
): JsonElement {
    val active: MutableSet<JsonElement> = Collections.newSetFromMap(IdentityHashMap())
    val frames = ArrayDeque<JsonSnapshotFrame>()
    var current = this
    while (true) {
        var snapshot: JsonElement
        if (current is JsonObject || current is JsonArray) {
            require(active.add(current)) { "JSON cannot represent cyclic objects" }
            val frame = JsonSnapshotFrame(current)
            if (frame.hasNext()) {
                frames.addLast(frame)
                current = frame.next()
                continue
            }
            snapshot = frame.finish()
            active.remove(current)
        } else {
            validatePrimitive(current as JsonPrimitive)
            snapshot = current
        }
        while (true) {
            val frame = frames.lastOrNull() ?: return snapshot
            frame.append(snapshot)
            if (frame.hasNext()) {
                current = frame.next()
                break
            }
            snapshot = frame.finish()
            frames.removeLast()
            active.remove(frame.source)
        }
    }
}

/** Each frame owns one detached container; completed aliases leave the active path. */
private class JsonSnapshotFrame(val source: JsonElement) {
    private val members = (source as? JsonObject)?.entries?.iterator()
    private val items = (source as? JsonArray)?.iterator()
    private val objectCopy = linkedMapOf<String, JsonElement>()
    private val arrayCopy = mutableListOf<JsonElement>()
    private var memberName: String? = null

    fun hasNext(): Boolean = members?.hasNext() ?: checkNotNull(items).hasNext()

    fun next(): JsonElement = if (members != null) {
        val member = members.next()
        memberName = member.key
        member.value
    } else {
        checkNotNull(items).next()
    }

    fun append(value: JsonElement) {
        if (members != null) objectCopy[checkNotNull(memberName)] = value else arrayCopy.add(value)
    }

    fun finish(): JsonElement = if (members != null) {
        JsonObject(Collections.unmodifiableMap(objectCopy))
    } else {
        JsonArray(arrayCopy.immutableSnapshot())
    }
}

/** Keep JSON strings exact when a UTF-8 writer cannot represent a lone UTF-16 code unit. */
internal fun String.escapeUnpairedSurrogates(): String = buildString(length) {
    for (index in this@escapeUnpairedSurrogates.indices) {
        val character = this@escapeUnpairedSurrogates[index]
        val unpaired = when {
            character.isHighSurrogate() -> index + 1 == this@escapeUnpairedSurrogates.length ||
                !this@escapeUnpairedSurrogates[index + 1].isLowSurrogate()
            character.isLowSurrogate() -> index == 0 || !this@escapeUnpairedSurrogates[index - 1].isHighSurrogate()
            else -> false
        }
        if (unpaired) {
            append("\\u")
            append(character.code.toString(16).padStart(4, '0'))
        } else {
            append(character)
        }
    }
}
