package ru.ivanivorontsov.nevedis.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseMatcherTest {
    private val matcher = PhraseMatcher()
    private val db = PhraseDatabase.default

    @Test fun databaseHasAtLeast500PhrasesAndAllCategoriesUsed() {
        assertTrue("phrases: ${db.phraseCount}", db.phraseCount >= 500)
        val used = db.entries.map { it.category }.toSet()
        assertEquals(db.categories.keys, used)
        assertTrue(db.categories.size >= 25)
    }

    @Test fun noPatternBreaksOnMultiWordAlternatives() {
        // Варианты через «/» должны быть однословными, иначе шаблон молча не сработает.
        db.entries.filter { it.regex == null }.forEach { e ->
            e.tokens.forEach { t -> (t.stems + t.prefixes).forEach { assertFalse("${e.source}: '$it'", it.contains(' ')) } }
        }
    }

    @Test fun everyPatternMatchesItsOwnText() {
        // Самопроверка: каждый шаблон, записанный обычным текстом (первый вариант каждого слова), срабатывает.
        val failures = db.entries.filter { it.regex == null && it.category != PhraseMatcher.PROTECTIVE }.filter { e ->
            val sample = e.source.split(Regex("\\s+")).filterNot { it.startsWith("?") }
                .joinToString(" ") { it.split('/').first().removeSuffix("*") }
            matcher.match(sample).hits.none { it.category.id == e.category }
        }.map { it.source }
        assertTrue("Не сработали: $failures", failures.isEmpty())
    }

    @Test fun stemmerHandlesWordForms() {
        assertEquals(RussianStemmer.stem("безопасный"), RussianStemmer.stem("безопасного"))
        assertEquals(RussianStemmer.stem("счет"), RussianStemmer.stem("счета"))
        assertEquals(RussianStemmer.stem("переведите"), RussianStemmer.stem("переведи"))
        assertTrue(matcher.match("Кредиты оформлены на ваше имя").categoryIds.contains("suspicious_operation"))
    }

    @Test fun safeAccountInAnyWordFormIsHigh() {
        val r = matcher.match("Вам нужно перевести все деньги на защищённый счёт, который мы открыли")
        assertTrue(r.categoryIds.contains("safe_account"))
        assertTrue(r.score >= 55)
    }

    @Test fun latinLookalikesAndYoAreNormalized() {
        val r = matcher.match("Служба бeзoпacнocти Cбербанка, пeреведите на рeзервный cчёт")
        assertTrue(r.categoryIds.containsAll(listOf("bank_security", "safe_account")))
    }

    @Test fun typoInLongWordStillMatches() {
        val r = matcher.match("Это служба безопастности банка")
        assertTrue(r.categoryIds.contains("bank_security"))
    }

    @Test fun insertedWordsBetweenPhraseWordsAreTolerated() {
        val r = matcher.match("Продиктуйте мне, пожалуйста, код")
        assertTrue(r.categoryIds.contains("code_request"))
    }

    @Test fun combosRaiseScore() {
        val r = matcher.match("Я из службы безопасности банка, назовите код из СМС")
        assertTrue(r.combos.isNotEmpty())
        assertTrue(r.score >= 55)
    }

    @Test fun relativeAndMoneyCombo() {
        val r = matcher.match("Мама, это я, попал в аварию, переведи мне на эту карту")
        assertTrue(r.categoryIds.contains("relative_trouble"))
        assertTrue(r.score >= 55)
    }

    @Test fun protectiveBankSmsIsNotFlagged() {
        val r = matcher.match("Код 4821. Никому не сообщайте этот код, даже сотруднику банка")
        assertTrue("score ${r.score} ${r.hits.map { it.category.id }}", r.score < 30)
    }

    @Test fun protectiveWarningIsNotFlagged() {
        val r = matcher.match("Никогда не переводите деньги на безопасный счёт")
        assertFalse(r.categoryIds.contains("safe_account"))
    }

    @Test fun scammerCannotHideCodeRequestBehindSecrecy() {
        val r = matcher.match("Назовите код и никому не сообщайте о нашем разговоре")
        assertTrue(r.categoryIds.contains("code_request"))
        assertTrue(r.score >= 55)
    }

    @Test fun scammerMentioningFraudstersIsStillFlagged() {
        val r = matcher.match("Мошенники пытаются похитить ваши средства, поэтому переведите их на резервный счёт")
        assertTrue(r.score >= 55)
    }

    @Test fun ordinaryConversationIsLow() {
        listOf(
            "Привет, как дела? Давай встретимся вечером в кафе",
            "Купи, пожалуйста, хлеба и молока по дороге домой",
            "Завтра совещание в десять, не опаздывай",
            "Ваш заказ готов к выдаче в пункте, код получения 1234"
        ).forEach { assertTrue(it, matcher.match(it).score < 55) }
    }

    @Test fun policeScriptIsHigh() {
        val r = matcher.match("Вас беспокоит следователь, возбуждено уголовное дело, вы подписываете подписку о неразглашении")
        assertTrue(r.score >= 55)
    }

    @Test fun mobileOperatorScript() {
        val r = matcher.match("Срок действия вашего договора истекает, продиктуйте код из смс для продления")
        assertTrue(r.categoryIds.contains("mobile_operator"))
        assertTrue(r.score >= 55)
    }

    @Test fun remoteAccess() {
        val r = matcher.match("Установите приложение RustDesk, наш специалист подключится к телефону")
        assertTrue(r.categoryIds.contains("remote_access"))
        assertTrue(r.score >= 55)
    }

    @Test fun editDistance() {
        assertTrue(PhraseMatcher.withinOneEdit("безопасн", "безопастн"))
        assertTrue(PhraseMatcher.withinOneEdit("кредит", "кердит"))
        assertFalse(PhraseMatcher.withinOneEdit("кредит", "дебет"))
    }
}
