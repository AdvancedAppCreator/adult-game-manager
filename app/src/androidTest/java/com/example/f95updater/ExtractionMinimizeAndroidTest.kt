package com.example.f95updater

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimizing a progress dialog is a UI-only change: the extraction coroutine keeps its job, keeps
 * writing and stays cancellable. These tests run a real extraction on-device and assert exactly
 * that, plus the batch-scoped behaviour of the shared minimize state.
 */
@RunWith(AndroidJUnit4::class)
class ExtractionMinimizeAndroidTest {

    private lateinit var context: Context
    private lateinit var work: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "minimize-test").apply {
            deleteRecursively()
            mkdirs()
        }
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        work.deleteRecursively()
    }

    private fun archive(name: String, entries: Int = 8, entryBytes: Int = 1024 * 1024): File {
        val file = File(work, name)
        val random = Random(name.hashCode().toLong())
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            repeat(entries) { index ->
                zip.putNextEntry(ZipEntry("Game/data/asset$index.bin"))
                val block = ByteArray(64 * 1024)
                var written = 0
                while (written < entryBytes) {
                    random.nextBytes(block)
                    zip.write(block)
                    written += block.size
                }
                zip.closeEntry()
            }
        }
        return file
    }

    private fun destination(name: String): File = File(work, name).apply { mkdirs() }

    private fun start(flow: JoiPlayExtractFlow, source: File, dest: File) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flow.start(
                archiveUri = Uri.fromFile(source),
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(dest),
            )
        }
    }

    private fun waitUntil(timeoutMs: Long = 120_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    private fun operationKey(flow: JoiPlayExtractFlow): String? =
        flow.archiveName?.takeIf { flow.inProgress }?.let { "${ProgressOperationKind.Extract}:$it" }

    @Test
    fun extractionKeepsRunningAndFinishesWhileTheDialogIsMinimized() {
        val flow = JoiPlayExtractFlow(context, scope)
        val source = archive("standalone.zip")
        start(flow, source, destination("standalone-out"))

        assertTrue("the flow engages before the dialog is minimized", flow.inProgress)
        val key = operationKey(flow)
        assertNotNull(key)

        var minimize = ProgressMinimize.onActiveOperation(ProgressMinimizeState(), key, null)
        minimize = ProgressMinimize.minimize(minimize, key, null)
        assertTrue(minimize.minimized)

        // The dialog is hidden for the whole extraction; nothing else touches the flow.
        var sawProgressWhileMinimized = false
        val finished = waitUntil {
            if (flow.inProgress) {
                minimize = ProgressMinimize.onActiveOperation(minimize, operationKey(flow), null)
                assertTrue("stays minimized while the same operation runs", minimize.minimized)
                if ((flow.progress?.bytesWritten ?: 0L) > 0L) sawProgressWhileMinimized = true
            }
            flow.extractedRoot != null || flow.errorMessage != null
        }

        assertTrue("extraction completed while minimized", finished)
        assertNull(flow.errorMessage)
        assertTrue("progress kept updating while minimized", sawProgressWhileMinimized)
        val root = flow.extractedRoot as ArchiveExtractor.ExtractRoot.FileRoot
        assertTrue(File(root.file, "data/asset0.bin").isFile)

        // The operation ended, so the card resets and the result dialog is what the user sees.
        minimize = ProgressMinimize.onActiveOperation(minimize, operationKey(flow), null)
        assertFalse(minimize.minimized)
        assertNotNull("the result still surfaces after minimize", flow.extractedRoot)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { flow.acknowledgeResult() }
    }

    @Test
    fun restoringMidExtractionShowsTheDialogAgainWithoutDisturbingTheJob() {
        val flow = JoiPlayExtractFlow(context, scope)
        start(flow, archive("restore.zip"), destination("restore-out"))

        val key = operationKey(flow)
        var minimize = ProgressMinimize.minimize(ProgressMinimizeState(), key, null)
        assertTrue(minimize.minimized)
        minimize = ProgressMinimize.restore(minimize)
        assertFalse(minimize.minimized)

        assertTrue(waitUntil { flow.extractedRoot != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)
        assertNotNull(flow.extractedRoot)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { flow.acknowledgeResult() }
    }

    @Test
    fun cancellingFromTheMinimizedCardStillStopsTheExtraction() {
        val flow = JoiPlayExtractFlow(context, scope)
        start(flow, archive("cancel.zip", entries = 24), destination("cancel-out"))

        val key = operationKey(flow)
        val minimize = ProgressMinimize.minimize(ProgressMinimizeState(), key, null)
        assertTrue(minimize.minimized)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { flow.cancelInProgress() }
        assertTrue("cancellation settles the flow", waitUntil(30_000) { !flow.inProgress })
        assertNull(flow.extractedRoot)
    }

    @Test
    fun minimizingOncePersistsAcrossConsecutiveBulkItems() {
        val batchId = 4242L
        val flow = JoiPlayExtractFlow(context, scope)

        start(flow, archive("bulk-1.zip", entries = 4), destination("bulk-1-out"))
        val firstKey = operationKey(flow)
        var minimize = ProgressMinimize.onActiveOperation(ProgressMinimizeState(), firstKey, batchId)
        minimize = ProgressMinimize.minimize(minimize, firstKey, batchId)
        assertTrue(minimize.minimized)
        assertTrue(minimize.batchScoped)

        assertTrue(waitUntil { flow.extractedRoot != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { flow.acknowledgeResult() }

        // Item 1 settled: the batch is still running, so the card must not pop back into a dialog.
        minimize = ProgressMinimize.onActiveOperation(minimize, operationKey(flow), batchId)
        assertTrue("still minimized between items", minimize.minimized)

        start(flow, archive("bulk-2.zip", entries = 4), destination("bulk-2-out"))
        val secondKey = operationKey(flow)
        assertNotNull(secondKey)
        minimize = ProgressMinimize.onActiveOperation(minimize, secondKey, batchId)
        assertTrue("item 2 stays minimized", minimize.minimized)
        assertTrue(waitUntil { flow.extractedRoot != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)
        assertNotNull(flow.extractedRoot)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { flow.acknowledgeResult() }

        // Batch finished: the next standalone operation opens its dialog normally again.
        minimize = ProgressMinimize.onActiveOperation(minimize, null, null)
        assertFalse(minimize.minimized)
        assertFalse(minimize.batchScoped)
    }

    /**
     * The invariant that makes minimizing safe: hiding the dialog changes nothing about ownership.
     * Every re-entry is refused identically whether the operation's dialog is on screen, behind the
     * compact card, or restored again.
     */
    @Test
    fun minimizingNeverWidensWhatAgmAcceptsWhileAnOperationRuns() {
        val flow = JoiPlayExtractFlow(context, scope)
        start(flow, archive("invariant.zip", entries = 12), destination("invariant-out"))
        assertTrue(flow.inProgress)

        fun refusalNow(): String? = InstallOperationGuard.refuse(
            flow.activeOperation,
            InstallOperationKind.Extract,
        )

        val whileShown = refusalNow()
        assertNotNull("a running extraction owns the pipeline", whileShown)

        var minimize = ProgressMinimize.minimize(ProgressMinimizeState(), operationKey(flow), null)
        assertTrue("a running operation can always be minimized", minimize.minimized)
        assertEquals("minimizing changes no refusal", whileShown, refusalNow())

        minimize = ProgressMinimize.restore(minimize)
        assertFalse(minimize.minimized)
        assertEquals("restoring changes no refusal", whileShown, refusalNow())

        // And the refusal is real: a second start is rejected while minimized.
        minimize = ProgressMinimize.minimize(minimize, operationKey(flow), null)
        val second = archive("invariant-2.zip", entries = 1, entryBytes = 1024)
        var refused: InstallStartOutcome? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            refused = flow.start(
                archiveUri = Uri.fromFile(second),
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination("invariant-2-out")),
            )
        }
        assertNotNull(refused!!.refusalOrNull)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { flow.cancelInProgress() }
        assertTrue(waitUntil(30_000) { !flow.inProgress })
    }
}
