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

// Per-path install roots. When unset, callers fall back to the shared games root
// (JoiPlayScanner.getRootUri). Videos/Other have no fallback — they must be configured to use them.
private val WINLATOR_ROOT_URI = stringPreferencesKey("winlator_root_uri")
private val JOIPLAY_ROOT_URI = stringPreferencesKey("joiplay_root_uri")
private val KIRIKIROID_ROOT_URI = stringPreferencesKey("kirikiroid_root_uri")
private val VIDEOS_ROOT_URI = stringPreferencesKey("videos_root_uri")
private val OTHER_ROOT_URI = stringPreferencesKey("other_root_uri")
private val AUTO_USE_WINLATOR = booleanPreferencesKey("auto_use_winlator")

/** Persisted preferences for non-Android install flows and JoiPlay integration. */
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

    /** Which per-path install root a value belongs to. */
    enum class InstallRoot(internal val key: androidx.datastore.preferences.core.Preferences.Key<String>) {
        Winlator(WINLATOR_ROOT_URI),
        JoiPlay(JOIPLAY_ROOT_URI),
        Kirikiroid(KIRIKIROID_ROOT_URI),
        Videos(VIDEOS_ROOT_URI),
        Other(OTHER_ROOT_URI),
    }

    suspend fun installRootUri(context: Context, root: InstallRoot): String? = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[root.key] }.first()
    }

    suspend fun setInstallRootUri(context: Context, root: InstallRoot, uri: String?) {
        context.joiplayPrefsStore.edit {
            if (uri.isNullOrBlank()) it.remove(root.key) else it[root.key] = uri
        }
    }

    suspend fun autoUseWinlator(context: Context): Boolean = withContext(Dispatchers.IO) {
        context.joiplayPrefsStore.data.map { it[AUTO_USE_WINLATOR] ?: false }.first()
    }

    suspend fun setAutoUseWinlator(context: Context, value: Boolean) {
        context.joiplayPrefsStore.edit { it[AUTO_USE_WINLATOR] = value }
    }
}
