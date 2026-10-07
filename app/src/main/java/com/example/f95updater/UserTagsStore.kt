package com.example.f95updater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user-defined tags per installed game, keyed by packageName. Small and synchronous
 * (SharedPreferences JSON blob) — the set of installed games is tiny compared to the catalog.
 * Tags are normalized (trimmed, collapsed whitespace, lowercased) so filtering is predictable.
 */
object UserTagsStore {
    private const val PREFS = "user_tags"
    private const val KEY = "tags_json"

    fun load(context: Context): Map<String, Set<String>> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { pkg ->
                    val arr = obj.getJSONArray(pkg)
                    val tags = (0 until arr.length())
                        .mapNotNull { normalize(arr.optString(it)) }
                        .toCollection(linkedSetOf())
                    if (tags.isNotEmpty()) put(pkg, tags)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(context: Context, map: Map<String, Set<String>>) {
        val obj = JSONObject()
        map.forEach { (pkg, tags) ->
            val clean = tags.mapNotNull { normalize(it) }
            if (clean.isNotEmpty()) obj.put(pkg, JSONArray().apply { clean.forEach { put(it) } })
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, obj.toString())
            .apply()
    }

    /** Unions tags onto [newPackageName], retaining the old key for legacy backups. */
    fun rekeyPackageName(context: Context, legacyPackageName: String, newPackageName: String): Boolean {
        require(legacyPackageName.isNotBlank() && newPackageName.isNotBlank())
        if (legacyPackageName == newPackageName) return false
        val current = load(context)
        val legacy = current[legacyPackageName].orEmpty()
        if (legacy.isEmpty()) return false
        val merged = current[newPackageName].orEmpty() + legacy
        if (merged == current[newPackageName]) return false
        save(context, current + (newPackageName to merged))
        return true
    }

    /** Union of every user tag across all games (for filter chips). Sorted. */
    fun allTags(map: Map<String, Set<String>>): List<String> =
        map.values.flatten().toSortedSet().toList()

    /** Normalize a raw user tag: trim, collapse internal whitespace, lowercase. Empty -> null. */
    fun normalize(raw: String?): String? =
        raw?.trim()?.replace(Regex("\\s+"), " ")?.lowercase()?.takeIf { it.isNotEmpty() }
}
