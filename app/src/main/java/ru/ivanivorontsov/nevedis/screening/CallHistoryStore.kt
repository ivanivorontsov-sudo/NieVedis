package ru.ivanivorontsov.nevedis.screening

/**
 * Локальная история незнакомых входящих (только номер и время, хранится на устройстве до 7 дней).
 * Нужна для эвристик «настойчивых» звонков и серийного обзвона с соседних номеров.
 */
class CallHistory(private val entries: MutableList<Pair<String, Long>> = mutableListOf()) {
    fun record(number: String, at: Long) {
        entries += number to at
        prune(at)
    }

    fun prune(now: Long) {
        entries.removeAll { now - it.second > RETENTION_MS }
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Сколько раз этот номер звонил за окно. */
    fun countFrom(number: String, now: Long, windowMs: Long): Int =
        entries.count { it.first == number && now - it.second <= windowMs }

    /** Сколько разных номеров с тем же префиксом (первые 8 цифр) звонили за окно — признак серийного обзвона. */
    fun distinctNeighbours(number: String, now: Long, windowMs: Long): Int {
        if (number.length < 10) return 0
        val prefix = number.take(8)
        return entries.filter { now - it.second <= windowMs && it.first != number && it.first.startsWith(prefix) }
            .map { it.first }.toSet().size
    }

    fun serialize(): String = entries.joinToString(";") { "${it.first},${it.second}" }

    companion object {
        const val RETENTION_MS = 7L * 24 * 3600 * 1000
        const val MAX_ENTRIES = 500

        fun parse(s: String?): CallHistory = CallHistory(
            s.orEmpty().split(';').mapNotNull { item ->
                val p = item.split(',')
                val t = p.getOrNull(1)?.toLongOrNull()
                if (p.size == 2 && t != null && p[0].isNotEmpty()) p[0] to t else null
            }.toMutableList()
        )
    }
}
