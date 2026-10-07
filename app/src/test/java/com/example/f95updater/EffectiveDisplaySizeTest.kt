package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins card display-size selection for legacy rows and unified managed folders. */
class EffectiveDisplaySizeTest {

    private fun app(
        source: AppSource,
        apkSize: Long = 0L,
        dataSize: Long = 0L,
        containerBytes: Long? = null,
    ) = InstalledApp(
        packageName = "pkg.test",
        label = "Test",
        versionName = "1",
        versionCode = 1,
        source = source,
        apkSize = apkSize,
        dataSize = dataSize,
        winlatorContainerAllocatedSizeBytes = containerBytes,
    )

    @Test
    fun winlatorUsesProviderContainerSize() {
        val a = app(AppSource.Winlator, containerBytes = 5_000L)
        assertEquals(5_000L, effectiveDisplaySize(a, null))
    }

    @Test
    fun winlatorFallsBackToZeroWhenNothingKnown() {
        val a = app(AppSource.Winlator, containerBytes = null)
        assertEquals(0L, effectiveDisplaySize(a, null))
    }

    @Test
    fun nonWinlatorDelegatesToEffectiveInstalledSize() {
        val android = app(AppSource.Android, apkSize = 10L, dataSize = 5L)
        assertEquals(15L, effectiveDisplaySize(android, null))

        val joi = app(AppSource.JoiPlay)
        assertEquals(
            777L,
            effectiveDisplaySize(joi, JoiPlayScanner.SizeInfo(totalBytes = 777L)),
        )
    }

    @Test
    fun managedWinlatorFallsBackToContainerWhenFolderSizeIsUnavailable() {
        val managed = app(AppSource.Managed, containerBytes = 8_000L).copy(
            managedRunnerBindings = listOf(
                ManagedRunnerBinding.Winlator(
                    managedId = "win-id",
                    executablePath = "/storage/emulated/0/Games/Test/Game.exe",
                ),
            ),
        )

        assertEquals(8_000L, effectiveDisplaySize(managed, null))
        assertEquals(
            4_000L,
            effectiveDisplaySize(managed, JoiPlayScanner.SizeInfo(totalBytes = 4_000L)),
        )
    }
}
