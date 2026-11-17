package ru.ivanivorontsov.nevedis.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamPhraseAnalyzerTest {
    @Test fun guaranteedInvestmentProfitIsHighRisk() {
        val report = ScamPhraseAnalyzer.analyze("Инвестируйте с гарантией прибыли")
        assertEquals(ScamPhraseAnalyzer.Level.HIGH, report.level)
        assertTrue(report.findings.any { it.phrase == "гарантируют прибыль от инвестиций" })
    }

    @Test fun commonTypoInInvestmentWordIsStillDetected() {
        val report = ScamPhraseAnalyzer.analyze("Ивестируйте с гарантией прибыли")
        assertEquals(ScamPhraseAnalyzer.Level.HIGH, report.level)
        assertTrue(report.findings.any { it.phrase == "гарантируют прибыль от инвестиций" })
    }

    @Test fun guaranteedInvestmentIncomeWithoutVerbIsDetected() {
        val report = ScamPhraseAnalyzer.analyze("Гарантированный доход от инвестиций")
        assertTrue(report.findings.any { it.phrase == "гарантируют прибыль от инвестиций" })
    }

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

    @Test fun protectiveTransferWarningDoesNotTriggerSafeAccountAlert() {
        val report = ScamPhraseAnalyzer.analyze("Не переводите деньги на безопасный счёт, это обман")
        assertTrue(report.findings.none { it.phrase == "безопасный счёт" })
        assertEquals(ScamPhraseAnalyzer.Level.LOW, report.level)
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
