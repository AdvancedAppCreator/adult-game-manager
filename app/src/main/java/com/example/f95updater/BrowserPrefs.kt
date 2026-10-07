package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.IDN

private val Context.browserPrefsStore by preferencesDataStore("browser_prefs")

enum class BrowserDownloadBackend(val label: String) {
    AndroidDownloadManager("Android DownloadManager"),
    ExternalBrowser("External browser"),
}

data class BrowserSettings(
    val openLinksInApp: Boolean = false,
    val downloadFolderPath: String = "",
    val downloadBackend: BrowserDownloadBackend = BrowserDownloadBackend.AndroidDownloadManager,
    val allowedPopupHosts: Set<String> = emptySet(),
    val blockedPopupHosts: Set<String> = emptySet(),
    val disabledConfiguredPopupHosts: Set<String> = emptySet(),
)

object BrowserPrefs {
    private val KEY_OPEN_LINKS_IN_APP = booleanPreferencesKey("open_links_in_app")
    private val KEY_DOWNLOAD_FOLDER = stringPreferencesKey("download_folder_path")
    private val KEY_DOWNLOAD_BACKEND = stringPreferencesKey("download_backend")
    private val KEY_ALLOWED_POPUP_HOSTS = stringSetPreferencesKey("allowed_popup_hosts")
    private val KEY_BLOCKED_POPUP_HOSTS = stringSetPreferencesKey("blocked_popup_hosts")
    private val KEY_DISABLED_CONFIGURED_POPUP_HOSTS =
        stringSetPreferencesKey("disabled_configured_popup_hosts")

    fun observe(context: Context): Flow<BrowserSettings> =
        context.browserPrefsStore.data.map { prefs ->
            BrowserSettings(
                openLinksInApp = prefs[KEY_OPEN_LINKS_IN_APP] ?: false,
                downloadFolderPath = prefs[KEY_DOWNLOAD_FOLDER] ?: "",
                downloadBackend = prefs[KEY_DOWNLOAD_BACKEND]
                    ?.let { value ->
                        BrowserDownloadBackend.entries.firstOrNull { it.name == value }
                    }
                    ?: BrowserDownloadBackend.AndroidDownloadManager,
                allowedPopupHosts = normalizedHostSet(prefs[KEY_ALLOWED_POPUP_HOSTS].orEmpty()),
                blockedPopupHosts = normalizedHostSet(prefs[KEY_BLOCKED_POPUP_HOSTS].orEmpty()),
                disabledConfiguredPopupHosts =
                    normalizedHostSet(prefs[KEY_DISABLED_CONFIGURED_POPUP_HOSTS].orEmpty()),
            )
        }

    suspend fun setOpenLinksInApp(context: Context, value: Boolean) {
        context.browserPrefsStore.edit { it[KEY_OPEN_LINKS_IN_APP] = value }
    }

    suspend fun setDownloadFolderPath(context: Context, value: String) {
        context.browserPrefsStore.edit { it[KEY_DOWNLOAD_FOLDER] = value }
    }

    suspend fun setDownloadBackend(context: Context, value: BrowserDownloadBackend) {
        context.browserPrefsStore.edit { it[KEY_DOWNLOAD_BACKEND] = value.name }
    }

    suspend fun allowPopupHost(context: Context, rawHost: String, configuredHosts: Set<String>) {
        val host = requireNotNull(normalizeBrowserHost(rawHost)) { "Enter a valid host." }
        context.browserPrefsStore.edit { prefs ->
            val allowed = normalizedHostSet(prefs[KEY_ALLOWED_POPUP_HOSTS].orEmpty()).toMutableSet()
            val blocked = normalizedHostSet(prefs[KEY_BLOCKED_POPUP_HOSTS].orEmpty()).toMutableSet()
            val disabled =
                normalizedHostSet(prefs[KEY_DISABLED_CONFIGURED_POPUP_HOSTS].orEmpty()).toMutableSet()
            blocked.remove(host)
            disabled.remove(host)
            if (host !in configuredHosts) allowed.add(host)
            prefs[KEY_ALLOWED_POPUP_HOSTS] = allowed
            prefs[KEY_BLOCKED_POPUP_HOSTS] = blocked
            prefs[KEY_DISABLED_CONFIGURED_POPUP_HOSTS] = disabled
        }
    }

