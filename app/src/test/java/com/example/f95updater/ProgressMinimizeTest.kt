package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMinimizeTest {

    private val extract = "Extract:game.zip"
    private val nextItem = "Extract:other.zip"

    @Test
    fun aStandaloneOperationResetsWhenItFinishes() {
        var state = ProgressMinimize.onActiveOperation(ProgressMinimizeState(), extract, null)
        state = ProgressMinimize.minimize(state, extract, null)
        assertTrue(state.minimized)
        assertFalse(state.batchScoped)

        state = ProgressMinimize.onActiveOperation(state, null, null)
        assertFalse("finishing resets the card", state.minimized)
    }

    @Test
    fun aStandaloneOperationResetsWhenAnotherOperationStarts() {
        var state = ProgressMinimize.minimize(ProgressMinimizeState(), extract, null)
        state = ProgressMinimize.onActiveOperation(state, "Patch:patch.rar", null)
        assertFalse(state.minimized)
        assertEquals("Patch:patch.rar", state.operationKey)
    }

    @Test
    fun minimizingOnceInABulkRunCoversTheWholeRemainingBatch() {
        var state = ProgressMinimize.onActiveOperation(ProgressMinimizeState(), extract, 42L)
        state = ProgressMinimize.minimize(state, extract, 42L)
        assertTrue(state.minimized)
        assertTrue(state.batchScoped)

        // Item 1 finishes, item 2 starts: the dialog must not pop back up.
        state = ProgressMinimize.onActiveOperation(state, null, 42L)
        assertTrue(state.minimized)
        state = ProgressMinimize.onActiveOperation(state, nextItem, 42L)
        assertTrue(state.minimized)
        assertEquals(nextItem, state.operationKey)
    }

    @Test
    fun aFinishedOrReplacedBatchResetsTheCard() {
        var state = ProgressMinimize.minimize(ProgressMinimizeState(), extract, 42L)
        state = ProgressMinimize.onActiveOperation(state, extract, null)
        assertFalse("batch ended", state.minimized)

        state = ProgressMinimize.minimize(state, extract, 42L)
        state = ProgressMinimize.onActiveOperation(state, extract, 43L)
        assertFalse("new batch", state.minimized)
    }

    @Test
    fun restoringDropsBatchScopeSoLaterItemsShowTheDialogAgain() {
        var state = ProgressMinimize.minimize(ProgressMinimizeState(), extract, 42L)
        state = ProgressMinimize.restore(state)
        assertFalse(state.minimized)
        assertFalse(state.batchScoped)

        state = ProgressMinimize.onActiveOperation(state, nextItem, 42L)
        assertFalse(state.minimized)
    }

    @Test
    fun minimizingWithNothingRunningIsANoOp() {
        val state = ProgressMinimize.minimize(ProgressMinimizeState(), null, null)
        assertFalse(state.minimized)
    }

    @Test
    fun theCardLabelsEveryCoveredOperation() {
        val progress = ArchiveExtractor.Progress(50L, 100L, 1, 2, "entry")
        assertEquals(
            "Extracting",
            MinimizedProgress(
                ProgressOperationKind.Extract, "a.zip", JoiPlayExtractFlow.Phase.Extracting, progress,
            ).label,
        )
        assertEquals(
            "Updating game",
            MinimizedProgress(
                ProgressOperationKind.Upgrade, "a.zip", JoiPlayExtractFlow.Phase.Extracting, progress,
            ).label,
        )
        assertEquals(
            "Installing patch",
            MinimizedProgress(
                ProgressOperationKind.Patch, "a.zip", JoiPlayExtractFlow.Phase.Extracting, progress,
            ).label,
        )
        assertEquals(
            "Extract:a.zip",
            MinimizedProgress(
                ProgressOperationKind.Extract, "a.zip", JoiPlayExtractFlow.Phase.Extracting, progress,
            ).key,
        )
    }
}
