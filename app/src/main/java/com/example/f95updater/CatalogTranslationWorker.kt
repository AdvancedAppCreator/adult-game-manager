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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class CatalogTranslationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runMutex.withLock {
        val settings = CatalogPrefs.load(applicationContext)
        if (!settings.translateTitles) return@withLock Result.success()

        val workStartedAt = System.currentTimeMillis()
        CatalogMemoryDiagnostics.log(phase = "translation_worker_start")
        CatalogRepository(applicationContext).use { catalog ->
            val loadStartedAt = System.currentTimeMillis()
            val database = catalog.prepareCatalogDatabase()
            CatalogMemoryDiagnostics.log(
                phase = "translation_entries_ready",
                startedAtMs = loadStartedAt,
                detail = "entries=${database.totalCount}",
            )
            if (database.totalCount == 0) return@withLock Result.success()

            val state = backgroundState(applicationContext)
            val target = settings.translationTarget
            val generation = catalogTranslationGeneration(
                registry = catalog.sourceCatalogIndex(),
                entryCount = database.totalCount,
            )
            val storedTarget = state.getString(KEY_TARGET, null)
            val storedGeneration = state.getString(KEY_GENERATION, null)
            val samePass = storedTarget == target && storedGeneration == generation
            if (samePass && state.getBoolean(KEY_COMPLETED, false)) {
                return@withLock Result.success()
            }
            var cursor = if (samePass) state.getInt(KEY_CURSOR, 0) else 0
            if (cursor !in 0 until database.totalCount) cursor = 0

            val startedAt = System.currentTimeMillis()
            var attempted = 0
            var modelMisses = 0
            var failures = 0
            while (cursor < database.totalCount &&
                attempted < MAX_ENTRIES_PER_RUN &&
                System.currentTimeMillis() - startedAt < MAX_RUN_TIME_MS
            ) {
                currentCoroutineContext().ensureActive()
                val page = CatalogRowsDatabaseStore.translationEntries(
                    file = catalog.catalogRowsDatabaseFile,
                    offset = cursor,
                    limit = minOf(TRANSLATION_PAGE_SIZE, database.totalCount - cursor),
                )
                check(page.isNotEmpty()) {
                    "Catalog translation page was empty at $cursor/${database.totalCount}"
                }
                for (entry in page) {
                    if (attempted >= MAX_ENTRIES_PER_RUN ||
                        System.currentTimeMillis() - startedAt >= MAX_RUN_TIME_MS
                    ) {
                        break
                    }
                    currentCoroutineContext().ensureActive()
                    val result = CatalogTitleTranslator.translateIfNeeded(
                        context = applicationContext,
                        entryKey = "${entry.source}:${entry.sourceId}",
                        text = entry.title,
                        targetLanguageTag = target,
                        allowModelDownload = false,
                        priority = TranslationPriority.Background,
                    )
                    result.exceptionOrNull()?.let { error ->
                        when (error) {
                            is CatalogTitleTranslator.ModelDownloadRequiredException -> modelMisses++
                            else -> {
                                failures++
                                if (failures == 1) {
                                    AppLog.w(
                                        "CatalogTranslateBg",
                                        "Background translation failed at ${entry.source}:${entry.sourceId}",
                                        error,
                                    )
                                }
                            }
                        }
                        Unit
                    }
                    cursor++
                    attempted++
                    if (attempted % CHECKPOINT_INTERVAL == 0) {
                        state.edit()
                            .putString(KEY_TARGET, target)
                            .putString(KEY_GENERATION, generation)
                            .putInt(KEY_CURSOR, cursor)
                            .putBoolean(KEY_COMPLETED, false)
                            .apply()
                    }
                    if (modelMisses >= MAX_MODEL_MISSES || failures >= MAX_FAILURES) break
                }
                if (modelMisses >= MAX_MODEL_MISSES || failures >= MAX_FAILURES) break
            }

            val completed = cursor >= database.totalCount
            val nextCursor = if (completed) 0 else cursor
            state.edit()
                .putString(KEY_TARGET, target)
                .putString(KEY_GENERATION, generation)
                .putInt(KEY_CURSOR, nextCursor)
                .putBoolean(KEY_COMPLETED, completed)
                .apply()
            AppLog.i(
                "CatalogTranslateBg",
                "Background pass target=$target attempted=$attempted next=$nextCursor/" +
                    "${database.totalCount} modelMisses=$modelMisses failures=$failures",
            )
            if (attempted > 0) {
                try {
                    catalog.foldTranslatedTitles()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    AppLog.e("CatalogTranslateBg", "Could not update translated catalog aliases", error)
                    return@withLock Result.retry()
                }
            }
            CatalogMemoryDiagnostics.log(
                phase = "translation_worker_complete",
                startedAtMs = workStartedAt,
                detail = "attempted=$attempted entries=${database.totalCount}",
            )
            if (!completed && modelMisses == 0 && failures == 0) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val MAX_ENTRIES_PER_RUN = 100_000
        private const val MAX_MODEL_MISSES = 50
        private const val MAX_FAILURES = 10
        private const val MAX_RUN_TIME_MS = 8 * 60 * 1000L
        private const val CHECKPOINT_INTERVAL = 100
        private const val TRANSLATION_PAGE_SIZE = 256
        private val runMutex = Mutex()
    }
}

