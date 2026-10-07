package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reclaim primitives for the storage dashboard. Everything here targets storage AGM itself owns:
 *   • JoiPlay update rollback folders (`<game>.bak-*`) beyond the newest [DEFAULT_BACKUPS_TO_KEEP].
 *   • AGM's own cache directories (temporary extraction scratch, thumbnails, decoded backups).
 * Winlator container caches are intentionally NOT touched — AGM has no correct native API for that.
 */
object HousekeepingManager {
    const val DEFAULT_BACKUPS_TO_KEEP = 2

    data class GameBackups(val app: InstalledApp, val prunable: List<File>, val prunableBytes: Long)

    data class BackupReclaim(
        val perGame: List<GameBackups>,
        val prunableBytes: Long,
        val prunableFolders: Int,
    )

    data class PruneResult(val deletedFolders: Int, val freedBytes: Long, val failed: Int)

    /** Scan every JoiPlay game for rollback folders beyond [keep]; read-only. */
    suspend fun scanPrunableBackups(
        apps: List<InstalledApp>,
        keep: Int = DEFAULT_BACKUPS_TO_KEEP,
    ): BackupReclaim = withContext(Dispatchers.IO) {
        val perGame = ArrayList<GameBackups>()
        var bytes = 0L
        var folders = 0
        for (app in apps) {
            if (app.source != AppSource.JoiPlay && app.source != AppSource.Managed) continue
            val info = JoiPlayBackupManager.findBackup(app) ?: continue
            val prunable = JoiPlayBackupManager.prunableBackups(info, keep)
            if (prunable.isEmpty()) continue
            val size = JoiPlayBackupManager.sizeOfFolders(prunable)
            perGame.add(GameBackups(app, prunable, size))
            bytes += size
            folders += prunable.size
        }
        BackupReclaim(perGame.sortedByDescending { it.prunableBytes }, bytes, folders)
    }

    /** Delete rollback folders beyond [keep] for every JoiPlay game. */
    suspend fun pruneBackups(
        apps: List<InstalledApp>,
        keep: Int = DEFAULT_BACKUPS_TO_KEEP,
    ): PruneResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var freed = 0L
        var failed = 0
        for (app in apps) {
            if (app.source != AppSource.JoiPlay && app.source != AppSource.Managed) continue
            val info = JoiPlayBackupManager.findBackup(app) ?: continue
            val prunable = JoiPlayBackupManager.prunableBackups(info, keep)
            for (folder in prunable) {
                val size = dirSize(folder)
                if (folder.deleteRecursively()) {
                    deleted++
                    freed += size
                } else {
                    failed++
                    AppLog.w("Housekeeping", "Could not delete backup ${folder.absolutePath}")
                }
            }
        }
        PruneResult(deleted, freed, failed)
    }

    /** AGM-owned cache directories that hold only regenerable temporary data. */
    private fun cacheDirs(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir)

    /** Total bytes currently held in AGM's cache directories; read-only. */
    suspend fun scanCache(context: Context): Long = withContext(Dispatchers.IO) {
        cacheDirs(context.applicationContext).sumOf { dirSize(it) }
    }

    /** Delete the contents of AGM's cache directories (not the directories themselves). */
    suspend fun clearCache(context: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (dir in cacheDirs(context.applicationContext)) {
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val size = dirSize(child)
                if (child.deleteRecursively()) freed += size
                else AppLog.w("Housekeeping", "Could not delete cache ${child.absolutePath}")
            }
        }
        freed
    }

    private fun dirSize(file: File): Long {
        if (file.isFile) return file.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(file)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c) else total += c.length()
            }
        }
        return total
    }
}
