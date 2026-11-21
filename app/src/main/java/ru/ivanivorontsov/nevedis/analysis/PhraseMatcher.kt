package ru.ivanivorontsov.nevedis.analysis

/**
 * Сопоставляет текст с базой фраз. Учитывает словоформы (основы Snowball), опечатки в одну букву,
 * вставные слова между словами шаблона, защитные формулировки («не сообщайте код») и сочетания
 * признаков (например «банк» + «код», «родственник» + «деньги»).
 */
class PhraseMatcher(private val db: PhraseDatabase = PhraseDatabase.default) {

    data class CategoryHit(
        val category: PhraseDatabase.Category,
        val score: Int,
        val fragments: List<String>
    )

    data class Combo(val title: String, val bonus: Int)

    data class Result(val score: Int, val hits: List<CategoryHit>, val combos: List<Combo>) {
        val categoryIds: Set<String> get() = hits.map { it.category.id }.toSet()
    }

    private data class RawHit(val entry: PhraseDatabase.Entry, val start: Int, val end: Int, val fragment: String)

    fun match(text: String): Result {
        val words = TextNormalizer.tokens(text)
        if (words.isEmpty()) return Result(0, emptyList(), emptyList())
        val stems = words.map(RussianStemmer::stem)
        val joined = words.joinToString(" ")

        val raw = ArrayList<RawHit>()
        for (entry in db.entries) {
            if (entry.regex != null) {
                val m = entry.regex.find(joined) ?: continue
                val start = joined.substring(0, m.range.first).count { it == ' ' }
                val end = start + m.value.count { it == ' ' }
                raw += RawHit(entry, start, end, m.value)
            } else {
                val span = findSpan(entry.tokens, words, stems) ?: continue
                raw += RawHit(entry, span.first, span.last, words.subList(span.first, span.last + 1).joinToString(" "))
            }
        }

        // Защитные формулировки из базы («код … даже сотруднику банка») гасят пересекающиеся слабые совпадения.
        val protective = raw.filter { it.entry.category == PROTECTIVE }
        val kept = raw.filter { it.entry.category != PROTECTIVE }
            .filterNot { isProtected(it, words, stems) }
            .filterNot { h -> h.entry.weight < 50 && protective.any { p -> p.start <= h.end && h.start <= p.end } }
        val byCategory = kept.groupBy { it.entry.category }
        val hits = byCategory.map { (catId, list) ->
            val distinct = list.distinctBy { it.entry.source }
            val maxWeight = distinct.maxOf { it.entry.weight }
            val score = (maxWeight + minOf(15, 5 * (distinct.size - 1))).coerceAtMost(100)
            CategoryHit(db.categories.getValue(catId), score, distinct.sortedByDescending { it.entry.weight }.map { it.fragment }.distinct().take(3))
        }.sortedByDescending { it.score }

        val tags = hits.flatMap { it.category.tags }.toSet()
        val combos = COMBOS.filter { c -> c.a.any { it in tags } && c.b.any { it in tags } }
            .map { Combo(it.title, it.bonus) }
        val score = (hits.sumOf { it.score } + combos.sumOf { it.bonus }).coerceAtMost(100)
        return Result(score, hits, combos)
    }

    private fun tokenMatches(t: PhraseDatabase.Token, word: String, stem: String): Boolean {
        for (p in t.prefixes) if (word.startsWith(p)) return true
        for (s in t.stems) {
            if (stem == s || word == s) return true
            if (s.length >= 5 && stem.length >= 5 && withinOneEdit(stem, s)) return true
            // Разные формы одного глагола: «оформ» / «оформля» / «оформлен».
            val (short, long) = if (stem.length <= s.length) stem to s else s to stem
            if (short.length >= 4 && long.length - short.length <= 2 && long.startsWith(short)) return true
        }
        return false
    }

    /** Находит первое вхождение шаблона: слова по порядку, между ними не больше [MAX_GAP] лишних слов. */
    private fun findSpan(tokens: List<PhraseDatabase.Token>, words: List<String>, stems: List<String>): IntRange? {
        val first = tokens.indexOfFirst { !it.optional }
        for (start in words.indices) {
            if (!tokenMatches(tokens[first], words[start], stems[start])) continue
            // Необязательные слова перед первым обязательным не влияют на совпадение.
            var pos = start
            var ok = true
            for (k in first + 1 until tokens.size) {
                val t = tokens[k]
                var found = -1
                val limit = minOf(words.size - 1, pos + 1 + MAX_GAP)
                for (j in pos + 1..limit) {
                    if (tokenMatches(t, words[j], stems[j])) { found = j; break }
                }
                if (found >= 0) pos = found
                else if (!t.optional) { ok = false; break }
            }
            if (ok) return start..pos
        }
        return null
    }

