package com.example.f95updater

import android.content.Context
import org.json.JSONObject

/**
 * Records when the user last launched each game *through AGM*, keyed by packageName. Android usage
 * stats only cover installed APKs (and only with Usage-access granted); JoiPlay and Winlator games
 * report no usage at all. Persisting our own launch timestamps makes "last played" — and therefore
 * the stale-game surfacing — meaningful for every source.
 */
object LastPlayedStore {
    private const val PREFS = "last_played"
    private const val KEY = "played_json"

    fun load(context: Context): Map<String, Long> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { pkg ->
                    val ts = obj.optLong(pkg, 0L)
                    if (ts > 0L) put(pkg, ts)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun recordLaunch(context: Context, packageName: String, whenMs: Long = System.currentTimeMillis()) {
        if (packageName.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
        obj.put(packageName, whenMs)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    /** Copies the newer launch timestamp to [newPackageName] without removing the legacy key. */
    fun rekeyPackageName(context: Context, legacyPackageName: String, newPackageName: String): Boolean {
        require(legacyPackageName.isNotBlank() && newPackageName.isNotBlank())
        if (legacyPackageName == newPackageName) return false
        val current = load(context)
        val legacy = current[legacyPackageName] ?: return false
        val merged = maxOf(legacy, current[newPackageName] ?: 0L)
        if (current[newPackageName] == merged) return false
        recordLaunch(context, newPackageName, merged)
        return true
    }

    /** Effective last-used time: the later of Android usage stats and AGM's own launch record. */
    fun effectiveLastUsed(app: InstalledApp, played: Map<String, Long>): Long =
        maxOf(app.lastUsedTime, played[app.packageName] ?: 0L)
}
