package ru.ivanivorontsov.nevedis.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ivanivorontsov.nevedis.analysis.ScamPhraseAnalyzer.Level

class MessageAnalyzerTest {
    @Test fun genuineBankCodeOutsideCallIsLow() {
        val r = MessageAnalyzer.analyze("900", "Код: 4821. Никому не сообщайте код, даже сотруднику банка.", inCall = false)
        assertEquals(Level.LOW, r.level)
    }

    @Test fun codeDuringCallIsHigh() {
        val r = MessageAnalyzer.analyze("Gosuslugi", "Код подтверждения 482193. Никому его не сообщайте", inCall = true)
        assertEquals(Level.HIGH, r.level)
    }

    @Test fun lookalikeBankDomainIsFlagged() {
        val r = MessageAnalyzer.analyze("+79991234567", "Ваша карта заблокирована, разблокируйте: sberbank-online-help.ru/login", inCall = false)
        assertEquals(Level.HIGH, r.level)
        assertTrue(r.reasons.any { it.text.contains("Сбер") })
    }

    @Test fun officialDomainIsNotLookalike() {
        val r = MessageAnalyzer.analyze("Tinkoff", "Выписка готова в приложении tbank.ru", inCall = false)
        assertTrue(r.reasons.none { it.text.contains("не официальный") })
    }

    @Test fun shortenerAndCallback() {
        val r = MessageAnalyzer.analyze("+79001112233", "Вам положена компенсация. Подробнее clck.ru/abc или позвоните 8 999 123-45-67", inCall = false)
        assertTrue(r.score >= 55)
    }

    @Test fun ordinaryMessageIsLow() {
        assertEquals(Level.LOW, MessageAnalyzer.analyze("+79001112233", "Буду через 10 минут, жди у подъезда", false).level)
    }
}