    /**
     * Защитный контекст: «не сообщайте код», «никогда не переводите на безопасный счёт».
     * Перед совпадением проверяется окно в 4 слова. После совпадения — только для слабых фраз,
     * иначе мошенник мог бы «спрятать» просьбу за словами «никому не сообщайте».
     */
    private fun isProtected(hit: RawHit, words: List<String>, stems: List<String>): Boolean {
        val firstEntryWord = hit.entry.tokens.firstOrNull()
        if (firstEntryWord != null && firstEntryWord.stems.any { it == "не" }) return false
        if (negatedProtective(words, stems, maxOf(0, hit.start - 4), hit.start - 1)) return true
        if (hit.entry.weight < 40 && negatedProtective(words, stems, hit.end + 1, minOf(words.size - 1, hit.end + 4))) return true
        return false
    }

    private fun negatedProtective(words: List<String>, stems: List<String>, from: Int, to: Int): Boolean {
        if (from > to) return false
        for (i in from..to) {
            if ((words[i] == "не" || words[i] == "никогда") && i + 1 < words.size) {
                val next = stems.getOrNull(i + 1) ?: continue
                val next2 = stems.getOrNull(i + 2)
                if (next in PROTECTIVE_VERBS || (words[i + 1] == "не" && next2 in PROTECTIVE_VERBS) ||
                    (words[i + 1] in setOf("никому", "кому") && next2 in PROTECTIVE_VERBS)) return true
            }
            if (words[i] == "никому" && i + 2 < words.size && words[i + 1] == "не" && stems[i + 2] in PROTECTIVE_VERBS) return true
        }
        return false
    }

    companion object {
        const val MAX_GAP = 2
        const val PROTECTIVE = "protective"

        private val PROTECTIVE_VERBS: Set<String> = listOf(
            "сообщайте", "сообщать", "называйте", "называть", "диктуйте", "диктовать", "передавайте", "передавать",
            "переводите", "переводить", "отправляйте", "отправлять", "перечисляйте", "перечислять",
            "устанавливайте", "устанавливать", "скачивайте", "переходите", "открывайте", "верьте", "поддавайтесь",
            "вводите", "вводить", "попадайтесь"
        ).map(RussianStemmer::stem).toSet()

        /** (теги A, теги B, название сочетания, бонус) */
        private val COMBOS = listOf(
            Combo4(setOf("authority", "bank"), setOf("money"), "Представитель «банка/ведомства» говорит о переводе денег", 15),
            Combo4(setOf("authority", "bank"), setOf("code"), "«Банк/ведомство» просит код или данные", 15),
            Combo4(setOf("remote"), setOf("bank", "money", "authority"), "Просят удалённый доступ под предлогом денег или банка", 20),
            Combo4(setOf("relative"), setOf("money"), "Близкий «в беде» и нужны деньги", 15),
            Combo4(setOf("urgency", "secrecy"), setOf("money", "code"), "Давление срочностью или тайной вокруг денег/кода", 10),
            Combo4(setOf("reward"), setOf("fee", "money", "code"), "Обещают выплату, но сначала просят оплату или код", 15),
            Combo4(setOf("gov"), setOf("code"), "«Госорган» просит код", 15),
            Combo4(setOf("invest"), setOf("money"), "Инвестиции с переводом денег «брокеру»", 10)
        )

        private data class Combo4(val a: Set<String>, val b: Set<String>, val title: String, val bonus: Int)

        fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            val la = a.length; val lb = b.length
            if (kotlin.math.abs(la - lb) > 1) return false
            var i = 0; var j = 0; var edits = 0
            while (i < la && j < lb) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    la > lb -> i++
                    lb > la -> j++
                    else -> {
                        // замена или перестановка соседних букв
                        if (i + 1 < la && j + 1 < lb && a[i] == b[j + 1] && a[i + 1] == b[j]) { i += 2; j += 2 } else { i++; j++ }
                    }
                }
            }
            edits += (la - i) + (lb - j)
            return edits <= 1
        }
    }
}
