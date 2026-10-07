package com.example.f95updater

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExternalBrowserDownloadAndroidTest {
    @Test
    fun detectsOnlyCompleteStableNewDownload() {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "agm-external-test-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        try {
            val existing = File(folder, "game.zip").apply { writeBytes(ByteArray(8)) }
            val createdAt = System.currentTimeMillis()
            val record = DownloadRecord(
                id = -createdAt,
                url = "https://example.test/game.zip",
                fileName = "game.zip",
                backend = DownloadBackend.ExternalBrowser,
                state = DownloadState.Running,
                createdAt = createdAt,
                expectedBytes = 16,
                externalSearchRoots = listOf(folder.absolutePath),
                externalBaseline = snapshotExternalDownloadFiles(listOf(folder.absolutePath)),
            )
            existing.writeBytes(ByteArray(9))
            assertNull(
                selectExternalDownloadCandidate(
                    record,
                    scanExternalDownloadFiles(record.externalSearchRoots),
                ),
            )

            val downloaded = File(folder, "game (1).zip").apply { writeBytes(ByteArray(16)) }
            val candidate = selectExternalDownloadCandidate(
                record,
                scanExternalDownloadFiles(record.externalSearchRoots),
            )

            assertEquals(downloaded.absolutePath, candidate?.path)
            val first = updateExternalCandidateObservation(null, candidate)
            val second = updateExternalCandidateObservation(first, candidate)
            assertEquals(2, second?.confirmations)
            assertTrue(downloaded.isFile)
        } finally {
            folder.deleteRecursively()
        }
    }
}
