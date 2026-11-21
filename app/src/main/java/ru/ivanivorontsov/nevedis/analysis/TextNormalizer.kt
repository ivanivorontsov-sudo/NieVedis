package ru.ivanivorontsov.nevedis.analysis

import java.util.Locale

/**
 * Приводит текст к единому виду перед сравнением: нижний регистр, ё→е, латинские «двойники»
 * кириллических букв внутри русских слов (мошенники пишут «Cбербанк» с латинской C), цифры-двойники
 * (0→о, 3→з) внутри слов, повторы букв («сроооочно») и пунктуация.
 */
object TextNormalizer {
    private val latinToCyrillic = mapOf(
        'a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м',
        'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у'
    )
    private val digitToCyrillic = mapOf('0' to 'о', '3' to 'з', '4' to 'ч', '6' to 'б')

    fun normalize(input: String): String {
        val lowered = input.lowercase(Locale.ROOT).replace('ё', 'е')
        val words = lowered.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return words.joinToString(" ") { fixWord(it) }
    }

    fun tokens(input: String): List<String> = normalize(input).split(' ').filter { it.isNotEmpty() }

    private fun fixWord(word: String): String {
        val cyr = word.count { it in 'а'..'я' }
        var w = word
        if (cyr > 0 && cyr < word.length) {
            // Смешанное слово: латиница и цифры-двойники превращаются в кириллицу.
            w = buildString {
                word.forEach { ch -> append(latinToCyrillic[ch] ?: digitToCyrillic[ch] ?: ch) }
            }
        }
        // «сроооочно» → «срочно»: три и больше одинаковых букв подряд сводим к одной.
        return w.replace(Regex("(\\p{L})\\1{2,}"), "$1")
    }
}
