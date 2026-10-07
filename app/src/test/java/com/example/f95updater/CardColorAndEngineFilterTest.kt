package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardColorAndEngineFilterTest {
    @Test
    fun normalizesSixDigitCardColors() {
        assertEquals("#A1B2C3", normalizeCardColor(" a1b2c3 "))
        assertEquals("#FFFFFF", normalizeCardColor("#ffffff"))
        assertNull(normalizeCardColor("#12345"))
        assertNull(normalizeCardColor("#GG0000"))
    }

    @Test
    fun engineFilterUsesEnabledManagedBindings() {
        val app = InstalledApp(
            packageName = "managed:test",
            label = "Test",
            versionName = "",
            versionCode = 0,
            source = AppSource.Managed,
            managedDefaultRunner = ManagedRunnerKind.JoiPlay,
            managedRunnerBindings = listOf(
                ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "game.py"),
                ManagedRunnerBinding.Winlator(
                    enabled = false,
                    managedId = "win-id",
                    executablePath = "/games/Test/Game.exe",
                ),
            ),
        )

        assertTrue(matchesEngineFilter(app, AppSource.JoiPlay))
        assertFalse(matchesEngineFilter(app, AppSource.Winlator))
        assertFalse(matchesEngineFilter(app, AppSource.Android))
    }

    @Test
    fun engineApplyIsEnabledOnlyForARealChange() {
        val original = setOf(ManagedRunnerKind.JoiPlay)
        assertFalse(
            managedEngineChoicesChanged(
                original,
                ManagedRunnerKind.JoiPlay,
                original,
                ManagedRunnerKind.JoiPlay,
            ),
        )
        assertTrue(
            managedEngineChoicesChanged(
                original,
                ManagedRunnerKind.JoiPlay,
                original + ManagedRunnerKind.Winlator,
                ManagedRunnerKind.JoiPlay,
            ),
        )
        assertTrue(
            managedEngineChoicesChanged(
                original + ManagedRunnerKind.Winlator,
                ManagedRunnerKind.JoiPlay,
                original + ManagedRunnerKind.Winlator,
                ManagedRunnerKind.Winlator,
            ),
        )
    }
}
