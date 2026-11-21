package ru.ivanivorontsov.nevedis.analysis

/**
 * Реализация алгоритма Snowball (Porter) для русского языка.
 * Ожидает слово в нижнем регистре с «е» вместо «ё».
 */
object RussianStemmer {
    private const val VOWELS = "аеиоуыэюя"

    private val perfectiveGerund1 = listOf("вшись", "вши", "в")
    private val perfectiveGerund2 = listOf("ившись", "ывшись", "ивши", "ывши", "ив", "ыв")
    private val adjective = listOf(
        "ими", "ыми", "его", "ого", "ему", "ому", "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой",
        "ем", "им", "ым", "ом", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею"
    )
    private val participle1 = listOf("ем", "нн", "вш", "ющ", "щ")
    private val participle2 = listOf("ивш", "ывш", "ующ")
    private val reflexive = listOf("ся", "сь")
    private val verb1 = listOf("ете", "йте", "ешь", "нно", "ла", "на", "ли", "ем", "ло", "но", "ет", "ют", "ны", "ть", "й", "л", "н")
    private val verb2 = listOf(
        "ейте", "уйте", "ила", "ыла", "ена", "ите", "или", "ыли", "ило", "ыло", "ено", "ует", "уют", "ены", "ить", "ыть", "ишь",
        "ей", "уй", "ил", "ыл", "им", "ым", "ен", "ят", "ит", "ыт", "ую", "ю"
    )
    private val noun = listOf(
        "иями", "ями", "ами", "ией", "иям", "ием", "иях", "ев", "ов", "ие", "ье", "еи", "ии", "ей", "ой", "ий", "ям", "ем",
        "ам", "ом", "ах", "ях", "ию", "ью", "ия", "ья", "а", "е", "и", "й", "о", "у", "ы", "ь", "ю", "я"
    )
    private val superlative = listOf("ейше", "ейш")
    private val derivational = listOf("ость", "ост")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun stem(word: String): String {
        if (word.length < 3 || word.any { it !in 'а'..'я' }) return word
        return cache.getOrPut(word) { doStem(word) }
    }

    private fun doStem(word: String): String {
        val rv = rvStart(word)
        if (rv >= word.length) return word
        val pre = word.substring(0, rv)
        var s = word.substring(rv)
        val r2 = r2Start(word) - rv

        // Шаг 1
        var removed = removeGroup(s, perfectiveGerund1, true) ?: removeEnding(s, perfectiveGerund2)
        if (removed != null) {
            s = removed
        } else {
            s = removeEnding(s, reflexive) ?: s
            removed = removeAdjectival(s) ?: removeGroup(s, verb1, true) ?: removeEnding(s, verb2)
                ?: removeEnding(s, noun)
            if (removed != null) s = removed
        }
        // Шаг 2
        if (s.endsWith("и")) s = s.dropLast(1)
        // Шаг 3
        for (d in derivational) {
            if (s.endsWith(d) && s.length - d.length >= r2) { s = s.dropLast(d.length); break }
        }
        // Шаг 4
        when {
            s.endsWith("нн") -> s = s.dropLast(1)
            else -> {
                val sup = removeEnding(s, superlative)
                if (sup != null) {
                    s = sup
                    if (s.endsWith("нн")) s = s.dropLast(1)
                } else if (s.endsWith("ь")) {
                    s = s.dropLast(1)
                }
            }
        }
        return pre + s
    }

    private fun removeEnding(s: String, endings: List<String>): String? {
        val e = endings.filter { s.endsWith(it) }.maxByOrNull { it.length } ?: return null
        return s.dropLast(e.length)
    }

    /** Окончания группы 1 должны идти после «а» или «я». */
    private fun removeGroup(s: String, endings: List<String>, afterAYa: Boolean): String? {
        val e = endings.filter { s.endsWith(it) && (!afterAYa || s.length > it.length && s[s.length - it.length - 1] in "ая") }
            .maxByOrNull { it.length } ?: return null
        return s.dropLast(e.length)
    }

    private fun removeAdjectival(s: String): String? {
        val withoutAdj = removeEnding(s, adjective) ?: return null
        return removeGroup(withoutAdj, participle1, true) ?: removeEnding(withoutAdj, participle2) ?: withoutAdj
    }

    private fun rvStart(w: String): Int {
        val i = w.indexOfFirst { it in VOWELS }
        return if (i < 0) w.length else i + 1
    }

    private fun r1Start(w: String, from: Int = 0): Int {
        var i = from
        while (i < w.length - 1) {
            if (w[i] in VOWELS && w[i + 1] !in VOWELS) return i + 2
            i++
        }
        return w.length
    }

    private fun r2Start(w: String): Int = r1Start(w, r1Start(w))
}
