package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-backup safety net.
 *
 * On every successful app upgrade (i.e. first launch on a new versionCode), we snapshot
 * the current state — mappings, hidden list, JoiPlay backup snapshot, version overrides,
 * deleted-id list — to a JSON file under <files>/auto_backups/. The newest [MAX_RETAINED]
 * backups are kept; older ones are pruned to save space.
 *
 * The user can list and restore from these via menu → Backup & config → Restore auto-backup.
 * Each restore creates a "pre-restore" backup first so the user can roll back if needed.
 *
 * Storage location:
 *   <files>/auto_backups/auto-vNNN-yyyyMMdd-HHmmss.json
 * Files survive app upgrades but NOT uninstall — for cross-uninstall durability the user
 * should also Export backup to Documents periodically. (Adding a Documents copy too would
 * require MediaStore writes which we already do for app_config; we may add that later.)
 */
private val Context.autoBackupStore by preferencesDataStore("auto_backup")
private val LAST_SEEN_VERSION_CODE = intPreferencesKey("last_seen_version_code")

object AutoBackupManager {
    private const val DIR_NAME = "auto_backups"
    private const val MAX_RETAINED = 5
    private val tsFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val displayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    data class BackupEntry(
        val file: File,
        val versionCode: Int,
        val createdAtMs: Long,
        val sizeBytes: Long,
    ) {
        val displayDate: String get() = displayFmt.format(Date(createdAtMs))
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * Called once on every app launch. If we're running on a newer versionCode than the
     * one we last recorded, snapshot current state to a fresh auto-backup file (tagged
     * with the *previous* versionCode — that's the state we'd want to recover to).
     * If the snapshot fails for any reason, we still bump the stored versionCode so the
     * next launch doesn't keep retrying on a permanently-broken state.
     */
    suspend fun maybeBackupOnUpgrade(context: Context, repo: MappingRepository): String? =
        withContext(Dispatchers.IO) {
            val current = BuildConfig.VERSION_CODE
            val last = context.autoBackupStore.data.map { it[LAST_SEEN_VERSION_CODE] ?: 0 }.first()
            if (last == current) return@withContext null
            // First-ever launch: don't snapshot empty state; just record the version.
            if (last == 0) {
                context.autoBackupStore.edit { it[LAST_SEEN_VERSION_CODE] = current }
                AppLog.i("AutoBackup", "First launch on v$current; nothing to back up.")
                return@withContext null
            }
            val path = runCatching {
                writeBackup(context, repo, label = "v$last")
            }.onFailure {
                AppLog.e("AutoBackup", "Snapshot on upgrade failed (ignored)", it)
            }.getOrNull()
            context.autoBackupStore.edit { it[LAST_SEEN_VERSION_CODE] = current }
            AppLog.i("AutoBackup", "Upgraded $last -> $current; backup=${path ?: "(failed)"}")
            path
        }

    /** Persist a fresh JSON snapshot. Returns the absolute path. */
    suspend fun writeBackup(context: Context, repo: MappingRepository, label: String): String =
        withContext(Dispatchers.IO) {
            val json = repo.exportJson()
            val name = "auto-${label}-${tsFmt.format(Date())}.json"
            val f = File(dir(context), name)
            f.writeText(json)
            prune(context)
            f.absolutePath
        }

    /** List auto-backups, newest first. */
    suspend fun list(context: Context): List<BackupEntry> = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?: return@withContext emptyList()
        files.mapNotNull { f ->
            val m = Regex("^auto-v?(\\d+)?[-_]?(\\d{8}-\\d{6})\\.json$").matchEntire(f.name)
                ?: return@mapNotNull BackupEntry(f, 0, f.lastModified(), f.length())
            val ver = m.groupValues[1].toIntOrNull() ?: 0
            val ts = runCatching { tsFmt.parse(m.groupValues[2])?.time }.getOrNull() ?: f.lastModified()
            BackupEntry(f, ver, ts, f.length())
        }.sortedByDescending { it.createdAtMs }
    }

    /** Apply a backup file's contents through the standard importJson() path. */
    suspend fun restore(context: Context, repo: MappingRepository, entry: BackupEntry): ImportSummary =
        withContext(Dispatchers.IO) {
            // Snapshot current state under a "pre-restore-..." name so the user can
            // step back if the restore turns out to be wrong.
            runCatching {
                writeBackup(context, repo, label = "prerestore")
            }.onFailure { AppLog.w("AutoBackup", "Pre-restore snapshot failed (ignored)", it) }
            val text = entry.file.readText()
            repo.importJson(text, replace = true)
        }

    /** Delete a single backup file. */
    suspend fun delete(entry: BackupEntry): Boolean = withContext(Dispatchers.IO) {
        runCatching { entry.file.delete() }.getOrDefault(false)
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?: return
        if (files.size <= MAX_RETAINED) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_RETAINED)
            .forEach { runCatching { it.delete() } }
    }
}
