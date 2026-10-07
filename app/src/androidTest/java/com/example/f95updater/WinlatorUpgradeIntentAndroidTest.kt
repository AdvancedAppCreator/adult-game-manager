package com.example.f95updater

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WinlatorUpgradeIntentAndroidTest {
    @Test
    fun configurePortableLocationCarriesTheNativeRepathExtras() {
        val intent = WinlatorApi.configurePortableLocation(
            gameId = "winlator-game-1",
            gamePath = "/storage/emulated/0/Games/New/bin",
            executablePath = "/storage/emulated/0/Games/New/bin/Game.exe",
        )

        assertEquals(WinlatorApi.ACTION_CONFIGURE_GAME, intent.action)
        assertEquals(WinlatorApi.PACKAGE, intent.component?.packageName)
        assertEquals(WinlatorApi.ACTIVITY, intent.component?.className)
        assertEquals("winlator-game-1", intent.getStringExtra("game_id"))
        assertEquals("/storage/emulated/0/Games/New/bin", intent.getStringExtra("game_path"))
        assertEquals(
            "/storage/emulated/0/Games/New/bin/Game.exe",
            intent.getStringExtra("executable_path"),
        )
    }
}
