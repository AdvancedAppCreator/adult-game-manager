package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRecommendationTest {
    @Test
    fun renpyRecommendsJoiPlay() {
        val a = InstallRecommendation.analyze(listOf("Game.exe", "renpy/main.py", "game/script.rpa"))
        assertEquals(InstallRecommendation.GameEngine.RENPY, a.engine)
        assertEquals(InstallRecommendation.Runner.JoiPlay, a.recommended)
        assertTrue(a.joiPlayCapable)
    }

    @Test
    fun rpgMakerMvRecommendsJoiPlay() {
        val a = InstallRecommendation.analyze(listOf("Game.exe", "www/index.html", "package.json"))
        assertEquals(InstallRecommendation.GameEngine.RPGM_MV_MZ, a.engine)
        assertTrue(a.joiPlayCapable)
    }

    @Test
    fun rgssRecommendsJoiPlayAndMentionsWineAudio() {
        val a = InstallRecommendation.analyze(listOf("Game.exe", "Game.rgss3a", "System/RGSS301.dll"))
        assertEquals(InstallRecommendation.GameEngine.RPGM_RGSS, a.engine)
        assertEquals(InstallRecommendation.Runner.JoiPlay, a.recommended)
        assertTrue(a.reasons.any { it.contains("audio", ignoreCase = true) })
    }

    @Test
    fun unityRecommendsWinlatorAndIsNotJoiPlayCapable() {
        val a = InstallRecommendation.analyze(listOf("Game.exe", "Game_Data/app.info", "UnityPlayer.dll"))
        assertEquals(InstallRecommendation.GameEngine.UNITY, a.engine)
        assertEquals(InstallRecommendation.Runner.Winlator, a.recommended)
        assertFalse(a.joiPlayCapable)
    }

    @Test
    fun unrealRecommendsWinlator() {
        val a = InstallRecommendation.analyze(listOf("MyGame-Win64-Shipping.exe", "Engine/Binaries/Win64/x.dll"))
        assertEquals(InstallRecommendation.GameEngine.UNREAL, a.engine)
        assertFalse(a.joiPlayCapable)
    }

    @Test
    fun unknownNativeExeFallsBackToWinlator() {
        val a = InstallRecommendation.analyze(listOf("Game.exe", "data.bin", "readme.txt"))
        assertEquals(InstallRecommendation.GameEngine.NATIVE_OR_UNKNOWN, a.engine)
        assertEquals(InstallRecommendation.Runner.Winlator, a.recommended)
        assertFalse(a.joiPlayCapable)
    }

    @Test
    fun kirikiriRecommendsKirikiroid() {
        val a = InstallRecommendation.analyze(listOf("Game.exe", "data.xp3", "startup.tjs"))
        assertEquals(InstallRecommendation.GameEngine.KIRIKIRI, a.engine)
        assertEquals(InstallRecommendation.Runner.Kirikiroid, a.recommended)
        assertTrue(a.kirikiroidCapable)
        assertFalse(a.joiPlayCapable)
        assertTrue(a.reasons.any { it.contains("xp3", ignoreCase = true) })
    }

    @Test
    fun onlyKirikiriIsKirikiroidCapable() {
        assertFalse(InstallRecommendation.analyze(listOf("Game.exe", "renpy/main.py")).kirikiroidCapable)
        assertFalse(InstallRecommendation.analyze(listOf("Game.exe", "UnityPlayer.dll")).kirikiroidCapable)
    }
}
