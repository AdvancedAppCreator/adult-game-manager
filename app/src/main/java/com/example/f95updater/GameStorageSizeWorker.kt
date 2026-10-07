package com.example.f95updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps the persisted AGM-managed game-folder size cache current in the background.
 *
 * Uses the fast [FastFolderSizer] with mtime change-detection, so a pass over unchanged storage only
 * stats the top-level game folders (milliseconds) and re-sizes just the folders that are new or whose
 * mtime moved. The on-demand "refresh game storage sizes" action remains the full recompute escape
 * hatch for the deep-change case (a folder's mtime does not move when content buried below its
 * immediate children changes).
 */
class GameStorageSizeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runMutex.withLock {
        runCatching {
            val managedTargets = managedGameSizeTargets(
                ManagedGameStore(applicationContext).get().map(ManagedGame::toInstalledApp),
            )
            if (managedTargets.isNotEmpty()) {
                JoiPlayScanner.computeTargetSizes(
                    applicationContext,
                    managedTargets,
                    recompute = false,
                ) {}
            }
            AppLog.i(
                "GameSizeBg",
                "Background size refresh done (${managedTargets.size} AGM-managed targets)",
            )
            Result.success()
        }.getOrElse { error ->
            AppLog.e("GameSizeBg", "Background size refresh failed", error)
            Result.retry()
        }
    }

    companion object {
        // Serialize against the in-app foreground scan / other worker runs.
        private val runMutex = Mutex()
    }
}

object GameStorageSizeWork {
    private const val PERIODIC_WORK = "game-size-refresh-periodic"
    private const val IMMEDIATE_WORK = "game-size-refresh-immediate"

    // Legacy unique-work names from the JoiPlay-only worker (retired in favour of the combined one).
    private const val LEGACY_PERIODIC = "joiplay-size-refresh-periodic"
    private const val LEGACY_IMMEDIATE = "joiplay-size-refresh-immediate"

    /** Schedule the recurring background refresh. Idempotent (KEEP) — safe to call every launch. */
    fun configurePeriodic(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        // Retire the old JoiPlay-only worker so its removed class is never rescheduled.
        manager.cancelUniqueWork(LEGACY_PERIODIC)
        manager.cancelUniqueWork(LEGACY_IMMEDIATE)
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GameStorageSizeWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build(),
        )
    }

    /** Fire an immediate one-off refresh, e.g. right after a game install/uninstall. */
    fun enqueueImmediate(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<GameStorageSizeWorker>()
                .setConstraints(constraints())
                .build(),
        )
    }

    private fun constraints(): Constraints =
        Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()
}
