package com.example.f95updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    private val KEY_WISHLIST_ONLY      = booleanPreferencesKey("wishlist_only")
    private val KEY_NOT_WISHLIST_ONLY  = booleanPreferencesKey("not_wishlist_only")
    private val KEY_IGNORED_ONLY       = booleanPreferencesKey("ignored_only")
    private val KEY_CANONICAL_TAGS     = stringPreferencesKey("canonical_tag_ids")
    private val KEY_SELECTED_TAGS      = stringPreferencesKey("selected_tag_tokens")
    private val KEY_TAG_GROUPS         = stringPreferencesKey("tag_groups")
    private val KEY_INCLUDE_SYNOPSIS   = booleanPreferencesKey("include_synopsis")
    private val KEY_SEARCH_MODE        = stringPreferencesKey("search_mode")
    private val KEY_MIN_POPULARITY     = longPreferencesKey("min_popularity")
    private val KEY_DATE_FIELD         = stringPreferencesKey("date_field")
    private val KEY_DATE_RANGE         = stringPreferencesKey("date_range")
    private val KEY_TRANSLATE_TITLES   = booleanPreferencesKey("translate_titles")
    private val KEY_TRANSLATION_TARGET = stringPreferencesKey("translation_target")

    private val json = Json { ignoreUnknownKeys = true }
    private val tagGroupsSerializer = ListSerializer(CatalogTagGroup.serializer())

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
        val wishlistOnly: Boolean,
        val notWishlistOnly: Boolean,
        val ignoredOnly: Boolean = false,
        val canonicalTagIds: List<String> = emptyList(),
        val selectedTagTokens: List<String> = emptyList(),
        val tagGroups: List<CatalogTagGroup> = emptyList(),
        val includeSynopsis: Boolean = false,
        val searchMode: CatalogSearchMode = CatalogSearchMode.Normal,
        val minPopularity: Long = 0L,
        val dateField: CatalogDateField = CatalogDateField.Updated,
        val dateRange: DateRangeFilter = DateRangeFilter.Any,
        val translateTitles: Boolean,
        val translationTarget: String,
    )

    data class TranslationSettings(
        val enabled: Boolean = false,
        val targetLanguage: String = "en",
    )

    private val DEFAULT = State(
        query = "",
        sortKey = CatalogSortKey.Relevance,
        sortDesc = true,
        statusFilter = null,
        engineFilter = null,
        categoryFilter = null,
        sourceFilter = null,
        platformFilter = null,
        minRating = 0f,
        installedOnly = false,
        notInstalledOnly = false,
        wishlistOnly = false,
        notWishlistOnly = false,
        ignoredOnly = false,
        translateTitles = false,
        translationTarget = "en",
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
            wishlistOnly       = prefs[KEY_WISHLIST_ONLY] ?: DEFAULT.wishlistOnly,
            notWishlistOnly    = prefs[KEY_NOT_WISHLIST_ONLY] ?: DEFAULT.notWishlistOnly,
            ignoredOnly       = prefs[KEY_IGNORED_ONLY] ?: DEFAULT.ignoredOnly,
            canonicalTagIds    = prefs[KEY_CANONICAL_TAGS]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: DEFAULT.canonicalTagIds,
            selectedTagTokens  = prefs[KEY_SELECTED_TAGS]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: DEFAULT.selectedTagTokens,
            tagGroups          = prefs[KEY_TAG_GROUPS]
                ?.let { runCatching { json.decodeFromString(tagGroupsSerializer, it) }.getOrNull() }
                ?: DEFAULT.tagGroups,
            includeSynopsis    = prefs[KEY_INCLUDE_SYNOPSIS] ?: DEFAULT.includeSynopsis,
            searchMode         = prefs[KEY_SEARCH_MODE]?.let { name ->
                runCatching { CatalogSearchMode.valueOf(name) }.getOrNull()
            } ?: DEFAULT.searchMode,
            minPopularity      = prefs[KEY_MIN_POPULARITY] ?: DEFAULT.minPopularity,
            dateField          = prefs[KEY_DATE_FIELD]?.let { name ->
                runCatching { CatalogDateField.valueOf(name) }.getOrNull()
            } ?: DEFAULT.dateField,
            dateRange          = prefs[KEY_DATE_RANGE]?.let { name ->
                runCatching { DateRangeFilter.valueOf(name) }.getOrNull()
            } ?: DEFAULT.dateRange,
            translateTitles    = prefs[KEY_TRANSLATE_TITLES] ?: DEFAULT.translateTitles,
            translationTarget  = prefs[KEY_TRANSLATION_TARGET] ?: DEFAULT.translationTarget,
        )
    }

    fun observeTranslationSettings(context: Context): Flow<TranslationSettings> =
        context.catalogPrefsStore.data
            .map { prefs ->
                TranslationSettings(
                    enabled = prefs[KEY_TRANSLATE_TITLES] ?: DEFAULT.translateTitles,
                    targetLanguage = prefs[KEY_TRANSLATION_TARGET] ?: DEFAULT.translationTarget,
                )
            }
            .distinctUntilChanged()

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
            prefs[KEY_WISHLIST_ONLY]      = s.wishlistOnly
            prefs[KEY_NOT_WISHLIST_ONLY]  = s.notWishlistOnly
            prefs[KEY_IGNORED_ONLY]       = s.ignoredOnly
            if (s.canonicalTagIds.isEmpty()) {
                prefs.remove(KEY_CANONICAL_TAGS)
            } else {
                prefs[KEY_CANONICAL_TAGS] = s.canonicalTagIds.joinToString("\n")
            }
            if (s.selectedTagTokens.isEmpty()) {
                prefs.remove(KEY_SELECTED_TAGS)
            } else {
                prefs[KEY_SELECTED_TAGS] = s.selectedTagTokens.joinToString("\n")
            }
            if (s.tagGroups.isEmpty()) {
                prefs.remove(KEY_TAG_GROUPS)
            } else {
                prefs[KEY_TAG_GROUPS] = json.encodeToString(tagGroupsSerializer, s.tagGroups)
            }
            prefs[KEY_INCLUDE_SYNOPSIS] = s.includeSynopsis
            prefs[KEY_SEARCH_MODE] = s.searchMode.name
            prefs[KEY_MIN_POPULARITY] = s.minPopularity
            prefs[KEY_DATE_FIELD] = s.dateField.name
            prefs[KEY_DATE_RANGE] = s.dateRange.name
            prefs[KEY_TRANSLATE_TITLES]   = s.translateTitles
            prefs[KEY_TRANSLATION_TARGET] = s.translationTarget
        }
    }
}
