package com.example.f95updater

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PendingManagedWinlatorCreate(
    val kind: WinlatorOperationKind,
    val gameId: String,
    val title: String,
    val managedGameId: String,
    val rollback: ManagedRunnerRollback? = null,
    val launchAfterCreate: Boolean = false,
    val detectedLanguage: String? = null,
    val recoveryCandidate: WinlatorPortableRecoveryCandidate? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

object ManagedWinlatorPendingStore {
    private const val PREFS = "managed_winlator_pending"
    private const val KEY = "create_json"
    private const val MAX_AGE_MS = 60L * 60L * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    fun save(context: Context, operation: PendingWinlatorOperation) {
        val managedGameId = operation.managedGameId ?: return
        require(
            operation.kind == WinlatorOperationKind.CreatePortable ||
                operation.kind == WinlatorOperationKind.CreateInstaller ||
                operation.kind == WinlatorOperationKind.RecoverPortable,
        ) { "Only managed Winlator create or recovery operations can be persisted." }
        val value = PendingManagedWinlatorCreate(
            kind = operation.kind,
            gameId = operation.gameId,
            title = operation.title,
            managedGameId = managedGameId,
            rollback = operation.managedRunnerRollback,
            launchAfterCreate = operation.launchAfterCreate,
            detectedLanguage = operation.detectedLanguage,
            recoveryCandidate = operation.recoveryCandidate,
        )
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(PendingManagedWinlatorCreate.serializer(), value))
            .commit()
    }

    fun load(context: Context): PendingWinlatorOperation? {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY, null) ?: return null
        val value = runCatching {
            json.decodeFromString(PendingManagedWinlatorCreate.serializer(), raw)
        }.getOrElse {
            preferences.edit().remove(KEY).commit()
            return null
        }
        if (System.currentTimeMillis() - value.savedAt > MAX_AGE_MS) {
            preferences.edit().remove(KEY).commit()
            return null
        }
        return PendingWinlatorOperation(
            kind = value.kind,
            gameId = value.gameId,
            title = value.title,
            managedGameId = value.managedGameId,
            managedRunnerRollback = value.rollback,
            launchAfterCreate = value.launchAfterCreate,
            detectedLanguage = value.detectedLanguage,
            recoveryCandidate = value.recoveryCandidate,
        )
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .commit()
    }
}