    suspend fun blockPopupHost(context: Context, rawHost: String) {
        val host = requireNotNull(normalizeBrowserHost(rawHost)) { "Enter a valid host." }
        context.browserPrefsStore.edit { prefs ->
            val allowed = normalizedHostSet(prefs[KEY_ALLOWED_POPUP_HOSTS].orEmpty()).toMutableSet()
            val blocked = normalizedHostSet(prefs[KEY_BLOCKED_POPUP_HOSTS].orEmpty()).toMutableSet()
            allowed.remove(host)
            blocked.add(host)
            prefs[KEY_ALLOWED_POPUP_HOSTS] = allowed
            prefs[KEY_BLOCKED_POPUP_HOSTS] = blocked
        }
    }

    suspend fun removeAllowedPopupHost(
        context: Context,
        rawHost: String,
        configuredHosts: Set<String>,
    ) {
        val host = requireNotNull(normalizeBrowserHost(rawHost)) { "Invalid host." }
        context.browserPrefsStore.edit { prefs ->
            val allowed = normalizedHostSet(prefs[KEY_ALLOWED_POPUP_HOSTS].orEmpty()).toMutableSet()
            val disabled =
                normalizedHostSet(prefs[KEY_DISABLED_CONFIGURED_POPUP_HOSTS].orEmpty()).toMutableSet()
            allowed.remove(host)
            if (host in configuredHosts) disabled.add(host)
            prefs[KEY_ALLOWED_POPUP_HOSTS] = allowed
            prefs[KEY_DISABLED_CONFIGURED_POPUP_HOSTS] = disabled
        }
    }

    suspend fun removeBlockedPopupHost(context: Context, rawHost: String) {
        val host = requireNotNull(normalizeBrowserHost(rawHost)) { "Invalid host." }
        context.browserPrefsStore.edit { prefs ->
            val blocked = normalizedHostSet(prefs[KEY_BLOCKED_POPUP_HOSTS].orEmpty()).toMutableSet()
            blocked.remove(host)
            prefs[KEY_BLOCKED_POPUP_HOSTS] = blocked
        }
    }
}

fun effectiveDownloadFolder(settings: BrowserSettings): java.io.File =
    if (settings.downloadFolderPath.isNotBlank()) java.io.File(settings.downloadFolderPath)
    else android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

internal fun effectivePopupAllowlist(
    configuredHosts: Collection<String>,
    settings: BrowserSettings,
): Set<String> {
    val configured = normalizedHostSet(configuredHosts)
    return (configured - settings.disabledConfiguredPopupHosts - settings.blockedPopupHosts +
        settings.allowedPopupHosts) - settings.blockedPopupHosts
}

internal fun normalizeBrowserHost(raw: String): String? {
    val trimmed = raw.trim().removePrefix("*.").trimEnd('.')
    if (trimmed.isBlank()) return null
    val parsed = runCatching {
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        java.net.URI(candidate).host
    }.getOrNull()?.trimEnd('.') ?: return null
    val ascii = runCatching { IDN.toASCII(parsed, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
        ?.lowercase()
        ?: return null
    return ascii.takeIf {
        it.length <= 253 &&
            it.split('.').all { label ->
                label.isNotBlank() && label.length <= 63 &&
                    !label.startsWith('-') && !label.endsWith('-')
            }
    }
}

private fun normalizedHostSet(values: Collection<String>): Set<String> =
    values.mapNotNull(::normalizeBrowserHost).toSet()
