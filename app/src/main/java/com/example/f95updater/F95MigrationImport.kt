package com.example.f95updater

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ImportedF95MigrationEnvelope(
    val schemaVersion: Int = 1,
    val sourceApp: String = "F95Updater",
    val sourcePackage: String = "com.advancedappcreator.f95updater",
    val exportedAtMs: Long = 0L,
    val backup: BackupBundle,
)

object F95MigrationImport {
    private val uri: Uri = Uri.parse("content://com.advancedappcreator.f95updater.migration/export.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun isF95UpdaterInstalled(context: Context): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("com.advancedappcreator.f95updater", 0)
            true
        }.getOrDefault(false)

    suspend fun importFromInstalledF95Updater(context: Context, replace: Boolean = false): ImportSummary {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("F95 Updater migration provider did not return data.")
        val envelope = json.decodeFromString(ImportedF95MigrationEnvelope.serializer(), text)
        val backupText = json.encodeToString(BackupBundle.serializer(), envelope.backup)
        return MappingRepository(context).importJson(backupText, replace = replace)
    }
}
