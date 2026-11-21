package ru.ivanivorontsov.nevedis.screening

/**
 * Normalizes common Russian phone-number formats to the same 11-digit form.
 * International numbers outside the Russian +7/8 format are left as digits.
 */
fun normalizePhoneNumber(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length == 10 && !value.trim().startsWith("+") -> "7" + digits
        digits.length == 11 && digits.startsWith('8') && !value.trim().startsWith("+") -> "7" + digits.drop(1)
        else -> digits
    }
}

/** Классификация номера по формату — без сети, только по правилам нумерации. */
object NumberFormat {
    enum class Kind { EMPTY, SHORT_SERVICE, RU_MOBILE, RU_GEOGRAPHIC, RU_TOLL_FREE, RU_PREMIUM, RU_INVALID, KAZAKHSTAN, INTERNATIONAL, SATELLITE_OR_GLOBAL, MALFORMED }

    /** Глобальные/спутниковые коды и премиальные диапазоны, которыми пользуются для дорогих и анонимных звонков. */
    private val globalPrefixes = listOf("870", "871", "872", "873", "874", "878", "881", "882", "883", "888", "979", "991")

    fun classify(raw: String): Kind {
        val trimmed = raw.trim()
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isEmpty()) return Kind.EMPTY
        val n = normalizePhoneNumber(trimmed)
        if (n.length in 3..6 && !trimmed.startsWith("+")) return Kind.SHORT_SERVICE
        if (n.length == 11 && n.startsWith("7")) {
            val code = n[1]
            return when {
                n.startsWith("7800") -> Kind.RU_TOLL_FREE
                n.startsWith("7809") || n.startsWith("7803") -> Kind.RU_PREMIUM
                code == '9' -> Kind.RU_MOBILE
                code == '3' || code == '4' || code == '8' -> Kind.RU_GEOGRAPHIC
                code == '6' || code == '7' -> Kind.KAZAKHSTAN
                else -> Kind.RU_INVALID // +7 0xx, 1xx, 2xx, 5xx в России не выдаются: вероятна подмена
            }
        }
        if (n.startsWith("7") && n.length != 11) return Kind.MALFORMED
        if (globalPrefixes.any { n.startsWith(it) }) return Kind.SATELLITE_OR_GLOBAL
        if (n.length < 7) return Kind.MALFORMED
        return Kind.INTERNATIONAL
    }

    /** Подозрительно «красивые» или технические номера: все цифры одинаковые, слишком много нулей. */
    fun looksSynthetic(normalized: String): Boolean {
        if (normalized.length < 7) return false
        val tail = normalized.takeLast(7)
        return tail.toSet().size == 1 || tail == "1234567" || tail.count { it == '0' } >= 6
    }
}
