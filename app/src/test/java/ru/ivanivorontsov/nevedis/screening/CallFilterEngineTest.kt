package ru.ivanivorontsov.nevedis.screening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine.Action
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine.CallInfo
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine.Protection
import ru.ivanivorontsov.nevedis.screening.CallFilterEngine.Settings

class CallFilterEngineTest {
    private fun decide(number: String, settings: Settings = Settings(), hidden: Boolean = false, inContacts: Boolean? = null,
                       white: Set<String> = emptySet(), black: Set<String> = emptySet(), history: CallHistory = CallHistory(),
                       verificationFailed: Boolean = false, hour: Int = 12, now: Long = 1_000_000_000L) =
        CallFilterEngine.decide(CallInfo(number, hidden, inContacts, verificationFailed, hour, now), settings, white, black, history)

    @Test fun whitelistAlwaysAllows() {
        assertEquals(Action.ALLOW, decide("+7 800 555-55-50", white = setOf("88005555550")).action)
    }

    @Test fun blacklistBlocksExactAndMask() {
        assertEquals(Action.BLOCK, decide("+79991234567", black = setOf("89991234567")).action)
        assertEquals(Action.BLOCK, decide("+74951234567", black = setOf("8495123*")).action)
    }

    @Test fun contactsAreAllowed() {
        assertEquals(Action.ALLOW, decide("+79991234567", inContacts = true).action)
    }

    @Test fun hiddenNumberFollowsSetting() {
        assertEquals(Action.WARN, decide("", hidden = true).action)
        assertEquals(Action.BLOCK, decide("", hidden = true, settings = Settings(hiddenAction = Action.BLOCK)).action)
    }

    @Test fun invalidRussianCodeIsSpoofing() {
        val d = decide("+7 012 345-67-89")
        assertTrue(d.risk >= 60)
        assertEquals(Action.BLOCK, decide("+7 012 345-67-89", settings = Settings(protection = Protection.STRICT)).action)
    }

    @Test fun incomingTollFreeAndBankNumberIsHighRisk() {
        val d = decide("+7 800 555-55-50")
        assertTrue(d.reasons.any { it.text.contains("Сбербанк") })
        assertEquals(Action.SILENCE, d.action)
    }

    @Test fun satelliteCodes() {
        assertTrue(decide("+882 1234 5678").risk >= 60)
    }

    @Test fun internationalCanBeBlocked() {
        assertEquals(Action.WARN, decide("+44 20 7183 8750").action)
        assertEquals(Action.BLOCK, decide("+44 20 7183 8750", settings = Settings(blockInternational = true)).action)
    }

    @Test fun contactsOnlyMode() {
        assertEquals(Action.SILENCE, decide("+79991234567", inContacts = false, settings = Settings(contactsOnly = true)).action)
        assertEquals(Action.BLOCK, decide("+79991234567", inContacts = false, settings = Settings(contactsOnly = true, protection = Protection.STRICT)).action)
    }

    @Test fun repeatedCallsAndSeriesAreDetected() {
        val h = CallHistory()
        val now = 10_000_000L
        h.record("79991234567", now - 60_000)
        h.record("79991234567", now - 30_000)
        assertTrue(decide("+79991234567", history = h, now = now).reasons.any { it.text.contains("Настойчиво") })
        val s = CallHistory()
        s.record("79991234511", now - 60_000)
        s.record("79991234522", now - 50_000)
        assertTrue(decide("+79991234533", history = s, now = now).reasons.any { it.text.contains("Серия") })
    }

    @Test fun softModeNeverSilences() {
        assertEquals(Action.WARN, decide("+7 012 345-67-89", settings = Settings(protection = Protection.SOFT)).action)
    }

    @Test fun verificationFailureAddsRisk() {
        assertTrue(decide("+79991234567", verificationFailed = true).risk >= 55)
    }

    @Test fun unknownMobileIsOnlyMildWarning() {
        val d = decide("+79161234589")
        assertEquals(Action.WARN, d.action)
        assertTrue(d.risk < 30)
        assertEquals(Action.ALLOW, decide("+79161234589", settings = Settings(warnUnknown = false)).action)
        assertTrue(decide("+79991234567").reasons.any { it.text.contains("искусственным") })
    }

    @Test fun numberFormatClassification() {
        assertEquals(NumberFormat.Kind.RU_MOBILE, NumberFormat.classify("8 912 345 67 89"))
        assertEquals(NumberFormat.Kind.KAZAKHSTAN, NumberFormat.classify("+7 701 123 45 67"))
        assertEquals(NumberFormat.Kind.SHORT_SERVICE, NumberFormat.classify("900"))
        assertEquals(NumberFormat.Kind.RU_TOLL_FREE, NumberFormat.classify("8 800 100 24 24"))
        assertEquals(NumberFormat.Kind.MALFORMED, NumberFormat.classify("+7 912 34"))
    }

    @Test fun historySerializationRoundTrip() {
        val h = CallHistory()
        h.record("79991234567", 5000L)
        val back = CallHistory.parse(h.serialize())
        assertEquals(1, back.countFrom("79991234567", 6000L, 10_000L))
    }
}
