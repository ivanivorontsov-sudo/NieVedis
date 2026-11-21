package ru.ivanivorontsov.nevedis.screening

/**
 * Публичные справочные номера банков и ведомств (с карт и официальных сайтов). На такие номера
 * обычно звонят клиенты, а мошенники часто подделывают их при входящем звонке. Совпадение — не
 * доказательство обмана, а повод положить трубку и перезвонить самому.
 */
object KnownNumbers {
    data class Org(val name: String, val numbers: List<String>)

    val orgs: List<Org> = listOf(
        Org("Сбербанк", listOf("88005555550", "74955005550", "900")),
        Org("ВТБ", listOf("88001002424", "74957772424", "1000")),
        Org("Альфа-Банк", listOf("88002000000", "74957888878")),
        Org("Т-Банк", listOf("88005557778", "74956481111")),
        Org("Газпромбанк", listOf("88001000701")),
        Org("Райффайзенбанк", listOf("88007009100")),
        Org("Совкомбанк", listOf("88001000006")),
        Org("Россельхозбанк", listOf("88001000100")),
        Org("ПСБ", listOf("88003330303")),
        Org("Банк «Открытие»", listOf("88004444400")),
        Org("МТС Банк", listOf("88002500520")),
        Org("Росбанк", listOf("88002005434")),
        Org("Банк России", listOf("88003003000", "74993003000", "300")),
        Org("Госуслуги", listOf("88001007010", "115")),
        Org("Социальный фонд России", listOf("88001000001"))
    )

    private val index: Map<String, String> = orgs.flatMap { o -> o.numbers.map { normalizePhoneNumber(it) to o.name } }.toMap()

    fun organizationFor(normalized: String): String? = index[normalized]
}
