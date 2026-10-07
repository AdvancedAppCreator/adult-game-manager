package com.example.f95updater

import android.content.Context

/** Small persistent flags for the storage/cleanup dashboard. */
object CleanupPrefs {
    private const val PREFS = "cleanup_prefs"
    private const val KEY_LEFTOVERS = "show_leftovers"

    /** Whether the dashboard surfaces the read-only "possible leftover folders" review. Default off. */
    fun showLeftovers(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEFTOVERS, false)

    fun setShowLeftovers(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LEFTOVERS, value).apply()
    }
}