object CatalogTranslationWork {
    private const val IMMEDIATE_WORK = "catalog-title-translation-immediate"
    private const val PERIODIC_WORK = "catalog-title-translation-periodic"

    fun configure(
        context: Context,
        enabled: Boolean,
        targetLanguage: String,
        resetCursor: Boolean,
    ) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        backgroundState(appContext).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            manager.cancelUniqueWork(IMMEDIATE_WORK)
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }

        val state = backgroundState(appContext)
        if (resetCursor || state.getString(KEY_TARGET, null) != targetLanguage) {
            state.edit()
                .putString(KEY_TARGET, targetLanguage)
                .putInt(KEY_CURSOR, 0)
                .putBoolean(KEY_COMPLETED, false)
                .apply()
        }

        enqueueImmediate(manager, replace = resetCursor)
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CatalogTranslationWorker>(6, TimeUnit.HOURS)
                .setConstraints(workConstraints())
                .build(),
        )
    }

    fun onModelReady(context: Context, targetLanguage: String) {
        val appContext = context.applicationContext
        val state = backgroundState(appContext)
        if (!state.getBoolean(KEY_ENABLED, false) ||
            state.getString(KEY_TARGET, null) != targetLanguage
        ) {
            return
        }
        state.edit().putInt(KEY_CURSOR, 0).apply()
        state.edit().putBoolean(KEY_COMPLETED, false).apply()
        enqueueImmediate(WorkManager.getInstance(appContext), replace = true)
    }

    suspend fun scheduleAfterCatalogSync(context: Context) {
        val settings = CatalogPrefs.load(context.applicationContext)
        configure(
            context = context,
            enabled = settings.translateTitles,
            targetLanguage = settings.translationTarget,
            resetCursor = false,
        )
    }

    private fun enqueueImmediate(manager: WorkManager, replace: Boolean) {
        manager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CatalogTranslationWorker>()
                .setConstraints(workConstraints())
                .build(),
        )
    }

    private fun workConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
}

private const val BACKGROUND_PREFS = "catalog_translation_background"
private const val KEY_TARGET = "target"
private const val KEY_CURSOR = "cursor"
private const val KEY_ENABLED = "enabled"
private const val KEY_GENERATION = "generation"
private const val KEY_COMPLETED = "completed"

private fun backgroundState(context: Context) =
    context.applicationContext.getSharedPreferences(BACKGROUND_PREFS, Context.MODE_PRIVATE)

internal fun catalogTranslationGeneration(
    registry: CatalogSourceRegistry?,
    entryCount: Int,
): String = buildString {
    append("title-alias-v1|")
    append(registry?.generatedAt.orEmpty())
    append('|')
    append(entryCount)
    registry?.catalogs
        ?.sortedBy { it.id }
        ?.forEach { catalog ->
            append('|')
            append(catalog.id)
            append(':')
            append(catalog.generatedAt.orEmpty())
            append(':')
            append(catalog.count ?: -1)
        }
}
