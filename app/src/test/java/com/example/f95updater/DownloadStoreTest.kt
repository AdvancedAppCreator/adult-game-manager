package com.example.f95updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class DownloadStoreTest {
    @Test
    fun completedRecordIsMissingWhenDestinationWasDeleted() {
        val record = record(DownloadState.Completed, "C:\\Downloads\\game.zip")

        assertTrue(downloadFileIsMissing(record) { false })
        assertFalse(downloadFileIsMissing(record) { true })
    }

    @Test
    fun runningFailedAndCancelledRecordsAreNeverClassifiedAsMissingFiles() {
        listOf(
            DownloadState.Running,
            DownloadState.Finalizing,
            DownloadState.Failed,
            DownloadState.FinalizationFailed,
            DownloadState.Cancelled,
        ).forEach { state ->
            assertFalse(downloadFileIsMissing(record(state, null)) { false })
        }
    }

    @Test
    fun onlyDownloadingAndFinalizingStatesAreActive() {
        assertTrue(DownloadState.Running.isActive)
        assertTrue(DownloadState.Finalizing.isActive)
        assertFalse(DownloadState.Completed.isActive)
        assertFalse(DownloadState.Failed.isActive)
        assertFalse(DownloadState.FinalizationFailed.isActive)
        assertFalse(DownloadState.Cancelled.isActive)
    }

    @Test
    fun completedRecordWithoutDestinationIsMissing() {
        assertTrue(downloadFileIsMissing(record(DownloadState.Completed, null)) { true })
    }

    @Test
    fun retryRetainsNativeRequestMetadata() {
        val game = CatalogGame(
            thread_id = 123,
            title = "Game",
            source = SOURCE_F95ZONE,
            sourceId = "123",
        )
        val record = record(DownloadState.Failed, null).copy(
            catalogGame = game,
            userAgent = "Agent",
            contentDisposition = "attachment; filename=game.zip",
            mimeType = "application/zip",
            referrer = "https://example.test/game",
        )

        val request = record.retryRequest()
        assertEquals(record.url, request.url)
        assertEquals("Agent", request.userAgent)
        assertEquals("attachment; filename=game.zip", request.contentDisposition)
        assertEquals("application/zip", request.mimeType)
        assertEquals("https://example.test/game", request.referrer)
    }

    @Test
    fun waitingForNetworkExplainsAutomaticContinuation() {
        assertEquals(
            "Waiting for network; will continue automatically",
            downloadManagerReasonLabel(android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK),
        )
    }

    @Test
    fun pausedDownloadStatusIncludesReason() {
        assertEquals(
            "paused: Waiting for network; will continue automatically",
            downloadManagerStatusLabel(
                android.app.DownloadManager.STATUS_PAUSED,
                android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK,
            ),
        )
    }

    @Test
    fun pendingStatusReportsExactNativeStateAndElapsedWait() {
        assertEquals(
            "Queued — waiting to start for 2m 5s",
            downloadRuntimeStatusText(
                record(DownloadState.Running, null).copy(createdAt = 1_000L),
                DownloadManagerSnapshot(
                    status = android.app.DownloadManager.STATUS_PENDING,
                    reason = 0,
                    downloadedBytes = 0,
                    totalBytes = -1,
                ),
                queryCompleted = true,
                nowMs = 126_000L,
                pendingConstraints = PendingDownloadConstraints(),
            ),
        )
    }

    @Test
    fun pendingStatusReportsObservedConstraintWithoutInventingCausation() {
        assertEquals(
            "Queued — device thermal status is Severe for 2m 5s",
            downloadRuntimeStatusText(
                record(DownloadState.Running, null).copy(createdAt = 1_000L),
                DownloadManagerSnapshot(
                    status = android.app.DownloadManager.STATUS_PENDING,
                    reason = 0,
                    downloadedBytes = 0,
                    totalBytes = -1,
                ),
                queryCompleted = true,
                nowMs = 126_000L,
                pendingConstraints = PendingDownloadConstraints(
                    thermalStatus = android.os.PowerManager.THERMAL_STATUS_SEVERE,
                ),
            ),
        )
    }

    @Test
    fun pendingConstraintPrecedenceUsesValidatedNetworkFirst() {
        assertEquals(
            "Queued — no validated active network",
            pendingDownloadStatus(
                PendingDownloadConstraints(
                    thermalStatus = android.os.PowerManager.THERMAL_STATUS_SEVERE,
                    powerSaveMode = true,
                    restrictBackgroundStatus =
                        android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                    hasActiveNetwork = true,
                    hasValidatedNetwork = false,
                ),
            ),
        )
    }

    @Test
    fun pendingConstraintStatusesAreDirectlyObservableFacts() {
        assertEquals(
            "Queued — Data Saver restricts background data",
            pendingDownloadStatus(
                PendingDownloadConstraints(
                    restrictBackgroundStatus =
                        android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                ),
            ),
        )
        assertEquals(
            "Queued — Battery Saver is on",
            pendingDownloadStatus(PendingDownloadConstraints(powerSaveMode = true)),
        )
        assertEquals(null, pendingDownloadStatus(PendingDownloadConstraints()))
    }

    @Test
    fun olderPersistedRecordLoadsWithFinalizationDefaults() {
        val record = Json.decodeFromString<DownloadRecord>(
            """{"id":7,"url":"https://example.test/game.zip","fileName":"game.zip","state":"Running"}""",
        )

        assertEquals(null, record.targetPath)
        assertEquals(DownloadBackend.AndroidDownloadManager, record.backend)
        assertEquals(-1L, record.expectedBytes)
        assertEquals(-1L, record.totalBytes)
        assertEquals(0L, record.finalizedBytes)
    }

    @Test
    fun runningStatusReportsBytesAndPercentage() {
        assertEquals(
            "Downloading — 1.0 MB / 4.0 MB (25%)",
            downloadRuntimeStatusText(
                record(DownloadState.Running, null),
                DownloadManagerSnapshot(
                    status = android.app.DownloadManager.STATUS_RUNNING,
                    reason = 0,
                    downloadedBytes = 1_048_576,
                    totalBytes = 4_194_304,
                ),
                queryCompleted = true,
                nowMs = 0,
            ),
        )
    }

    @Test
    fun missingNativeRegistrationIsExplicit() {
        assertEquals(
            "Unavailable — no longer registered with Android Download Manager",
            downloadRuntimeStatusText(
                record(DownloadState.Running, null),
                snapshot = null,
                queryCompleted = true,
                nowMs = 0,
            ),
        )
    }

    @Test
    fun terminalStateWinsLateInitialInsertForSameAttempt() {
        val running = record(DownloadState.Running, null).copy(id = 7, createdAt = 100)
        val completed = running.copy(
            state = DownloadState.Completed,
            destPath = "C:\\Downloads\\game.zip",
        )

        assertEquals(listOf(completed), insertDownloadRecord(listOf(completed), running))
        assertEquals(listOf(completed), replaceDownloadRecord(listOf(completed), 6, running))
    }

    @Test
    fun newerAttemptReplacesStaleRecordWhenDownloadManagerReusesId() {
        val stale = record(DownloadState.Failed, null).copy(id = 7, createdAt = 100)
        val replacement = record(DownloadState.Running, null).copy(id = 7, createdAt = 200)

        assertEquals(listOf(replacement), insertDownloadRecord(listOf(stale), replacement))
        assertEquals(listOf(replacement), replaceDownloadRecord(listOf(stale), 6, replacement))
    }

    private fun record(state: DownloadState, path: String?) = DownloadRecord(
        id = 1,
        url = "https://example.test/game.zip",
        fileName = "game.zip",
        destPath = path,
        state = state,
    )
}
