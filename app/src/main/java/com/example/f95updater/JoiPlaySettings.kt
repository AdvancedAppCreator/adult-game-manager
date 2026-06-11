package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.joiplayPrefsStore by preferencesDataStore("joiplay_prefs")

private val INSTALL_WARNING_DISMISSED = booleanPreferencesKey("install_warning_dismissed")
private val SOURCE_FOLDER_URI = stringPreferencesKey("source_folder_uri")
private val EXTRACT_DEST_URI = stringPreferencesKey("extract_dest_uri")
private val LAST_FILE_PICKER_DIR = stringPreferencesKey("last_file_picker_dir")
private val DELETE_AFTER_INSTALL = booleanPreferencesKey("delete_after_install")
private val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
private val UPGRADE_GUIDANCE_DISMISSED = booleanPreferencesKey("upgrade_guidance_dismissed")

/** Persisted preferences specific to JoiPlay integration. */
object JoiPlaySettingsStore {

    suspend fun installWarningDismissed(context: Context): Boolean = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[INSTALL_WARNING_DISMISSED] ?: false }.first()
    }

    suspend fun setInstallWarningDismissed(context: Context, dismissed: Boolean) {
        context.joiplayPrefsStore.edit { it[INSTALL_WARNING_DISMISSED] = dismissed }
    }

    suspend fun sourceFolderUri(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[SOURCE_FOLDER_URI] }.first()
    }

    suspend fun setSourceFolderUri(context: Context, uri: String?) {
        context.joiplayPrefsStore.edit {
            if (uri == null) it.remove(SOURCE_FOLDER_URI) else it[SOURCE_FOLDER_URI] = uri
        }
    }

    suspend fun extractDestUri(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[EXTRACT_DEST_URI] }.first()
    }

    suspend fun setExtractDestUri(context: Context, uri: String?) {
        context.joiplayPrefsStore.edit {
            if (uri == null) it.remove(EXTRACT_DEST_URI) else it[EXTRACT_DEST_URI] = uri
        }
    }

    /** Last folder the user navigated into via FilePickerDialog (any of the install
     *  flows). Used to re-open the picker on the same path next time. */
    suspend fun lastFilePickerDir(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[LAST_FILE_PICKER_DIR] }.first()
    }

    suspend fun setLastFilePickerDir(context: Context, path: String?) {
        context.joiplayPrefsStore.edit {
            if (path.isNullOrBlank()) it.remove(LAST_FILE_PICKER_DIR) else it[LAST_FILE_PICKER_DIR] = path
        }
    }

    suspend fun deleteAfterInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[DELETE_AFTER_INSTALL] ?: false }.first()
    }

    suspend fun setDeleteAfterInstall(context: Context, value: Boolean) {
        context.joiplayPrefsStore.edit { it[DELETE_AFTER_INSTALL] = value }
    }

    suspend fun backupFolderUri(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[BACKUP_FOLDER_URI] }.first()
    }

    suspend fun setBackupFolderUri(context: Context, uri: String?) {
        context.joiplayPrefsStore.edit {
            if (uri.isNullOrBlank()) it.remove(BACKUP_FOLDER_URI) else it[BACKUP_FOLDER_URI] = uri
        }
    }

    suspend fun upgradeGuidanceDismissed(context: Context): Boolean = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[UPGRADE_GUIDANCE_DISMISSED] ?: false }.first()
    }

    suspend fun setUpgradeGuidanceDismissed(context: Context, dismissed: Boolean) {
        context.joiplayPrefsStore.edit { it[UPGRADE_GUIDANCE_DISMISSED] = dismissed }
    }
}
