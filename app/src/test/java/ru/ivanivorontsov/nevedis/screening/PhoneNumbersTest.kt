package ru.ivanivorontsov.nevedis.screening

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumbersTest {
    @Test fun russianInternationalFormatIsPreserved() {
        assertEquals("79991234567", normalizePhoneNumber("+7 (999) 123-45-67"))
    }

    @Test fun russianNationalTrunkPrefixIsNormalized() {
        assertEquals("79991234567", normalizePhoneNumber("8 999 123 45 67"))
    }

    @Test fun tenDigitRussianNumberGetsCountryCode() {
        assertEquals("79991234567", normalizePhoneNumber("9991234567"))
    }

    @Test fun internationalNonRussianNumberIsNotRewritten() {
        assertEquals("442071838750", normalizePhoneNumber("+44 20 7183 8750"))
    }
}
