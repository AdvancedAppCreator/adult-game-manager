package com.example.f95updater

import android.content.Context

data class ManagedGameStateMigrationResult(
    val mappingOrHiddenCopied: Boolean,
    val lastPlayedCopied: Boolean,
    val gameStateCopied: Boolean,
    val userTagsCopied: Boolean,
    val versionOverrideCopied: Boolean,
)

/**
 * Copies every AGM user-state key associated with one legacy row to a managed game's stable key.
 * Old keys remain untouched so existing exports and rollback paths keep working.
 */
suspend fun migrateManagedGameUserState(
    context: Context,
    mappings: MappingRepository,
    legacyApp: InstalledApp,
    managedGame: ManagedGame,
): ManagedGameStateMigrationResult {
    require(
        legacyApp.source == AppSource.JoiPlay ||
            legacyApp.source == AppSource.Winlator ||
            legacyApp.source == AppSource.Kirikiroid,
    )
    val stableKey = managedGame.packageName
    val mappingCopied = mappings.rekeyPackageName(legacyApp.packageName, stableKey)
    val lastPlayedCopied = LastPlayedStore.rekeyPackageName(context, legacyApp.packageName, stableKey)
    val gameStateCopied = GameStateStore.rekeyPackageName(context, legacyApp.packageName, stableKey)
    val tagsCopied = UserTagsStore.rekeyPackageName(context, legacyApp.packageName, stableKey)
    val overrideCopied = legacyApp.joiPlayGameId?.takeIf { it.isNotBlank() }?.let {
        JoiPlayVersionOverrides.rekey(context, it, stableKey)
    } ?: false
    return ManagedGameStateMigrationResult(
        mappingOrHiddenCopied = mappingCopied,
        lastPlayedCopied = lastPlayedCopied,
        gameStateCopied = gameStateCopied,
        userTagsCopied = tagsCopied,
        versionOverrideCopied = overrideCopied,
    )
}
