package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledLibraryRefreshTest {
    @Test
    fun firstResumeIsConsumedByInitialComposition() {
        val gate = InitialResumeScanGate()
        gate.onInitialScanFinished(true)

        assertFalse(gate.shouldScan())
        assertTrue(gate.shouldScan())
        assertTrue(gate.shouldScan())
    }

    @Test
    fun resumeRetriesWhenInitialScanAlreadyFailed() {
        val gate = InitialResumeScanGate()
        gate.onInitialScanFinished(false)

        assertTrue(gate.shouldScan())
    }

    @Test
    fun resumeDuringInitialScanIsSuppressedButNextResumeRetriesAfterFailure() {
        val gate = InitialResumeScanGate()

        assertFalse(gate.shouldScan())
        gate.onInitialScanFinished(false)
        assertTrue(gate.shouldScan())
    }

    @Test
    fun missingWinlatorGamePreservesDurableRecoveryIdentity() {
        val refreshed = refreshManagedWinlatorBinding(
            binding = binding(),
            live = null,
            now = 200_000L,
        )

        assertEquals("win-1", refreshed.managedId)
        assertNull(refreshed.containerId)
        assertNull(refreshed.executableDosPath)
        assertEquals(WINLATOR_REGISTRATION_MISSING, refreshed.state)
        assertEquals("/games/Test/old.exe", refreshed.executablePath)
    }

    @Test
    fun recentlyCreatedWinlatorGameWaitsForProviderPropagation() {
        val refreshed = refreshManagedWinlatorBinding(
            binding = binding(metadata = mapOf("agmBoundAtMs" to "100000")),
            live = null,
            now = 150_000L,
        )

        assertEquals("win-1", refreshed.managedId)
        assertEquals("installing", refreshed.state)
    }

    @Test
    fun liveWinlatorGameRefreshesProviderOwnedFields() {
        val refreshed = refreshManagedWinlatorBinding(
            binding = binding(),
            live = InstalledApp(
                packageName = "winlator:win-1",
                label = "Test",
                versionName = "",
                versionCode = 0,
                source = AppSource.Winlator,
                winlatorGameId = "win-1",
                winlatorExecutablePath = "/games/Test/new.exe",
                winlatorExecutableDosPath = "D:\\Test\\new.exe",
                winlatorContainerId = 9,
                winlatorState = "ready",
                winlatorContainerShared = true,
                winlatorConfigSha256 = "abc",
            ),
        )

        assertEquals("/games/Test/new.exe", refreshed.executablePath)
        assertEquals("D:\\Test\\new.exe", refreshed.executableDosPath)
        assertEquals(9, refreshed.containerId)
        assertEquals("ready", refreshed.state)
        assertTrue(refreshed.containerShared)
        assertEquals("abc", refreshed.configSha256)
    }

    private fun binding(
        metadata: Map<String, String> = emptyMap(),
    ) = ManagedRunnerBinding.Winlator(
        managedId = "win-1",
        executablePath = "/games/Test/old.exe",
        executableDosPath = "D:\\Test\\old.exe",
        containerId = 4,
        state = "ready",
        metadata = metadata,
    )
}
