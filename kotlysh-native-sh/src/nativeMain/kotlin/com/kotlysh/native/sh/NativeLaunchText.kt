package com.kotlysh.nativesh

/** Rejects text whose C-string encoding would truncate or replace user data. */
internal fun requireNativeLaunchText(text: String, label: String) {
    require('\u0000' !in text) { "$label must not contain NUL" }
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character in '\uD800'..'\uDBFF') {
            require(index + 1 < text.length && text[index + 1] in '\uDC00'..'\uDFFF') {
                "$label must have well-formed UTF-16"
            }
            index += 2
        } else {
            require(character !in '\uDC00'..'\uDFFF') { "$label must have well-formed UTF-16" }
            index++
        }
    }
}
