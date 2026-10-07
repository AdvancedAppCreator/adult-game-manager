package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLanguageDetectorTest {
    @Test
    fun japaneseKanaInTitleOverridesContentDetection() {
        assertEquals("ja", preferredGameLanguage("田舎でかてきょ", "en"))
        assertEquals("ja", preferredGameLanguage("ゲーム 2026", null))
    }

    @Test
    fun kanjiOnlyTitleUsesConservativeContentDetection() {
        assertEquals("zh", preferredGameLanguage("冒険物語", "zh"))
        assertEquals(null, preferredGameLanguage("冒険物語", null))
    }
}
