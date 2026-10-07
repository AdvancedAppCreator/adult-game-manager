package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.catalogIgnoreStore by preferencesDataStore(name = "catalog_ignored")

object CatalogIgnoreStore {
    private val KEY_GROUP_IDS = stringSetPreferencesKey("group_ids")

    fun observe(context: Context): Flow<Set<String>> =
        context.catalogIgnoreStore.data
            .map { prefs -> prefs[KEY_GROUP_IDS].orEmpty().filterTo(linkedSetOf()) { it.isNotBlank() } }
            .distinctUntilChanged()

    suspend fun setIgnored(context: Context, groupIds: Collection<String>, ignored: Boolean) {
        val valid = groupIds.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (valid.isEmpty()) return
        context.catalogIgnoreStore.edit { prefs ->
            val updated = prefs[KEY_GROUP_IDS].orEmpty().toMutableSet()
            if (ignored) updated.addAll(valid) else updated.removeAll(valid)
            prefs[KEY_GROUP_IDS] = updated
        }
    }

    suspend fun toggle(context: Context, groupId: String) {
        if (groupId.isBlank()) return
        context.catalogIgnoreStore.edit { prefs ->
            val updated = prefs[KEY_GROUP_IDS].orEmpty().toMutableSet()
            if (!updated.add(groupId)) updated.remove(groupId)
            prefs[KEY_GROUP_IDS] = updated
        }
    }
}
