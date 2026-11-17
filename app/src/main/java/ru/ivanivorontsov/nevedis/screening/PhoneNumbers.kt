package ru.ivanivorontsov.nevedis.screening

/**
 * Normalizes common Russian phone-number formats to the same 11-digit form.
 * International numbers outside the Russian +7/8 format are left as digits.
 */
fun normalizePhoneNumber(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length == 10 -> "7" + digits
        digits.length == 11 && digits.startsWith('8') -> "7" + digits.drop(1)
        else -> digits
    }
}
