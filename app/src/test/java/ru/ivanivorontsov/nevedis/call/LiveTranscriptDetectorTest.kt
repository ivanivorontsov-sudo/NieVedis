package ru.ivanivorontsov.nevedis.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptDetectorTest {
    @Test fun alertsOnScamSpeechSplitAcrossResults() {
        val d = LiveTranscriptDetector()
        assertNull(d.onText("здравствуйте это служба безопасности", true, 0))
        val alert = d.onText("банка переведите деньги на безопасный счет", true, 1000)
        assertNotNull(alert)
        assertTrue(alert!!.high)
        assertTrue(alert.title.startsWith("Похоже на мошенника"))
    }

    @Test fun partialResultsAreChecked() {
        val d = LiveTranscriptDetector()
        assertNotNull(d.onText("продиктуйте код из смс", false, 0))
    }

    @Test fun cooldownPreventsSpam() {
        val d = LiveTranscriptDetector(cooldownMs = 10_000)
        assertNotNull(d.onText("назовите код из смс", true, 0))
        assertNull(d.onText("назовите код", true, 2_000))
    }

    @Test fun ordinaryTalkIsSilent() {
        val d = LiveTranscriptDetector()
        assertNull(d.onText("привет как дела что на ужин купи хлеба", true, 0))
    }

    @Test fun clearWipesMemoryAndWindowIsBounded() {
        val d = LiveTranscriptDetector(windowWords = 10)
        d.onText((1..50).joinToString(" ") { "слово$it" }, true, 0)
        assertEquals(10, d.wordsInMemory)
        d.clear()
        assertEquals(0, d.wordsInMemory)
    }
}
