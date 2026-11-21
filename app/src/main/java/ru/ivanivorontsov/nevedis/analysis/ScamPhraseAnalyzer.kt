package ru.ivanivorontsov.nevedis.analysis

import java.util.Locale

/** Offline heuristic detector. It flags language patterns, not people or phone numbers. */
object ScamPhraseAnalyzer {
    enum class Level { LOW, CAUTION, HIGH }

    data class Finding(
        val phrase: String,
        val reason: String,
        val points: Int
    )

    data class Report(
        val level: Level,
        val score: Int,
        val findings: List<Finding>,
        val advice: String
    )

    private data class Rule(
        val regex: Regex,
        val phrase: String,
        val reason: String,
        val points: Int
    )

    private val rules = listOf(
        Rule(Regex("(?<![\\p{L}\\p{N}])(?:(?:инвестир|ивестир)[\\p{L}]*.{0,60}(?:гарантир[\\p{L}]*\\s+(?:прибыл[\\p{L}]*|доход[\\p{L}]*)|гаранти[\\p{L}]*\\s+(?:прибыл[\\p{L}]*|доход[\\p{L}]*))|гарантир[\\p{L}]*\\s+(?:прибыл[\\p{L}]*|доход[\\p{L}]*).{0,60}(?:инвестир|ивестир)[\\p{L}]*|гаранти[\\p{L}]*\\s+(?:прибыл[\\p{L}]*|доход[\\p{L}]*))"), "гарантируют прибыль от инвестиций", "Обещание гарантированной прибыли или дохода от инвестиций — серьёзный признак возможного обмана: инвестиции всегда связаны с риском.", 65),
        Rule(Regex("(?<![\\p{L}\\p{N}])безопасн[\\p{L}]*\\s+(?:банковск[\\p{L}]*\\s+)?сч[её]т(?![\\p{L}\\p{N}])"), "безопасный счёт", "Просят перевести деньги на так называемый «безопасный счёт».", 65),
        Rule(Regex("(?<![\\p{L}\\p{N}])код[\\p{L}]*\\s+(?:из|по)\\s+смс(?![\\p{L}\\p{N}])|(?<![\\p{L}\\p{N}])код\\s+подтверждени[\\p{L}]*(?![\\p{L}\\p{N}])"), "код из СМС", "Настоящие сотрудники банка не должны просить сообщать коды подтверждения.", 65),
        Rule(Regex("(?<![\\p{L}\\p{N}])(?:назовите|продиктуйте|сообщите|скажите)(?![\\p{L}\\p{N}]).{0,35}(?<![\\p{L}\\p{N}])код(?![\\p{L}\\p{N}])"), "просят назвать код", "Собеседник пытается получить одноразовый код.", 60),
        Rule(Regex("(?<![\\p{L}\\p{N}])срочно(?![\\p{L}\\p{N}]).{0,45}(?<![\\p{L}\\p{N}])(?:перевед[\\p{L}]*|отправ[\\p{L}]*|сним[\\p{L}]*|оплат[\\p{L}]*)(?![\\p{L}\\p{N}])"), "срочный перевод денег", "Давление срочностью часто используют в мошеннических сценариях.", 50),
        Rule(Regex("(?<![\\p{L}\\p{N}])(?:мама|мам|сын|дочь|доченька|сынок)(?![\\p{L}\\p{N}]).{0,70}(?<![\\p{L}\\p{N}])(?:авари[\\p{L}]*|попал[\\p{L}]*|попала|задержал[\\p{L}]*|сбил[\\p{L}]*)(?![\\p{L}\\p{N}])"), "история о родственнике в беде", "Возможна схема «родственник попал в беду» с требованием денег.", 60),
        Rule(Regex("(?<![\\p{L}\\p{N}])(?:установите|скачайте)(?![\\p{L}\\p{N}]).{0,45}(?<![\\p{L}\\p{N}])(?:anydesk|teamviewer|rustdesk|приложени[\\p{L}]*)(?![\\p{L}\\p{N}])"), "просят установить приложение удалённого доступа", "Удалённый доступ к телефону или компьютеру может дать мошеннику контроль над устройством.", 65),
        Rule(Regex("(?<![\\p{L}\\p{N}])не\\s+кладите\\s+трубку(?![\\p{L}\\p{N}])"), "запрещают класть трубку", "Попытка не дать вам спокойно проверить информацию — тревожный признак.", 35),
        Rule(Regex("(?<![\\p{L}\\p{N}])(?:представитель|сотрудник)(?![\\p{L}\\p{N}]).{0,35}(?<![\\p{L}\\p{N}])(?:центральн[\\p{L}]*\\s+банк[\\p{L}]*|цб|фсб|полици[\\p{L}]*|следственн[\\p{L}]*)(?![\\p{L}\\p{N}])"), "ссылка на государственный орган или ЦБ", "Название ведомства используют для давления; перепроверьте звонок самостоятельно.", 30),
        Rule(Regex("(?<![\\p{L}\\p{N}])(?:переведите|перевести)(?![\\p{L}\\p{N}]).{0,35}(?<![\\p{L}\\p{N}])(?:резервн[\\p{L}]*|защитн[\\p{L}]*|специальн[\\p{L}]*)\\s+сч[её]т(?![\\p{L}\\p{N}])"), "перевод на специальный счёт", "Предложение перевести деньги на «специальный» счёт — типичный маркер обмана.", 65),
        Rule(Regex("(?<![\\p{L}\\p{N}])никому\\s+не\\s+(?:говорите|рассказывайте|сообщайте)(?![\\p{L}\\p{N}])"), "требуют хранить разговор в тайне", "Изоляция от близких помогает мошенникам не дать вам проверить историю.", 30)
    )

