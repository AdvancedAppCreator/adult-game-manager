package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-upgrade rollback/cleanup folder management for managed games.
 *
 * Legacy AGM upgrades renamed the old game folder to `<name>.bak-<timestamp>` beside the
 * reused game folder, and those backups can still be reverted to. The current managed
 * upgrade instead extracts into a brand new folder and leaves the previous one renamed to
 * `upgraded_to_delete_<name>`; that leftover is recorded in the game's `.agm-install.json`
 * and can only be deleted, never reverted to, because AGM and every runner already point at
 * the new folder. This object lets the user, from the game's action menu:
 *
 *   • Revert to a legacy backup (delete the current folder, restore the backup) — for when a
 *     freshly updated game fails to launch.
 *   • Delete the leftover folder(s) — to reclaim space once the update is validated.
 *
 * All operations use plain [File] APIs (AGM holds MANAGE_EXTERNAL_STORAGE), mirroring the
 * upgrade flow which resolves the game folder as `File(app.storagePath)`.
 */
object JoiPlayBackupManager {

    /** Marker `source` written by the current managed upgrade, whose leftover is delete-only. */
    private const val MANAGED_UPGRADE_MARKER_SOURCE = "agm-managed-upgrade"

    /** Matches the legacy `<name>.bak-<timestamp>` rollback folders. */
    private fun backupRegexFor(baseName: String) =
        Regex("""^${Regex.escape(baseName)}\.bak-\d{8}-\d{6}(?:-\d+)?$""")

    data class BackupInfo(
        val gameFolder: File,
        /** All matching sibling rollback/cleanup folders, newest first. */
        val backups: List<File>,
        /** `installedAt` recorded in the game's `.agm-install.json`, if present. */
        val recordedAt: String?,
        /** `sourceArchive` recorded in the game's `.agm-install.json`, if present. */
        val sourceArchive: String?,
        /** False for current managed upgrades, whose leftover folder can only be deleted. */
        val revertible: Boolean = true,
    ) {
        val newest: File get() = backups.first()
        val count: Int get() = backups.size
    }

    sealed interface ActionResult {
        data class Success(val message: String) : ActionResult
        data class Failure(val message: String) : ActionResult
        object NoBackup : ActionResult
    }

    /**
     * Locate rollback folder(s) for a JoiPlay game. Returns null if none exist. This is
     * cheap (a single directory listing, no recursive size walk) so callers can show the
     * "backup exists" state instantly; use [totalSize] separately when a size is needed.
     */
    suspend fun findBackup(app: InstalledApp): BackupInfo? = withContext(Dispatchers.IO) {
        if (app.source != AppSource.JoiPlay && app.source != AppSource.Managed) {
            return@withContext null
        }
        val gameFolder = resolveGameFolder(app) ?: return@withContext null
        findBackupForFolder(gameFolder)
    }

