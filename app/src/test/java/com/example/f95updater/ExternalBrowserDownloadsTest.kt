package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBrowserDownloadsTest {
    @Test
    fun collisionSuffixMatchesExpectedFilename() {
        assertTrue(externalDownloadNamesMatch("game.zip", "game (2).zip"))
        assertTrue(externalDownloadNamesMatch("GAME.ZIP", "game.zip"))
    }

    @Test
    fun selectsOnlyNewStableMetadataMatch() {
        val record = record().copy(
            expectedBytes = 200,
            externalBaseline = listOf(ExternalFileSnapshot("C:\\Download\\old.zip", 200, 1_000)),
        )
        val selected = selectExternalDownloadCandidate(
            record,
            listOf(
                ExternalDownloadCandidate("C:\\Download\\old.zip", "old.zip", 200, 1_000),
                ExternalDownloadCandidate("C:\\Download\\game (1).zip", "game (1).zip", 200, 2_000),
            ),
        )

        assertEquals("C:\\Download\\game (1).zip", selected?.path)
    }

    @Test
    fun rejectsWrongSizeAndAmbiguousMatches() {
        val record = record().copy(expectedBytes = 200)
        assertNull(
            selectExternalDownloadCandidate(
                record,
                listOf(ExternalDownloadCandidate("a", "game.zip", 199, 2_000)),
            ),
        )
        assertNull(
            selectExternalDownloadCandidate(
                record,
                listOf(
                    ExternalDownloadCandidate("a", "game.zip", 200, 2_000),
                    ExternalDownloadCandidate("b", "game (1).zip", 200, 2_000),
                ),
            ),
        )
    }

    @Test
    fun unknownExpectedSizeIsNeverAutoCompleted() {
        assertNull(
            selectExternalDownloadCandidate(
                record(),
                listOf(ExternalDownloadCandidate("a", "game.zip", 200, 2_000)),
            ),
        )
    }

    @Test
    fun requiresTwoUnchangedObservations() {
        val candidate = ExternalDownloadCandidate("a", "game.zip", 200, 2_000)
        val first = updateExternalCandidateObservation(null, candidate)
        val second = updateExternalCandidateObservation(first, candidate)

        assertEquals(1, first?.confirmations)
        assertEquals(2, second?.confirmations)
        assertNull(updateExternalCandidateObservation(second, null))
    }

    @Test
    fun externalIdsAreNegativeAndCollisionSafe() {
        assertEquals(-1_001L, newExternalDownloadId(1_000, listOf(-1_000L)))
    }

    private fun record() = DownloadRecord(
        id = -1,
        url = "https://example.test/game.zip",
        fileName = "game.zip",
        backend = DownloadBackend.ExternalBrowser,
        state = DownloadState.Running,
        createdAt = 2_000,
    )
}
