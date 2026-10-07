package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledSizeSortTest {
    @Test
    fun effectiveInstalledSizeUsesAndroidTotalSize() {
        val app = InstalledApp(
            packageName = "pkg.android",
            label = "Android Game",
            versionName = "1",
            versionCode = 1,
            apkSize = 10,
            dataSize = 20,
            cacheSize = 3,
        )

        assertEquals(33L, effectiveInstalledSize(app, JoiPlayScanner.SizeInfo(totalBytes = 999)))
    }

    @Test
    fun effectiveInstalledSizeUsesScannedJoiPlaySizeWhenAvailable() {
        val app = InstalledApp(
            packageName = "joiplay:Game",
            label = "JoiPlay Game",
            versionName = "",
            versionCode = 0,
            apkSize = 10,
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/Game/www",
            storageFolderName = "Game",
        )

        assertEquals(777L, effectiveInstalledSize(app, JoiPlayScanner.SizeInfo(totalBytes = 777)))
    }

    @Test
    fun effectiveInstalledSizeFallsBackToStoredJoiPlayTotal() {
        val app = InstalledApp(
            packageName = "joiplay:Game",
            label = "JoiPlay Game",
            versionName = "",
            versionCode = 0,
            apkSize = 123,
            source = AppSource.JoiPlay,
            storageFolderName = "Game",
        )

        assertEquals(123L, effectiveInstalledSize(app, null))
        assertEquals(123L, effectiveInstalledSize(app, JoiPlayScanner.SizeInfo(totalBytes = 0)))
    }

    @Test
    fun joiPlaySizeKeyUsesParentFolderForWrapperFolders() {
        val app = InstalledApp(
            packageName = "joiplay:Wrapped",
            label = "Wrapped",
            versionName = "",
            versionCode = 0,
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/Wrapped/www",
            storageFolderName = "www",
        )

        assertEquals("Wrapped", joiPlaySizeKey(app))
    }

    @Test
    fun effectiveInstalledTotalSizeUsesScannedJoiPlaySizes() {
        val android = InstalledApp(
            packageName = "pkg.android",
            label = "Android",
            versionName = "1",
            versionCode = 1,
            apkSize = 10,
            dataSize = 20,
        )
        val joiplay = InstalledApp(
            packageName = "joiplay:Wrapped",
            label = "Wrapped",
            versionName = "",
            versionCode = 0,
            apkSize = 1,
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/Wrapped/www",
            storageFolderName = "www",
        )

        assertEquals(
            807L,
            effectiveInstalledTotalSize(
                listOf(android, joiplay),
                mapOf("Wrapped" to JoiPlayScanner.SizeInfo(totalBytes = 777)),
            )
        )
    }
}
