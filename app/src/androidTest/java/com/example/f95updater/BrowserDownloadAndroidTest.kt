package com.example.f95updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserDownloadAndroidTest {
    @Test
    fun downloadManagerWritesDestinationLocalStageAndFinalizes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Environment.isExternalStorageManager())
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "agm-native-test-${UUID.randomUUID()}",
        )
        val target = BrowserDownloads.reserveTargetFile(folder, "version.json")
        val staged = File(folder, ".agm-${UUID.randomUUID()}.part")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(
            DownloadManager.Request(
                Uri.parse(
                    "https://github.com/AdvancedAppCreator/" +
                        "adult-game-manager-releases/releases/download/app/version.json",
                ),
            ).setDestinationUri(Uri.fromFile(staged)),
        )
        try {
            val deadline = System.currentTimeMillis() + 60_000L
            var snapshot: DownloadManagerSnapshot?
            do {
                Thread.sleep(250)
                snapshot = queryDownloadManagerSnapshots(manager, listOf(id))[id]
            } while (
                snapshot != null &&
                snapshot.status != DownloadManager.STATUS_SUCCESSFUL &&
                snapshot.status != DownloadManager.STATUS_FAILED &&
                System.currentTimeMillis() < deadline
            )
            assertEquals(DownloadManager.STATUS_SUCCESSFUL, snapshot?.status)
            assertTrue(staged.length() > 0L)

            val finalized = BrowserDownloads.finalizeDownloadedFile(
                staged,
                target,
                snapshot!!.downloadedBytes,
            )

            assertEquals(target, finalized)
            assertTrue(!staged.exists())
            assertTrue(target.readText().contains("\"versionName\""))
        } finally {
            manager.remove(id)
            folder.deleteRecursively()
        }
    }

    @Test
    fun pendingConstraintQueryUsesPublicAndroidApis() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val constraints = queryPendingDownloadConstraints(context)

        assertTrue(constraints.powerSaveMode != null)
        assertTrue(constraints.restrictBackgroundStatus != null)
        assertTrue(constraints.thermalStatus != null)
        assertTrue(constraints.hasActiveNetwork != null)
    }
}
