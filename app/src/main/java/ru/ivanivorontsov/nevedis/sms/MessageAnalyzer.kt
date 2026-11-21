package ru.ivanivorontsov.nevedis.sms

import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer
import ru.ivanivorontsov.nevedis.analysis.TextNormalizer

/** Проверка СМС и сообщений мессенджеров на устройстве: ссылки, отправитель, коды во время звонка, база фраз. */
object MessageAnalyzer {
    data class Reason(val text: String, val points: Int)
    data class Report(val score: Int, val level: ScamPhraseAnalyzer.Level, val reasons: List<Reason>) {
        val summary: String get() = reasons.sortedByDescending { it.points }.joinToString("; ") { it.text }
    }

    private val urlRegex = Regex("(?i)\\b((?:https?://)?(?:[a-z0-9\\-а-яё]+\\.)+(?:[a-z]{2,24}|рф|xn--[a-z0-9]+)(?::\\d+)?(?:/[^\\s]*)?)")
    private val ipUrl = Regex("(?i)\\b(?:https?://)?\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
    private val codeRegex = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val phoneInText = Regex("(?:\\+7|8)[\\s\\-(]*\\d{3}[\\s\\-)]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2}")

    private val shorteners = setOf(
        "bit.ly", "clck.ru", "goo.su", "tinyurl.com", "cutt.ly", "t.ly", "is.gd", "u.to", "rebrand.ly", "ow.ly",
        "shorturl.at", "qps.ru", "vk.cc", "tiny.cc", "rb.gy", "s.id", "lnkd.in", "surl.li"
    )
    private val brandTokens = mapOf(
        "sber" to "Сбер", "sbrf" to "Сбер", "vtb" to "ВТБ", "alfa" to "Альфа-Банк", "tinkoff" to "Т-Банк", "tbank" to "Т-Банк",
        "gosuslug" to "Госуслуги", "gosuslugi" to "Госуслуги", "nalog" to "ФНС", "pochta" to "Почта России", "cdek" to "СДЭК",
        "ozon" to "Ozon", "wildberries" to "Wildberries", "avito" to "Авито", "gibdd" to "ГИБДД", "mvd" to "МВД",
        "cbr" to "Банк России", "pfr" to "Соцфонд", "sfr" to "Соцфонд", "mts" to "МТС", "megafon" to "МегаФон", "beeline" to "Билайн",
        "сбер" to "Сбер", "госуслуг" to "Госуслуги", "втб" to "ВТБ", "налог" to "ФНС", "почта" to "Почта России"
    )
    /** Официальные домены (и их поддомены) — для них «двойник бренда» не срабатывает. */
    private val officialDomains = setOf(
        "sberbank.ru", "sber.ru", "sberbank.com", "online.sberbank.ru", "vtb.ru", "alfabank.ru", "tbank.ru", "tinkoff.ru",
        "gosuslugi.ru", "nalog.gov.ru", "nalog.ru", "pochta.ru", "cdek.ru", "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru",
        "gibdd.ru", "мвд.рф", "cbr.ru", "sfr.gov.ru", "mts.ru", "megafon.ru", "beeline.ru", "t.me"
    )
    private val bankWords = Regex("(?i)(банк|сбер|втб|альфа|тинькофф|т-банк|госуслуг|налогов|пенси|соцфонд|мвд|полици|цб|центробанк)")
    private val codeWords = Regex("(?i)(код|парол|code|pin|пин)")

    fun analyze(sender: String?, body: String, inCall: Boolean): Report {
        val reasons = ArrayList<Reason>()
        val lower = body.lowercase()

        val links = urlRegex.findAll(lower).map { it.value.trimEnd('.', ',', ')', '!') }.filter { it.contains('.') }
            .filterNot { codeRegex.matches(it.replace(".", "")) }.toList()
        for (link in links) {
            val host = link.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.")
            val official = officialDomains.any { host == it || host.endsWith(".$it") }
            val punycode = host.split('.').dropLast(1).any { it.startsWith("xn--") }
            val brand = brandTokens.entries.firstOrNull { host.replace("-", "").contains(it.key) }?.value
            when {
                official -> Unit
                host in shorteners -> reasons += Reason("Сокращённая ссылка ($host): не видно, куда она ведёт", 35)
                brand != null -> reasons += Reason("Ссылка похожа на сайт «$brand», но это не официальный адрес: $host", 55)
                punycode -> reasons += Reason("Ссылка с «маскировкой» символов (punycode): $host", 30)
                else -> reasons += Reason("Ссылка в сообщении: $host — не переходите, если не ждали", 15)
            }
        }
        if (ipUrl.containsMatchIn(lower)) reasons += Reason("Ссылка на IP-адрес вместо сайта", 40)

        val senderDigits = sender.orEmpty().filter(Char::isDigit)
        val fromPersonalNumber = senderDigits.length >= 10
        if (fromPersonalNumber && bankWords.containsMatchIn(lower)) {
            reasons += Reason("Сообщение «от банка/ведомства» пришло с обычного номера ${sender.orEmpty()}", 40)
        }
        val hasCode = codeRegex.containsMatchIn(body) && codeWords.containsMatchIn(lower)
        if (hasCode && inCall) {
            reasons += Reason("Код пришёл прямо во время разговора. Если собеседник просит его назвать — это мошенник", 65)
        }
        if (phoneInText.containsMatchIn(body) && Regex("(?i)(позвоните|перезвоните|свяжитесь|звоните)").containsMatchIn(lower)) {
            reasons += Reason("Просят перезвонить по номеру из сообщения — звоните только по номеру с карты или сайта", 30)
        }

        val phrase = ScamPhraseAnalyzer.analyze(body)
        phrase.findings.sortedByDescending { it.points }.take(3).forEach {
            reasons += Reason(it.phrase, (it.points * 0.8).toInt())
        }
        val normalized = TextNormalizer.normalize(body)
        if (normalized.isEmpty() && reasons.isEmpty()) return Report(0, ScamPhraseAnalyzer.Level.LOW, emptyList())

        val linkPoints = reasons.filter { it.text.contains("сылк") }.maxOfOrNull { it.points } ?: 0
        val otherPoints = reasons.filterNot { it.text.contains("сылк") }.sumOf { it.points }
        val score = (linkPoints + otherPoints).coerceAtMost(100)
        return Report(score, ScamPhraseAnalyzer.levelFor(score), reasons.distinctBy { it.text })
    }
}
