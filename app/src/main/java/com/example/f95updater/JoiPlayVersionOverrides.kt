package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.joiplayOverrideStore by preferencesDataStore("joiplay_overrides")
private val OVERRIDES_KEY = stringPreferencesKey("version_overrides_json")

/** Per-JoiPlay-game-id version overrides (user-confirmed installed versions). */
object JoiPlayVersionOverrides {
    private val json = Json
    private val ser = MapSerializer(String.serializer(), String.serializer())

    suspend fun loadAll(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val text = context.joiplayOverrideStore.data.map { it[OVERRIDES_KEY] }.first()
            ?: return@withContext emptyMap()
        runCatching { json.decodeFromString(ser, text) }.getOrDefault(emptyMap())
    }

    suspend fun get(context: Context, gameId: String): String? = loadAll(context)[gameId]

    suspend fun set(context: Context, gameId: String, version: String) = withContext(Dispatchers.IO) {
        val current = loadAll(context).toMutableMap()
        current[gameId] = version
        context.joiplayOverrideStore.edit { it[OVERRIDES_KEY] = json.encodeToString(ser, current) }
    }

    suspend fun remove(context: Context, gameId: String) = withContext(Dispatchers.IO) {
        val current = loadAll(context).toMutableMap()
        current.remove(gameId)
        context.joiplayOverrideStore.edit { it[OVERRIDES_KEY] = json.encodeToString(ser, current) }
    }
}
