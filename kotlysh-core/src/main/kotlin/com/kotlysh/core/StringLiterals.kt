package com.kotlysh.core

/**
 * One complete literal Bash word, including its quoting. Insert the result into
 * command source outside existing quotes; it is not escaped string contents.
 * NUL and unpaired UTF-16 surrogates cannot cross its UTF-8 transport.
 */
fun shLiteral(value: String): String {
    require('\u0000' !in value) { "A shell argument cannot contain NUL" }
    require(value.indices.none { isUnpairedSurrogate(value, it) }) {
        "A shell argument cannot contain an unpaired UTF-16 surrogate"
    }
    return buildString {
        append('\'')
        for (character in value) {
            when (character) {
                '\'' -> append("'\"'\"'")
                // Keep data line endings outside script indentation normalization.
                '\r' -> append("'$'\\r''")
                '\n' -> append("'$'\\n''")
                else -> append(character)
            }
        }
        append('\'')
    }
}

/** A complete Python string expression. Insert outside existing string quotes. */
fun pythonLiteral(value: String): String = quotedStringLiteral(value)

/** A complete JavaScript string expression. Insert outside existing string quotes. */
fun jsLiteral(value: String): String = quotedStringLiteral(value)

private fun quotedStringLiteral(value: String): String = buildString {
    append('"')
    for (index in value.indices) {
        val character = value[index]
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (character < ' ' || character == '\u2028' || character == '\u2029' || isUnpairedSurrogate(value, index)) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun isUnpairedSurrogate(value: String, index: Int): Boolean = when {
    value[index].isHighSurrogate() -> index + 1 == value.length || !value[index + 1].isLowSurrogate()
    value[index].isLowSurrogate() -> index == 0 || !value[index - 1].isHighSurrogate()
    else -> false
}
