package ru.ivanivorontsov.nevedis.screening

/** Правила фильтрации входящих. Работает только с метаданными звонка, полностью на устройстве. */
object CallFilterEngine {
    enum class Action { ALLOW, WARN, SILENCE, BLOCK }
    enum class Protection { SOFT, STANDARD, STRICT }

    data class Settings(
        val protection: Protection = Protection.STANDARD,
        val warnUnknown: Boolean = true,
        val hiddenAction: Action = Action.WARN,
        val blockInternational: Boolean = false,
        val contactsOnly: Boolean = false,
        val blockSpoofedOrgNumbers: Boolean = false
    )

    data class CallInfo(
        val rawNumber: String,
        val hidden: Boolean,
        val inContacts: Boolean?,
        val verificationFailed: Boolean = false,
        val hourOfDay: Int = 12,
        val now: Long = System.currentTimeMillis()
    ) {
        val normalized: String get() = normalizePhoneNumber(rawNumber)
    }

    data class Reason(val text: String, val points: Int)

    data class Decision(val action: Action, val risk: Int, val reasons: List<Reason>) {
        val summary: String get() = reasons.sortedByDescending { it.points }.joinToString("; ") { it.text }
    }

    fun decide(
        call: CallInfo,
        settings: Settings,
        whitelist: Set<String>,
        blacklist: Set<String>,
        history: CallHistory
    ): Decision {
        val n = call.normalized
        if (n.isNotEmpty() && listMatches(whitelist, n)) return Decision(Action.ALLOW, 0, emptyList())
        if (n.isNotEmpty() && listMatches(blacklist, n)) {
            return Decision(Action.BLOCK, 100, listOf(Reason("Номер в вашем чёрном списке", 100)))
        }
        if (call.inContacts == true) return Decision(Action.ALLOW, 0, emptyList())

        val reasons = ArrayList<Reason>()
        var forced: Action? = null

        if (call.hidden || n.isEmpty()) {
            reasons += Reason("Скрытый номер: звонящий не показывает, кто он", 40)
            forced = settings.hiddenAction
        }
        if (settings.contactsOnly && call.inContacts == false) {
            reasons += Reason("Режим «только контакты»: номера нет в контактах", 30)
            forced = maxAction(forced, if (settings.protection == Protection.STRICT) Action.BLOCK else Action.SILENCE)
        }
        if (call.verificationFailed) {
            reasons += Reason("Оператор сообщил, что номер не прошёл проверку подлинности: вероятна подмена", 55)
        }

        if (n.isNotEmpty()) {
            val org = KnownNumbers.organizationFor(n)
            when (NumberFormat.classify(call.rawNumber)) {
                NumberFormat.Kind.RU_INVALID -> reasons += Reason("Такого номера нет в российской нумерации: почти наверняка подмена", 70)
                NumberFormat.Kind.MALFORMED -> reasons += Reason("Номер неправильного формата: возможна подмена", 45)
                NumberFormat.Kind.RU_TOLL_FREE -> reasons += Reason("Входящий с 8-800: с таких номеров обычно не звонят, их часто подделывают", 50)
                NumberFormat.Kind.RU_PREMIUM -> reasons += Reason("Платный номер 8-809/8-803", 40)
                NumberFormat.Kind.SATELLITE_OR_GLOBAL -> reasons += Reason("Спутниковый или международный «глобальный» код: дорогие и анонимные звонки", 60)
                NumberFormat.Kind.INTERNATIONAL -> {
                    reasons += Reason("Звонок из-за границы", 35)
                    if (settings.blockInternational) forced = maxAction(forced, Action.BLOCK)
                }
                NumberFormat.Kind.KAZAKHSTAN -> reasons += Reason("Номер Казахстана (+7 6xx/7xx), выглядит как российский", 20)
                NumberFormat.Kind.RU_GEOGRAPHIC -> if (settings.warnUnknown) {
                    val moscow = n.startsWith("7495") || n.startsWith("7499")
                    reasons += Reason(if (moscow) "Незнакомый московский городской номер: их часто подменяют" else "Незнакомый городской номер", if (moscow) 20 else 12)
                }
                NumberFormat.Kind.RU_MOBILE -> if (settings.warnUnknown) reasons += Reason("Номера нет в доверенных", 10)
                NumberFormat.Kind.SHORT_SERVICE, NumberFormat.Kind.EMPTY -> Unit
            }
            if (org != null && n.length == 11) {
                reasons += Reason("Номер совпадает с публичным номером «$org». Такие номера часто подделывают — положите трубку и перезвоните сами", 50)
                if (settings.blockSpoofedOrgNumbers) forced = maxAction(forced, Action.SILENCE)
            }
            if (NumberFormat.looksSynthetic(n)) reasons += Reason("Номер выглядит искусственным (одинаковые цифры)", 15)

            val repeats = history.countFrom(n, call.now, 15 * 60_000L)
            if (repeats >= 2) reasons += Reason("Настойчиво звонит: уже $repeats раз(а) за 15 минут", 20)
            val neighbours = history.distinctNeighbours(n, call.now, 60 * 60_000L)
            if (neighbours >= 2) reasons += Reason("Серия звонков с похожих номеров за час ($neighbours): похоже на массовый обзвон", 25)
        }
        if (reasons.isNotEmpty() && (call.hourOfDay >= 23 || call.hourOfDay < 7)) {
            reasons += Reason("Незнакомый звонок ночью", 10)
        }

        val risk = reasons.sumOf { it.points }.coerceAtMost(100)
        val byRisk = when (settings.protection) {
            Protection.SOFT -> if (risk >= 10) Action.WARN else Action.ALLOW
            Protection.STANDARD -> when {
                risk >= 70 -> Action.SILENCE
                risk >= 10 -> Action.WARN
                else -> Action.ALLOW
            }
            Protection.STRICT -> when {
                risk >= 70 -> Action.BLOCK
                risk >= 40 -> Action.SILENCE
                risk >= 10 -> Action.WARN
                else -> Action.ALLOW
            }
        }
        val action = maxAction(forced, byRisk) ?: Action.ALLOW
        return Decision(action, risk, reasons)
    }

    /** Строка списка может быть точным номером или маской с «*» в конце: «7495123*». */
    fun listMatches(list: Set<String>, normalized: String): Boolean = list.any { item ->
        if (item.endsWith("*")) {
            val digits = item.removeSuffix("*").filter(Char::isDigit)
            val prefix = if (digits.startsWith("8") && !item.trim().startsWith("+")) "7" + digits.drop(1) else digits
            prefix.isNotEmpty() && normalized.startsWith(prefix)
        } else normalizePhoneNumber(item) == normalized
    }

    private fun maxAction(a: Action?, b: Action?): Action? = when {
        a == null -> b
        b == null -> a
        else -> if (a.ordinal >= b.ordinal) a else b
    }
}
