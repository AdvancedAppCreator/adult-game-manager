package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.themePrefsStore by preferencesDataStore("theme_prefs")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

enum class AppThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

object ThemePrefs {
    fun observe(context: Context): Flow<AppThemeMode> =
        context.themePrefsStore.data.map { prefs ->
            prefs[THEME_MODE_KEY]
                ?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
                ?: AppThemeMode.System
        }

    suspend fun set(context: Context, mode: AppThemeMode) = withContext(Dispatchers.IO) {
        context.themePrefsStore.edit { it[THEME_MODE_KEY] = mode.name }
    }
}
