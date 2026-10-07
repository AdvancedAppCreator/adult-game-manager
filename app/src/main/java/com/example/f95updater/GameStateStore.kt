package com.example.f95updater

import android.content.Context
import org.json.JSONObject

/** First-class, user-assigned play state for a game. Purely organizational: it never hides a game
 *  and never affects update checks (per product decision, only non-games are excluded from checks). */
enum class GameState(val label: String) {
    None("None"),
    Playing("Playing"),
    Backlog("Backlog"),
    Completed("Completed"),
    OnHold("On hold"),
    Dropped("Dropped"),
    Archived("Archived");

    companion object {
        /** States a user can assign (excludes [None], which means "clear"). */
        val assignable: List<GameState> = listOf(Playing, Backlog, Completed, OnHold, Dropped, Archived)

        fun fromName(name: String?): GameState =
            entries.firstOrNull { it.name == name } ?: None
    }
}

/** Persists the [GameState] per installed game, keyed by packageName (SharedPreferences JSON). */
object GameStateStore {
    private const val PREFS = "game_states"
    private const val KEY = "states_json"

    fun load(context: Context): Map<String, GameState> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { pkg ->
                    val state = GameState.fromName(obj.optString(pkg))
                    if (state != GameState.None) put(pkg, state)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(context: Context, map: Map<String, GameState>) {
        val obj = JSONObject()
        map.forEach { (pkg, state) -> if (state != GameState.None) obj.put(pkg, state.name) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, obj.toString()).apply()
    }

    /** Copies state to [newPackageName] when absent, retaining the old key for legacy backups. */
    fun rekeyPackageName(context: Context, legacyPackageName: String, newPackageName: String): Boolean {
        require(legacyPackageName.isNotBlank() && newPackageName.isNotBlank())
        if (legacyPackageName == newPackageName) return false
        val current = load(context)
        val state = current[legacyPackageName] ?: return false
        if (newPackageName in current) return false
        save(context, current + (newPackageName to state))
        return true
    }

    /** Applies [state] to [packages] (or clears when [state] is [GameState.None]) over [current]. */
    fun applied(
        current: Map<String, GameState>,
        packages: Collection<String>,
        state: GameState,
    ): Map<String, GameState> {
        val updated = current.toMutableMap()
        for (pkg in packages) {
            if (state == GameState.None) updated.remove(pkg) else updated[pkg] = state
        }
        return updated
    }

    /** Counts of each assigned state across the map (excludes [GameState.None]). */
    fun counts(map: Map<String, GameState>): Map<GameState, Int> =
        map.values.groupingBy { it }.eachCount()
}
