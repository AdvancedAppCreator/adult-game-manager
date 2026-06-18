package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SaveSyncMirrorTarget(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class SaveSyncMirrorResult(
    val ok: Boolean,
    val message: String,
)

object SaveSyncMirror {
    fun findSyncTarget(savePath: String): SaveSyncMirrorTarget? {
        val saveFile = File(savePath)
        val dir = saveFile.parentFile ?: return null
        if (dir.name.equals("sync", ignoreCase = true)) return null
        val syncFile = File(File(dir, "sync"), saveFile.name)
        if (!syncFile.isFile) return null
        return SaveSyncMirrorTarget(
            fileName = syncFile.name,
            filePath = syncFile.absolutePath,
            sizeBytes = syncFile.length(),
            modifiedAt = syncFile.lastModified(),
        )
    }

    suspend fun overwriteSyncTarget(sourcePath: String, target: SaveSyncMirrorTarget): SaveSyncMirrorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                val destination = File(target.filePath)
                if (!source.isFile) return@withContext SaveSyncMirrorResult(false, "Edited save file no longer exists.")
                if (!destination.isFile) return@withContext SaveSyncMirrorResult(false, "Sync save file no longer exists.")
                val backup = File(destination.parentFile, "${destination.name}.agm-bak-${System.currentTimeMillis()}")
                destination.copyTo(backup, overwrite = false)
                source.copyTo(destination, overwrite = true)
                SaveSyncMirrorResult(true, "Sync copy overwritten. Backup: ${backup.name}.")
            }.getOrElse { t ->
                AppLog.w("SaveSync", "Could not overwrite sync save ${target.filePath}: ${t.message}", t)
                SaveSyncMirrorResult(false, "Could not overwrite sync copy: ${t.message ?: "unknown error"}")
            }
        }
}
