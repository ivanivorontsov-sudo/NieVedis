package ru.ivanivorontsov.nevedis.analysis

/**
 * База подозрительных фраз. Формат файла `phrases_ru.txt` (UTF-8):
 *
 * ```
 * @категория | Название | Почему опасно | признак1,признак2
 * категория | вес | шаблон
 * ```
 *
 * Шаблон — слова через пробел. Каждое слово сравнивается по основе (стемминг Snowball), допускается
 * опечатка в одну букву для длинных слов. `слово*` — совпадение по началу слова, `а/б/в` — варианты,
 * `?слово` — необязательное слово. Между словами шаблона в тексте может стоять до двух лишних слов.
 * Шаблон с префиксом `re:` — регулярное выражение по нормализованному тексту.
 */
class PhraseDatabase private constructor(
    val categories: Map<String, Category>,
    val entries: List<Entry>
) {
    data class Category(val id: String, val title: String, val reason: String, val tags: Set<String>)

    /** Одно слово шаблона: совпадает, если слово текста имеет одну из основ или начинается с одного из префиксов. */
    data class Token(val stems: List<String>, val prefixes: List<String>, val optional: Boolean)

    data class Entry(
        val category: String,
        val weight: Int,
        val source: String,
        val tokens: List<Token>,
        val regex: Regex?
    )

    val phraseCount: Int get() = entries.size

    companion object {
        private const val RESOURCE = "/nevedis/phrases_ru.txt"

        val default: PhraseDatabase by lazy {
            val stream = PhraseDatabase::class.java.getResourceAsStream(RESOURCE)
                ?: error("Phrase database $RESOURCE is missing")
            parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

        fun parse(text: String): PhraseDatabase {
            val categories = LinkedHashMap<String, Category>()
            val entries = ArrayList<Entry>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                if (line.startsWith("@")) {
                    val parts = line.drop(1).split('|').map { it.trim() }
                    require(parts.size >= 3) { "Line ${index + 1}: category needs id | title | reason" }
                    val tags = parts.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
                    categories[parts[0]] = Category(parts[0], parts[1], parts[2], tags)
                    return@forEachIndexed
                }
                val parts = line.split('|', limit = 3).map { it.trim() }
                require(parts.size == 3) { "Line ${index + 1}: expected category | weight | pattern" }
                val category = parts[0]
                require(category in categories) { "Line ${index + 1}: unknown category $category" }
                val weight = parts[1].toIntOrNull() ?: error("Line ${index + 1}: bad weight")
                require(weight in 1..100) { "Line ${index + 1}: weight must be 1..100" }
                val pattern = parts[2]
                entries += if (pattern.startsWith("re:")) {
                    Entry(category, weight, pattern, emptyList(), Regex(pattern.removePrefix("re:").trim()))
                } else {
                    val tokens = parsePattern(pattern)
                    require(tokens.any { !it.optional }) { "Line ${index + 1}: empty pattern" }
                    Entry(category, weight, pattern, tokens, null)
                }
            }
            return PhraseDatabase(categories, entries)
        }

        private fun parsePattern(pattern: String): List<Token> =
            pattern.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { raw ->
                val optional = raw.startsWith("?")
                val body = raw.removePrefix("?")
                val alternatives = body.split('/').filter { it.isNotEmpty() }
                val prefixes = alternatives.filter { it.endsWith("*") }.map { TextNormalizer.normalize(it.removeSuffix("*")) }
                val stems = alternatives.filterNot { it.endsWith("*") }.map { RussianStemmer.stem(TextNormalizer.normalize(it)) }
                Token(stems, prefixes, optional)
            }
    }
}
