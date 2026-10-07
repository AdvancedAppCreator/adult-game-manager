package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinlatorRefreshPolicyTest {
    @Test
    fun launchAndGameExitDoNotTriggerCatalogWidePostInstallRefresh() {
        assertFalse(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.Launch))
        assertFalse(shouldRefreshAfterWinlatorOperation(null))
        assertFalse(shouldRefreshAfterWinlatorEvent("game_exited"))
        assertFalse(shouldRefreshAfterWinlatorEvent("install_progress"))
    }

    @Test
    fun recordAndInstallerStateChangesTriggerRefresh() {
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.CreatePortable))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.CreateInstaller))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.Configure))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.Delete))
        assertTrue(shouldRefreshAfterWinlatorEvent("install_completed"))
        assertTrue(shouldRefreshAfterWinlatorEvent("install_failed"))
        // Post-commit settings/config reconcile notification: AGM must refetch so a persisted
        // change (e.g. resolution) is reflected in an open config panel.
        assertTrue(shouldRefreshAfterWinlatorEvent("settings_changed"))
    }

    @Test
    fun onlyNewWinlatorGamesReceiveAutomaticCatalogMatching() {
        assertTrue(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.CreatePortable))
        assertTrue(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.CreateInstaller))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.Configure))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.RunInstaller))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.Delete))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.MoveToIsolated))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.Launch))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(null))
    }

    @Test
    fun gameExitedIsNormalWhenRuntimeReachedOrUnknown() {
        // Winlator 11.1-secure.23 sets game_exited success=true unconditionally; runtimeReached is
        // the trustworthy signal. Unknown (bare/old events) must stay a normal session end.
        assertEquals("Winlator game session ended.", winlatorGameExitedMessage(true, "ignored"))
        assertEquals("Winlator game session ended.", winlatorGameExitedMessage(null, "ignored"))
    }

    @Test
    fun gameExitedSurfacesLaunchFailureWhenRuntimeNotReached() {
        assertEquals(
            "Winlator could not start this game: Unable to generate runtime locale ja_JP.UTF-8",
            winlatorGameExitedMessage(false, "Unable to generate runtime locale ja_JP.UTF-8"),
        )
        assertEquals(
            "Winlator could not start this game. The runtime did not start.",
            winlatorGameExitedMessage(false, null),
        )
        assertEquals(
            "Winlator could not start this game. The runtime did not start.",
            winlatorGameExitedMessage(false, "   "),
        )
    }
}