    /** Pure core of [findBackup]: operates directly on a resolved game folder. */
    fun findBackupForFolder(gameFolder: File): BackupInfo? {
        val parent = gameFolder.parentFile ?: return null
        val re = backupRegexFor(gameFolder.name)

        val found = LinkedHashSet<File>()
        parent.listFiles()?.forEach { f ->
            if (f.isDirectory && re.matches(f.name)) found.add(f)
        }

        // Best-effort: honour the exact rollback path recorded in the install marker even
        // if it doesn't match the naming pattern (e.g. a hand-renamed folder, or the
        // upgraded_to_delete_* leftover of a current managed upgrade).
        val marker = readMarker(gameFolder)
        val recordedAt = marker?.optString("installedAt")?.trim()?.ifBlank { null }
        val sourceArchive = marker?.optString("sourceArchive")?.trim()?.ifBlank { null }
        marker?.optString("rollbackFolderPath")?.trim()?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isDirectory) found.add(f)
        }

        if (found.isEmpty()) return null
        val ordered = found.sortedByDescending { it.lastModified() }
        return BackupInfo(
            gameFolder = gameFolder,
            backups = ordered,
            recordedAt = recordedAt,
            sourceArchive = sourceArchive,
            revertible = marker?.optString("source") != MANAGED_UPGRADE_MARKER_SOURCE,
        )
    }

    /** Recursively sum the byte size of all backup folders (may be slow for big games). */
    suspend fun totalSize(info: BackupInfo): Long = withContext(Dispatchers.IO) {
        info.backups.sumOf { dirSize(it) }
    }

    /**
     * Revert the game to its most recent backup: move the current folder aside, rename the
     * backup back to the original name, then delete the moved-aside current folder. Older
     * `.bak-` folders are left untouched.
     */
    suspend fun revertToBackup(app: InstalledApp): ActionResult = withContext(Dispatchers.IO) {
        val info = findBackup(app) ?: return@withContext ActionResult.NoBackup
        if (!info.revertible) {
            return@withContext ActionResult.Failure(
                "This game was upgraded into a new folder, so AGM can't revert by renaming folders. " +
                    "Re-install the previous archive instead.",
            )
        }
        val gameFolder = info.gameFolder
        val parent = gameFolder.parentFile
            ?: return@withContext ActionResult.Failure("Game folder has no parent.")
        if (!parent.canWrite()) {
            return@withContext ActionResult.Failure("Can't write to the game folder's parent.")
        }
        val backup = info.newest
        if (!backup.isDirectory) return@withContext ActionResult.Failure("Backup folder is missing.")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val tmp = File(parent, "${gameFolder.name}.reverting-$stamp")

        // 1) Move the current (new) folder aside so we can put the backup in its place.
        if (gameFolder.exists() && !gameFolder.renameTo(tmp)) {
            return@withContext ActionResult.Failure("Could not move the current folder aside.")
        }
        // 2) Restore the backup into the original name.
        if (!backup.renameTo(gameFolder)) {
            // Roll our aside-move back so we don't leave the game broken.
            if (tmp.exists()) tmp.renameTo(gameFolder)
            return@withContext ActionResult.Failure("Could not restore the backup folder.")
        }
        // 3) Drop the moved-aside new version.
        runCatching { if (tmp.exists()) tmp.deleteRecursively() }
            .onFailure { AppLog.w("JoiPlayBackup", "Reverted but could not delete $tmp", it) }

        AppLog.i("JoiPlayBackup", "Reverted ${app.label}: restored ${backup.name} -> ${gameFolder.name}")
        ActionResult.Success("Reverted to backup (${backup.name}). Relaunch to confirm.")
    }

    /** Delete every `<name>.bak-*` rollback folder for the game. Returns bytes/count freed. */
    suspend fun deleteBackups(app: InstalledApp): ActionResult = withContext(Dispatchers.IO) {
        val info = findBackup(app) ?: return@withContext ActionResult.NoBackup
        deleteFolders(info.backups, app.label)
    }

    /** Folders that would be pruned when keeping the newest [keep] backups (newest-first order). */
    fun prunableBackups(info: BackupInfo, keep: Int): List<File> =
        if (keep <= 0) info.backups else info.backups.drop(keep)

    /** Delete all but the newest [keep] rollback folders for the game. Returns bytes/count freed. */
    suspend fun pruneBackups(app: InstalledApp, keep: Int): ActionResult = withContext(Dispatchers.IO) {
        val info = findBackup(app) ?: return@withContext ActionResult.NoBackup
        val toDelete = prunableBackups(info, keep)
        if (toDelete.isEmpty()) return@withContext ActionResult.NoBackup
        deleteFolders(toDelete, app.label)
    }

    private fun deleteFolders(folders: List<File>, label: String): ActionResult {
        var deleted = 0
        var failed = 0
        var freed = 0L
        for (b in folders) {
            val size = dirSize(b)
            if (b.deleteRecursively()) {
                deleted++
                freed += size
            } else {
                failed++
                AppLog.w("JoiPlayBackup", "Could not delete backup ${b.absolutePath}")
            }
        }
        if (deleted == 0) {
            return ActionResult.Failure("Could not delete the backup folder.")
        }
        val plural = if (deleted == 1) "backup" else "backups"
        val tail = if (failed > 0) " ($failed could not be deleted)" else ""
        AppLog.i("JoiPlayBackup", "Deleted $deleted backup(s) for $label, freed=$freed failed=$failed")
        return ActionResult.Success("Deleted $deleted $plural, freed ${fmtBytes(freed)}$tail.")
    }

    /** Recursively sum the byte size of a list of folders. */
    suspend fun sizeOfFolders(folders: List<File>): Long = withContext(Dispatchers.IO) {
        folders.sumOf { dirSize(it) }
    }

    // ---------------------------------------------------------------------------------

    private fun resolveGameFolder(app: InstalledApp): File? {
        val path = app.storagePath?.takeIf { it.isNotBlank() } ?: return null
        val f = File(path)
        return if (f.isAbsolute && f.isDirectory) f else null
    }

    private fun readMarker(gameFolder: File): JSONObject? {
        val marker = File(gameFolder, JoiPlayVersionDetector.INSTALL_MARKER_FILE)
        if (!marker.isFile) return null
        return runCatching { JSONObject(marker.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c) else total += c.length()
            }
        }
        return total
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
