package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.matchingPrefsStore by preferencesDataStore("matching_prefs")

object MatchingPrefs {
    private val KEY_OVERWRITE_MANUAL = booleanPreferencesKey("overwrite_manual_matches")

    suspend fun overwriteManualMatches(context: Context): Boolean =
        context.matchingPrefsStore.data.first()[KEY_OVERWRITE_MANUAL] ?: false

    suspend fun setOverwriteManualMatches(context: Context, value: Boolean) {
        context.matchingPrefsStore.edit { it[KEY_OVERWRITE_MANUAL] = value }
    }
}
