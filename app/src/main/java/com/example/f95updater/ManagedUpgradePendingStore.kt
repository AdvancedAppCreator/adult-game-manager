package com.example.f95updater

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ManagedUpgradePhase {
    /** New folder extracted; save migration has not been confirmed yet. */
    SavesPending,

    /** Saves copied and verified; the AGM store still points at the old folder. */
    StoreRelocationPending,

    /** A native Winlator re-point was requested and its outcome is unknown. */
    WinlatorRequested,

    /** A compensating Winlator re-point back to the old folder was requested. */
    WinlatorRollbackRequested,
}

@Serializable
data class ManagedUpgradeWinlatorPlan(
    val winlatorGameId: String,
    val oldGamePath: String,
    val oldExecutablePath: String,
    val newGamePath: String,
    val newExecutablePath: String,
)

@Serializable
data class ManagedUpgradePendingState(
    val schemaVersion: Int = 1,
    val phase: ManagedUpgradePhase,
    val managedGameId: String,
    val label: String,
    val oldRootPath: String,
    val oldCanonicalPath: String,
    val newRootPath: String,
    val newCanonicalPath: String,
    val sourceArchivePath: String? = null,
    /**
     * Whether the picked source archive may be deleted once this upgrade commits.
     *
     * Captured from [JoiPlaySettingsStore.deleteAfterInstall] when the upgrade transaction is
     * created and never re-read afterwards: a recovery that runs days later must honour the
     * preference that was in force when the user started *this* upgrade, not whatever the setting
     * says now. Records written before this field existed decode as false, so an upgrade journalled
     * by an older build keeps the user's archive instead of silently deleting it.
     */
    val deleteSourceArchive: Boolean = false,
    val saveItemsCopied: Int = 0,
    val originalGame: ManagedGame,
    val replacementGame: ManagedGame,
    val winlator: ManagedUpgradeWinlatorPlan? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

/**
 * Durable record of an in-flight managed-archive upgrade. Unlike [ManagedWinlatorPendingStore] this
 * survives process death indefinitely: it is cleared only after a successful commit or a confirmed
 * abort, because the recorded folders would otherwise be orphaned.
 */
object ManagedUpgradePendingStore {
    private const val PREFS = "managed_upgrade_pending"
    private const val KEY = "upgrade_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "runner"
    }

    internal fun encode(state: ManagedUpgradePendingState): String =
        json.encodeToString(ManagedUpgradePendingState.serializer(), state)

    internal fun decode(raw: String): ManagedUpgradePendingState =
        json.decodeFromString(ManagedUpgradePendingState.serializer(), raw)

    fun save(context: Context, state: ManagedUpgradePendingState) {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encode(state))
            .commit()
        check(saved) { "Could not persist the managed-upgrade recovery record." }
        AppLog.i(
            "ManagedUpgrade",
            "Persisted pending upgrade phase=${state.phase} game=${state.managedGameId} " +
                "old=${state.oldRootPath} new=${state.newRootPath} winlator=${state.winlator?.winlatorGameId}",
        )
    }

    fun load(context: Context): ManagedUpgradePendingState? {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY, null) ?: return null
        return runCatching {
            decode(raw)
        }.getOrElse {
            AppLog.w("ManagedUpgrade", "Discarding unreadable pending upgrade record", it)
            preferences.edit().remove(KEY).commit()
            null
        }
    }

    fun clear(context: Context) {
        val cleared = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .commit()
        if (cleared) {
            AppLog.i("ManagedUpgrade", "Cleared pending upgrade record")
        } else {
            AppLog.w("ManagedUpgrade", "Could not clear pending upgrade record")
        }
    }
}
