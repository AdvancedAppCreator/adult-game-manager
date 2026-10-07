package com.example.f95updater

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemePrefsTest {
    @Test
    fun oledModeUsesTrueBlackForEverySurfaceRole() {
        val scheme = appColorScheme(AppThemeMode.Oled, systemDark = false)

        listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceDim,
            scheme.surfaceBright,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceVariant,
            scheme.scrim,
        ).forEach { assertEquals(Color.Black, it) }
        assertEquals(Color.Transparent, scheme.surfaceTint)
    }

    @Test
    fun darkModeKeepsTheStandardMaterialDarkSurfaces() {
        val scheme = appColorScheme(AppThemeMode.Dark, systemDark = false)

        assertNotEquals(Color.Black, scheme.surface)
    }

    @Test
    fun systemModeStillFollowsTheDeviceTheme() {
        assertNotEquals(
            appColorScheme(AppThemeMode.System, systemDark = false),
            appColorScheme(AppThemeMode.System, systemDark = true),
        )
    }
}
