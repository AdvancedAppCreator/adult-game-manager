package com.example.f95updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the user's Catalog tab filter selection and sort order so they
 * survive process restarts.
 */
private val Context.catalogPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "catalog_prefs")

object CatalogPrefs {
    private val KEY_QUERY              = stringPreferencesKey("query")
    private val KEY_SORT_KEY           = stringPreferencesKey("sort_key")
    private val KEY_SORT_DESC          = booleanPreferencesKey("sort_desc")
    private val KEY_STATUS             = intPreferencesKey("status_filter")
    private val KEY_ENGINE             = intPreferencesKey("engine_filter")
    private val KEY_CATEGORY           = stringPreferencesKey("category_filter")
    private val KEY_SOURCE             = stringPreferencesKey("source_filter")
    private val KEY_PLATFORM           = stringPreferencesKey("platform_filter")
    private val KEY_MIN_RATING         = floatPreferencesKey("min_rating")
    private val KEY_INSTALLED_ONLY     = booleanPreferencesKey("installed_only")
    private val KEY_NOT_INSTALLED_ONLY = booleanPreferencesKey("not_installed_only")

    data class State(
        val query: String,
        val sortKey: CatalogSortKey,
        val sortDesc: Boolean,
        val statusFilter: Int?,
        val engineFilter: Int?,
        val categoryFilter: String?,
        val sourceFilter: String?,
        val platformFilter: String?,
        val minRating: Float,
        val installedOnly: Boolean,
        val notInstalledOnly: Boolean,
    )

    private val DEFAULT = State(
        query = "",
        sortKey = CatalogSortKey.Rating,
        sortDesc = true,
        statusFilter = null,
        engineFilter = null,
        categoryFilter = null,
        sourceFilter = null,
        platformFilter = null,
        minRating = 0f,
        installedOnly = false,
        notInstalledOnly = false,
    )

    suspend fun load(context: Context): State {
        val prefs = context.catalogPrefsStore.data.first()
        return State(
            query              = prefs[KEY_QUERY] ?: DEFAULT.query,
            sortKey            = prefs[KEY_SORT_KEY]?.let { name ->
                runCatching { CatalogSortKey.valueOf(name) }.getOrDefault(DEFAULT.sortKey)
            } ?: DEFAULT.sortKey,
            sortDesc           = prefs[KEY_SORT_DESC] ?: DEFAULT.sortDesc,
            statusFilter       = prefs[KEY_STATUS]?.takeIf { it != Int.MIN_VALUE },
            engineFilter       = prefs[KEY_ENGINE]?.takeIf { it != Int.MIN_VALUE },
            categoryFilter     = prefs[KEY_CATEGORY]?.takeIf { it.isNotEmpty() },
            sourceFilter       = prefs[KEY_SOURCE]?.takeIf { it.isNotEmpty() }?.let(::migrateSourceFilter),
            platformFilter     = prefs[KEY_PLATFORM]?.takeIf { it.isNotEmpty() },
            minRating          = prefs[KEY_MIN_RATING] ?: DEFAULT.minRating,
            installedOnly      = prefs[KEY_INSTALLED_ONLY] ?: DEFAULT.installedOnly,
            notInstalledOnly   = prefs[KEY_NOT_INSTALLED_ONLY] ?: DEFAULT.notInstalledOnly,
        )
    }

    /** v1.0.91 persisted the source filter as the enum constant name ("F95Zone"/
     *  "AdultGameWorld"); v2 compares against the lowercase data-driven source id. Map the
     *  legacy values (and any other) to lowercase so an upgraded user's saved filter keeps
     *  matching instead of silently hiding the whole catalog. */
    private fun migrateSourceFilter(raw: String): String = when (raw) {
        "F95Zone" -> SOURCE_F95ZONE
        "AdultGameWorld" -> SOURCE_ADULTGAMEWORLD
        else -> raw
    }

    suspend fun save(context: Context, s: State) {
        context.catalogPrefsStore.edit { prefs ->
            prefs[KEY_QUERY]              = s.query
            prefs[KEY_SORT_KEY]           = s.sortKey.name
            prefs[KEY_SORT_DESC]          = s.sortDesc
            if (s.statusFilter == null) prefs.remove(KEY_STATUS) else prefs[KEY_STATUS] = s.statusFilter
            if (s.engineFilter == null) prefs.remove(KEY_ENGINE) else prefs[KEY_ENGINE] = s.engineFilter
            if (s.categoryFilter.isNullOrEmpty()) prefs.remove(KEY_CATEGORY) else prefs[KEY_CATEGORY] = s.categoryFilter
            if (s.sourceFilter.isNullOrEmpty()) prefs.remove(KEY_SOURCE) else prefs[KEY_SOURCE] = s.sourceFilter
            if (s.platformFilter.isNullOrEmpty()) prefs.remove(KEY_PLATFORM) else prefs[KEY_PLATFORM] = s.platformFilter
            prefs[KEY_MIN_RATING]         = s.minRating
            prefs[KEY_INSTALLED_ONLY]     = s.installedOnly
            prefs[KEY_NOT_INSTALLED_ONLY] = s.notInstalledOnly
        }
    }
}
