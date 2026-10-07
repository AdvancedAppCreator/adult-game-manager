package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.themePrefsStore by preferencesDataStore("theme_prefs")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val ANDROID_CARD_COLOR_KEY = stringPreferencesKey("android_card_color")
private val JOIPLAY_CARD_COLOR_KEY = stringPreferencesKey("joiplay_card_color")
private val WINLATOR_CARD_COLOR_KEY = stringPreferencesKey("winlator_card_color")
private val KIRIKIROID_CARD_COLOR_KEY = stringPreferencesKey("kirikiroid_card_color")

enum class AppThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

data class CardColorSettings(
    val android: String = "#6750A4",
    val joiPlay: String = "#7D5260",
    val winlator: String = "#4DB6AC",
    val kirikiroid: String = "#9575CD",
) {
    fun colorFor(app: InstalledApp): Color {
        val hex = when {
            app.source == AppSource.Android -> android
            app.managedDefaultRunner == ManagedRunnerKind.Winlator || app.source == AppSource.Winlator -> winlator
            app.managedDefaultRunner == ManagedRunnerKind.Kirikiroid || app.source == AppSource.Kirikiroid -> kirikiroid
            else -> joiPlay
        }
        return parseCardColor(hex).copy(alpha = 0.22f)
    }
}

val LocalCardColorSettings = staticCompositionLocalOf { CardColorSettings() }

fun normalizeCardColor(raw: String): String? {
    val value = raw.trim().removePrefix("#")
    if (value.length != 6 || value.any { it !in "0123456789abcdefABCDEF" }) return null
    return "#${value.uppercase()}"
}

fun parseCardColor(raw: String): Color {
    val normalized = normalizeCardColor(raw) ?: error("Invalid card color: $raw")
    return Color(("FF" + normalized.removePrefix("#")).toLong(16))
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

object CardColorPrefs {
    fun observe(context: Context): Flow<CardColorSettings> =
        context.themePrefsStore.data.map { prefs ->
            CardColorSettings(
                android = prefs[ANDROID_CARD_COLOR_KEY]?.let(::normalizeCardColor)
                    ?: CardColorSettings().android,
                joiPlay = prefs[JOIPLAY_CARD_COLOR_KEY]?.let(::normalizeCardColor)
                    ?: CardColorSettings().joiPlay,
                winlator = prefs[WINLATOR_CARD_COLOR_KEY]?.let(::normalizeCardColor)
                    ?: CardColorSettings().winlator,
                kirikiroid = prefs[KIRIKIROID_CARD_COLOR_KEY]?.let(::normalizeCardColor)
                    ?: CardColorSettings().kirikiroid,
            )
        }

    suspend fun set(context: Context, colors: CardColorSettings) = withContext(Dispatchers.IO) {
        val normalized = CardColorSettings(
            android = requireNotNull(normalizeCardColor(colors.android)),
            joiPlay = requireNotNull(normalizeCardColor(colors.joiPlay)),
            winlator = requireNotNull(normalizeCardColor(colors.winlator)),
            kirikiroid = requireNotNull(normalizeCardColor(colors.kirikiroid)),
        )
        context.themePrefsStore.edit {
            it[ANDROID_CARD_COLOR_KEY] = normalized.android
            it[JOIPLAY_CARD_COLOR_KEY] = normalized.joiPlay
            it[WINLATOR_CARD_COLOR_KEY] = normalized.winlator
            it[KIRIKIROID_CARD_COLOR_KEY] = normalized.kirikiroid
        }
    }
}
