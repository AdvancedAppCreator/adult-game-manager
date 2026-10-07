package com.example.f95updater

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/**
 * Persistent, ordered list of candidate archive passwords.
 *
 * Used by the extraction flows to auto-try known passwords against an encrypted
 * archive before falling back to a manual prompt. Successful passwords are moved to
 * the front so the most-likely candidate is tried first next time.
 *
 * These are *public* archive passwords (e.g. a site name printed next to a download),
 * so plain [SharedPreferences] storage is fine.
 */
object PasswordVault {
    private const val PREFS = "agm_password_vault"
    private const val KEY = "passwords"
    private const val KEY_SEEDED = "seeded_v1"
    private const val KEY_OTOMI_SEEDED = "otomi_seeded_v1"

    /** Known-good passwords shipped by default. Kept deliberately tiny — the vault
     *  learns real passwords automatically whenever one succeeds. */
    private val DEFAULT_SEED = listOf("kimochi.info")

    private var prefs: SharedPreferences? = null

    /** Observable snapshot for Compose UIs (management screen, etc.). */
    val entries = mutableStateListOf<String>()

    /** Idempotent; safe to call on every app start. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        var list = decode(p.getString(KEY, null))
        val editor = p.edit()
        var changed = false
        if (!p.getBoolean(KEY_SEEDED, false)) {
            list = (list + DEFAULT_SEED).distinct()
            editor.putBoolean(KEY_SEEDED, true)
            changed = true
        }
        if (!p.getBoolean(KEY_OTOMI_SEEDED, false)) {
            list = (list + "otomi-games.com").distinct()
            editor.putBoolean(KEY_OTOMI_SEEDED, true)
            changed = true
        }
        if (changed) {
            editor.putString(KEY, encode(list)).apply()
        }
        entries.clear()
        entries.addAll(list)
    }

    fun all(): List<String> = entries.toList()

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Add a new password to the end if not already present. Returns true if added. */
    fun add(password: String): Boolean {
        val pwd = password.takeIf { it.isNotEmpty() } ?: return false
        if (entries.contains(pwd)) return false
        entries.add(pwd)
        persist()
        return true
    }

    /** Record a password that just worked: move it to the front (or insert it). */
    fun remember(password: String) {
        val pwd = password.takeIf { it.isNotEmpty() } ?: return
        entries.remove(pwd)
        entries.add(0, pwd)
        persist()
    }

    fun delete(password: String) {
        if (entries.remove(password)) persist()
    }

    private fun persist() {
        prefs?.edit()?.putString(KEY, encode(entries.toList()))?.apply()
    }

    private fun encode(list: List<String>): String = JSONArray(list).toString()

    private fun decode(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
