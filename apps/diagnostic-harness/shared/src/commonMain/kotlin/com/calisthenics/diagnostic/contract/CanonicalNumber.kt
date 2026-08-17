package com.calisthenics.diagnostic.contract

/**
 * The finite IEEE-754 number spelling used by the contract's canonical writer.
 *
 * Kotlin's platform `Double.toString()` supplies the shortest round-tripping
 * decimal digits. This class only normalizes its punctuation and switches at
 * the ECMAScript fixed/scientific boundaries required by RFC 8785.
 */
object CanonicalNumber {
    fun format(value: Double): String {
        require(value.isFinite()) { "JSON numbers must be finite" }
        if (value == 0.0) return "0"

        val negative = value < 0.0
        val unsigned = if (negative) -value else value
        val raw = unsigned.toString()
        val exponentMarker = raw.indexOfAny(charArrayOf('e', 'E'))
        val mantissa = if (exponentMarker >= 0) raw.substring(0, exponentMarker) else raw
        val exponent = if (exponentMarker >= 0) raw.substring(exponentMarker + 1).toInt() else 0
        val decimalPoint = mantissa.indexOf('.')
        val digitsBeforeDecimal = if (decimalPoint >= 0) decimalPoint else mantissa.length
        var digits = if (decimalPoint >= 0) {
            mantissa.removeRange(decimalPoint, decimalPoint + 1)
        } else {
            mantissa
        }
        var decimalPosition = digitsBeforeDecimal + exponent
        while (digits.length > 1 && digits.last() == '0') {
            digits = digits.dropLast(1)
        }

        val body = if (unsigned >= 1e-6 && unsigned < 1e21) {
            fixed(digits, decimalPosition)
        } else {
            val first = digits.first()
            val rest = digits.drop(1)
            val scientificExponent = decimalPosition - 1
            buildString {
                append(first)
                if (rest.isNotEmpty()) {
                    append('.')
                    append(rest)
                }
                append('e')
                if (scientificExponent >= 0) append('+')
                append(scientificExponent)
            }
        }
        return if (negative) "-$body" else body
    }

    private fun fixed(digits: String, decimalPosition: Int): String = buildString {
        when {
            decimalPosition <= 0 -> {
                append("0.")
                repeat(-decimalPosition) { append('0') }
                append(digits)
            }

            decimalPosition >= digits.length -> {
                append(digits)
                repeat(decimalPosition - digits.length) { append('0') }
            }

            else -> {
                append(digits, 0, decimalPosition)
                append('.')
                append(digits, decimalPosition, digits.length)
            }
        }
    }
}