    private val matcher by lazy { PhraseMatcher() }

    /** Размер базы фраз — для экрана «О базе». */
    val databasePhraseCount: Int get() = PhraseDatabase.default.phraseCount + rules.size
    val databaseCategoryCount: Int get() = PhraseDatabase.default.categories.size

    fun analyze(input: String): Report {
        val text = input.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

        if (text.isBlank()) {
            return Report(Level.LOW, 0, emptyList(), "Вставьте фразу или краткий пересказ разговора.")
        }

        val findings = rules.mapNotNull { rule ->
            if (rule.regex.containsMatchIn(text)) Finding(rule.phrase, rule.reason, rule.points) else null
        }.distinctBy { it.phrase }

        // A warning such as “не сообщайте код из СМС” is protective advice, not a scam request.
        val protectiveAdvice = Regex("(?<![\\p{L}\\p{N}])не\\s+(?:называйте|сообщайте|диктуйте|передавайте)(?![\\p{L}\\p{N}]).{0,30}(?<![\\p{L}\\p{N}])код(?![\\p{L}\\p{N}])")
            .containsMatchIn(text) ||
            // «Код 1234. Никому не сообщайте его» — стандартная приписка в настоящих СМС банков.
            Regex("(?<![\\p{L}\\p{N}])код(?![\\p{L}\\p{N}]).{0,40}(?:никому\\s+)?не\\s+(?:сообщайте|называйте|говорите|передавайте|диктуйте)(?![\\p{L}\\p{N}])")
                .containsMatchIn(text)
        val protectiveTransferAdvice = Regex(
            "(?<![\\p{L}\\p{N}])не\\s+(?:переводите|переводить|отправляйте|отправлять|перечисляйте|перечислять)(?![\\p{L}\\p{N}]).{0,70}(?:безопасн[\\p{L}]*\\s+сч[её]т|резервн[\\p{L}]*\\s+сч[её]т|защитн[\\p{L}]*\\s+сч[её]т|специальн[\\p{L}]*\\s+сч[её]т)"
        ).containsMatchIn(text)
        val filtered = findings.filterNot { finding ->
            (protectiveAdvice && (finding.phrase == "код из СМС" || finding.phrase == "просят назвать код")) ||
                (protectiveTransferAdvice && (finding.phrase == "безопасный счёт" || finding.phrase == "перевод на специальный счёт")) ||
                (protectiveAdvice && finding.phrase == "требуют хранить разговор в тайне" &&
                    Regex("никому\\s+не\\s+\\p{L}+(?:\\s+\\p{L}+){0,2}\\s+(?:код|пароль|его)").containsMatchIn(text) ||
                    Regex("(?:код|пароль)[\\p{L}\\s]{0,40}никому\\s+не").containsMatchIn(text) && finding.phrase == "требуют хранить разговор в тайне")
        }
        val legacyScore = filtered.sumOf { it.points }.coerceAtMost(100)

        // Полная база фраз: словоформы, опечатки, сочетания признаков.
        val db = matcher.match(input)
        val dbFindings = db.hits.map { hit ->
            Finding(
                phrase = hit.category.title,
                reason = hit.category.reason + " Услышано: «" + hit.fragments.joinToString("», «") + "».",
                points = hit.score
            )
        } + db.combos.map { Finding(it.title, "Сочетание признаков усиливает риск.", it.bonus) }

        val score = (maxOf(legacyScore, db.score) + minOf(legacyScore, db.score) / 3).coerceAtMost(100)
        val level = levelFor(score)
        val advice = when (level) {
            Level.HIGH -> "ПРЕРВИТЕ РАЗГОВОР. Не переводите деньги и не называйте коды. Позвоните в организацию или родственнику по номеру из своей записной книжки."
            Level.CAUTION -> "Не торопитесь. Завершите разговор и самостоятельно перепроверьте информацию по официальному номеру."
            Level.LOW -> "Явных маркеров из локального списка не найдено. Это не доказывает, что звонок безопасен."
        }
        return Report(level, score, filtered + dbFindings, advice)
    }

    fun levelFor(score: Int): Level = when {
        score >= 55 -> Level.HIGH
        score >= 30 -> Level.CAUTION
        else -> Level.LOW
    }
}
