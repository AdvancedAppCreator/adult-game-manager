package com.example.f95updater

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's filter/sort state so it survives process death.
 * (rememberSaveable only survives configuration changes — when a JoiPlay game launches and
 * the OS kills our backgrounded process, the saved-state bundle is sometimes dropped.)
 */
object FilterPrefs {
    private const val FILE = "installed_filters"
    private const val KEY_SORT = "sort_key"
    private const val KEY_SORT_DESC = "sort_desc"
    private const val KEY_NAME = "name_filter"
    private const val KEY_SOURCE = "source_filter"
    private const val KEY_STATUSES = "status_filters"
    private const val KEY_TAGS = "tag_filters"
    private const val KEY_SHOW_HIDDEN = "show_hidden"
    private const val KEY_MANUAL_ONLY = "manual_only"
    private const val KEY_THREAD_UPDATED_AFTER_INSTALL = "thread_updated_after_install"
    private const val KEY_LAYOUT_MODE = "layout_mode"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class State(
        val sortKey: SortKey = SortKey.Name,
        val sortDesc: Boolean = false,
        val nameFilter: String = "",
        val sourceFilter: AppSource? = null,
        val activeStatuses: List<UpdateStatus> = emptyList(),
        val activeTags: List<String> = emptyList(),
        val showHidden: Boolean = false,
        val manualOnly: Boolean = false,
        val threadUpdatedAfterInstallOnly: Boolean = false,
        val layoutMode: LibraryLayoutMode = LibraryLayoutMode.List,
    )

    fun load(c: Context): State {
        val p = prefs(c)
        return State(
            sortKey = when (val rawSort = p.getString(KEY_SORT, SortKey.Name.name)) {
                "JoiPlaySize" -> SortKey.Size
                else -> runCatching { SortKey.valueOf(rawSort ?: SortKey.Name.name) }.getOrDefault(SortKey.Name)
            },
            sortDesc = p.getBoolean(KEY_SORT_DESC, false),
            nameFilter = p.getString(KEY_NAME, "") ?: "",
            sourceFilter = p.getString(KEY_SOURCE, null)?.let { name ->
                runCatching { AppSource.valueOf(name) }.getOrNull()
            },
            activeStatuses = (p.getStringSet(KEY_STATUSES, emptySet()) ?: emptySet())
                .mapNotNull { runCatching { UpdateStatus.valueOf(it) }.getOrNull() }
                .filterNot { it == UpdateStatus.CheckFailed || it == UpdateStatus.Unknown },
            activeTags = (p.getStringSet(KEY_TAGS, emptySet()) ?: emptySet()).toList(),
            showHidden = p.getBoolean(KEY_SHOW_HIDDEN, false),
            manualOnly = p.getBoolean(KEY_MANUAL_ONLY, false),
            threadUpdatedAfterInstallOnly = p.getBoolean(KEY_THREAD_UPDATED_AFTER_INSTALL, false),
            layoutMode = p.getString(KEY_LAYOUT_MODE, LibraryLayoutMode.List.name)?.let { name ->
                runCatching { LibraryLayoutMode.valueOf(name) }.getOrNull()
            } ?: LibraryLayoutMode.List,
        )
    }

    fun save(c: Context, s: State) {
        prefs(c).edit().apply {
            putString(KEY_SORT, s.sortKey.name)
            putBoolean(KEY_SORT_DESC, s.sortDesc)
            putString(KEY_NAME, s.nameFilter)
            if (s.sourceFilter != null) putString(KEY_SOURCE, s.sourceFilter.name) else remove(KEY_SOURCE)
            putStringSet(KEY_STATUSES, s.activeStatuses.map { it.name }.toSet())
            putStringSet(KEY_TAGS, s.activeTags.toSet())
            putBoolean(KEY_SHOW_HIDDEN, s.showHidden)
            putBoolean(KEY_MANUAL_ONLY, s.manualOnly)
            putBoolean(KEY_THREAD_UPDATED_AFTER_INSTALL, s.threadUpdatedAfterInstallOnly)
            putString(KEY_LAYOUT_MODE, s.layoutMode.name)
        }.apply()
    }
}
