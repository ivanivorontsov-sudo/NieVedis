package ru.ivanivorontsov.nevedis.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamPhraseAnalyzerTest {
    @Test fun safeAccountIsHighRisk() {
        val report = ScamPhraseAnalyzer.analyze("Срочно переведите деньги на безопасный счёт")
        assertEquals(ScamPhraseAnalyzer.Level.HIGH, report.level)
        assertTrue(report.findings.isNotEmpty())
    }

    @Test fun smsCodeRequestIsHighRisk() {
        val report = ScamPhraseAnalyzer.analyze("Продиктуйте код из СМС")
        assertEquals(ScamPhraseAnalyzer.Level.HIGH, report.level)
    }

    @Test fun protectiveAdviceDoesNotTriggerCodeRequest() {
        val report = ScamPhraseAnalyzer.analyze("Не сообщайте никому код из СМС")
        assertTrue(report.findings.none { it.phrase == "код из СМС" || it.phrase == "просят назвать код" })
    }

    @Test fun emptyInputIsLowRisk() {
        val report = ScamPhraseAnalyzer.analyze("   ")
        assertEquals(ScamPhraseAnalyzer.Level.LOW, report.level)
        assertEquals(0, report.score)
    }

    @Test fun relativeInTroubleScenarioIsDetected() {
        val report = ScamPhraseAnalyzer.analyze("Мама, я попал в аварию, срочно нужны деньги")
        assertTrue(report.findings.any { it.phrase == "история о родственнике в беде" })
    }
}
