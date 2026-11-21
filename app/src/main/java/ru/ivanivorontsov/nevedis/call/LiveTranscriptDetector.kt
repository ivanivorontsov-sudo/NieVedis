package ru.ivanivorontsov.nevedis.call

import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer

/**
 * Проверяет распознанную речь «на лету». Хранит в памяти только последние слова разговора
 * (скользящее окно) — ничего не пишется на диск. Возвращает предупреждение, когда риск высокий
 * или появился новый сильный признак; повторяет одно и то же не чаще раза в [cooldownMs].
 */
class LiveTranscriptDetector(
    private val windowWords: Int = 80,
    private val cooldownMs: Long = 25_000
) {
    data class Alert(val title: String, val details: String, val score: Int, val high: Boolean)

    private val finalWords = ArrayDeque<String>()
    private var partial: String = ""
    private val lastAlertAt = HashMap<String, Long>()

    fun onText(text: String, isFinal: Boolean, now: Long): Alert? {
        if (isFinal) {
            text.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { finalWords.addLast(it) }
            while (finalWords.size > windowWords) finalWords.removeFirst()
            partial = ""
        } else {
            partial = text
        }
        val window = (finalWords.joinToString(" ") + " " + partial).trim()
        if (window.isEmpty()) return null
        val report = ScamPhraseAnalyzer.analyze(window)
        if (report.level == ScamPhraseAnalyzer.Level.LOW) return null
        val strongest = report.findings.maxByOrNull { it.points } ?: return null
        val high = report.level == ScamPhraseAnalyzer.Level.HIGH
        val key = if (high) "HIGH:" + strongest.phrase else "CAUTION:" + strongest.phrase
        val last = lastAlertAt[key]
        if (last != null && now - last < cooldownMs) return null
        if (!high && strongest.points < 40) return null
        lastAlertAt[key] = now
        val title = if (high) "Похоже на мошенника: ${strongest.phrase}" else "Осторожно: ${strongest.phrase}"
        return Alert(title, report.advice, report.score, high)
    }

    /** Стирает всё услышанное (вызывается при завершении звонка). */
    fun clear() {
        finalWords.clear()
        partial = ""
        lastAlertAt.clear()
    }

    val wordsInMemory: Int get() = finalWords.size
}
