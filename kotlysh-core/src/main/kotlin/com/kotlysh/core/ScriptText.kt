package com.kotlysh.core

import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Gives multiline DSL literals file-like semantics without requiring a
 * visible `.trimIndent()` transport operation at every call site.
 *
 * Single-line commands remain byte-for-byte unchanged. Multiline commands use
 * Kotlin raw-string indentation rules: a blank boundary line is discarded,
 * common indentation is removed, and line endings become LF.
 */
internal fun String.normalizeKotlyshScript(): String =
    if ('\n' in this || '\r' in this) trimIndent() else this

/** Reject executable source that UTF-8 would silently replace with different text. */
internal fun String.requireUtf8Source() {
    val index = firstUnpairedSurrogateIndex()
    require(index < 0) {
        "Executable runtime source contains an unpaired UTF-16 surrogate at index $index; " +
            "use a literal encoder or structured binding for data"
    }
}

internal fun String.firstUnpairedSurrogateIndex(): Int {
    for (index in indices) {
        val character = this[index]
        val paired = when {
            character.isHighSurrogate() -> index + 1 < length && this[index + 1].isLowSurrogate()
            character.isLowSurrogate() -> index > 0 && this[index - 1].isHighSurrogate()
            else -> true
        }
        if (!paired) return index
    }
    return -1
}

internal fun requireNativeText(text: String, description: String) {
    val index = text.firstUnpairedSurrogateIndex()
    require(index < 0) { "$description contains an unpaired UTF-16 surrogate at index $index" }
    require('\u0000' !in text) { "$description contains NUL" }
    // Unix NIO uses the same captured JNU charset as ProcessBuilder, but
    // reports unmappable text instead of silently replacing it. No I/O occurs.
    if (File.separatorChar == '/') {
        try {
            Path.of(text)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("$description cannot be represented by native process encoding")
        }
    }
}

internal fun String.toRuntimeSourceUtf8(): ByteArray {
    requireUtf8Source()
    return toByteArray(Charsets.UTF_8)
}
