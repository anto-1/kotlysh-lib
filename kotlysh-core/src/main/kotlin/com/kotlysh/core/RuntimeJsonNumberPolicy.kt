package com.kotlysh.core

/** Destination number semantics, independent of a runtime's name or command. */
enum class RuntimeJsonNumberPolicy {
    /** Standard JSON syntax only; the adapter owns destination-specific range checks. */
    Json,
    /** Exact integer spellings; decimal/exponent spellings must convert to finite doubles. */
    Python,
    /** Finite doubles; values rounded to integers must stay in JavaScript's exact range. */
    JavaScript;

    internal fun validate(literal: String) {
        val floating = this == JavaScript || this == Python && literal.any { it == '.' || it == 'e' || it == 'E' }
        if (!floating) return
        // Match native conversion, including finite zero with very wide exponents.
        // BigDecimal's bounded scale would reject those spellings too early.
        val parsed = literal.toDoubleOrNull()
        require(parsed != null && parsed.isFinite()) {
            if (this == JavaScript) "Number is outside JavaScript's finite range"
            else "Number is outside Python's finite float range"
        }
        if (this == JavaScript) {
            require(parsed % 1.0 != 0.0 || parsed in -9_007_199_254_740_991.0..9_007_199_254_740_991.0) {
                "Integer is outside JavaScript's exact range; transfer it as a string"
            }
        }
    }
}
