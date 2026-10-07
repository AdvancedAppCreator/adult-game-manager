package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.managedGameDataStore by preferencesDataStore("managed_games")
private val MANAGED_GAMES_KEY = stringPreferencesKey("managed_games_v1_json")

@Serializable
internal data class ManagedGameEnvelope(
    val version: Int = MANAGED_GAME_SCHEMA_VERSION,
    val games: List<ManagedGame> = emptyList(),
    val legacyMigrationCompleted: Boolean = false,
)

data class ManagedGameMigrationResult(
    val alreadyCompleted: Boolean,
    val supplied: Int,
    val created: Int,
    val merged: Int,
    val games: List<ManagedGame>,
)

class ManagedGameStore(
    context: Context,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val patchJournal: ManagedGamePatchJournal = PatchTransactionJournal(context),
) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "runner"
    }

    val games: Flow<List<ManagedGame>> = appContext.managedGameDataStore.data.map { prefs ->
        decode(prefs[MANAGED_GAMES_KEY]).games.map(::validateManagedGame)
    }

    suspend fun get(): List<ManagedGame> = games.first()

    suspend fun find(id: String): ManagedGame? = get().firstOrNull { it.id == id }

    suspend fun create(draft: ManagedGameDraft): ManagedGame {
        var result: ManagedGame? = null
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            val canonicalPath = canonicalManagedGamePath(draft.storagePath)
            require(envelope.games.none { it.canonicalPath == canonicalPath }) {
                "A managed game already exists at $canonicalPath"
            }
            val created = buildManagedGame(draft, null, idFactory, clock())
            writeOwnershipMarker(created)
            result = created
            prefs[MANAGED_GAMES_KEY] = encode(envelope.copy(games = envelope.games + created))
        }
        return checkNotNull(result)
    }

    /** Additive, repeatable path-based upsert. Existing UUIDs and runner bindings are retained. */
    suspend fun upsert(draft: ManagedGameDraft): ManagedGame {
        var result: ManagedGame? = null
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            val path = canonicalManagedGamePath(draft.storagePath)
            val existing = envelope.games.firstOrNull { it.canonicalPath == path }
            val updated = buildManagedGame(draft, existing, idFactory, clock())
            writeOwnershipMarker(updated)
            result = updated
            prefs[MANAGED_GAMES_KEY] = encode(
                envelope.copy(games = envelope.games.filterNot { it.id == updated.id } + updated),
            )
        }
        return checkNotNull(result)
    }

    suspend fun importOrUpsert(drafts: List<ManagedGameDraft>): List<ManagedGame> {
        var result = emptyList<ManagedGame>()
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            result = upsertManagedGames(envelope.games, drafts, idFactory, clock())
            result.forEach(::writeOwnershipMarkerIfPresent)
            prefs[MANAGED_GAMES_KEY] = encode(envelope.copy(games = result))
        }
        return result
    }

    suspend fun restore(
        games: List<ManagedGame>,
        replace: Boolean,
        legacyMigrationCompleted: Boolean = true,
    ): List<ManagedGame> {
        val incoming = games.map(::validateManagedGame)
        require(incoming.distinctBy { it.canonicalPath }.size == incoming.size) {
            "Managed-game backup contains duplicate storage paths."
        }
        var result = emptyList<ManagedGame>()
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            result = if (replace) {
                incoming
            } else {
                val byPath = envelope.games.associateBy { it.canonicalPath }.toMutableMap()
                for (restored in incoming) {
                    val existing = byPath[restored.canonicalPath]
                    byPath[restored.canonicalPath] = if (existing == null) {
                        restored
                    } else {
                        validateManagedGame(
                            restored.copy(
                                id = existing.id,
                                createdAt = existing.createdAt,
                                runnerBindings = mergeManagedRunnerBindings(
                                    existing.runnerBindings,
                                    restored.runnerBindings,
                                ),
                            ),
                        )
                    }
                }
                byPath.values.toList()
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            result.forEach(::reconcileOwnershipMarkerForRestore)
            prefs[MANAGED_GAMES_KEY] = encode(
                envelope.copy(
                    games = result,
                    legacyMigrationCompleted = legacyMigrationCompleted,
                ),
            )
        }
        return result
    }

    suspend fun update(game: ManagedGame): ManagedGame {
        val validated = validateManagedGame(game.copy(updatedAt = clock()))
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            require(envelope.games.any { it.id == game.id }) { "Unknown managed-game id: ${game.id}" }
            require(envelope.games.none {
                it.id != game.id && it.canonicalPath == validated.canonicalPath
            }) { "Another managed game already uses ${validated.canonicalPath}" }
            prefs[MANAGED_GAMES_KEY] = encode(
                envelope.copy(games = envelope.games.map { if (it.id == game.id) validated else it }),
            )
        }
        return validated
    }

    /**
     * Moves an existing managed game to a new folder without changing its identity. The ownership
     * marker for the new folder (same UUID, new canonical path) is regenerated before the record is
     * committed, so a crash can never leave a committed record without its marker.
     *
     * Refuses while the game still has an unresolved patch transaction, because that transaction's
     * private backup is the only copy of the original files. Once the move is committed, every
     * rollback point recorded for the old folder is invalidated and its backup deleted, so it can
     * never be restored into a folder the game has left.
     */
    suspend fun relocate(
        expectedOldCanonicalPath: String,
        replacement: ManagedGame,
    ): ManagedGame = patchJournal.withLifecycleGuard(replacement.id) { patchCleanup ->
        var result: ManagedGame? = null
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            val relocated = planManagedGameRelocation(
                envelope.games,
                expectedOldCanonicalPath,
                replacement,
                clock(),
            )
            writeOwnershipMarker(relocated)
            result = relocated
            prefs[MANAGED_GAMES_KEY] = encode(
                envelope.copy(games = envelope.games.map { if (it.id == relocated.id) relocated else it }),
            )
        }
        val relocated = checkNotNull(result)
        val oldPath = canonicalManagedGamePath(expectedOldCanonicalPath)
        if (oldPath != relocated.canonicalPath) {
            patchCleanup.invalidateRollbackPoints(
                relocated.id,
                oldPath,
                "${relocated.label} was moved to ${relocated.storagePath}, so the patch rollback " +
                    "point recorded for $oldPath can no longer be applied.",
            )
        }
        relocated
    }

    suspend fun updateWinlatorBindings(
        updates: Map<String, ManagedRunnerBinding.Winlator>,
    ): List<ManagedGame> {
        if (updates.isEmpty()) return get()
        var result = emptyList<ManagedGame>()
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            val updatedAt = clock()
            result = envelope.games.map { game ->
                val replacement = updates[game.id] ?: return@map game
                if (game.runnerBindings.none { it.kind == ManagedRunnerKind.Winlator }) {
                    return@map game
                }
                validateManagedGame(
                    game.copy(
                        updatedAt = updatedAt,
                        runnerBindings = game.runnerBindings.map {
                            if (it.kind == ManagedRunnerKind.Winlator) replacement else it
                        },
                    ),
                )
            }
            prefs[MANAGED_GAMES_KEY] = encode(envelope.copy(games = result))
        }
        return result
    }

    /**
     * Removes the managed-game record. Refuses while the game still has an unresolved patch
     * transaction, and invalidates the rollback points of the folder it is leaving behind so a later
     * rollback can never recreate a deleted game's folder.
     */
    suspend fun delete(id: String): Boolean = patchJournal.withLifecycleGuard(id) { patchCleanup ->
        var removed: ManagedGame? = null
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            val target = envelope.games.firstOrNull { it.id == id }
            removed = target
            if (target != null) {
                prefs[MANAGED_GAMES_KEY] = encode(
                    envelope.copy(games = envelope.games.filterNot { it.id == id }),
                )
            }
        }
        val deleted = removed
        if (deleted != null) {
            patchCleanup.invalidateRollbackPoints(
                deleted.id,
                deleted.canonicalPath,
                "${deleted.label} was removed from the AGM library, so its patch rollback point can " +
                    "no longer be applied.",
            )
        }
        deleted != null
    }

    /**
     * Refuses when [id] still has an unresolved patch transaction. Callers that touch the game's
     * files before calling [delete] must check this first, so nothing is destroyed while a patch
     * backup is the only copy of the original files.
     */
    suspend fun requireNoUnresolvedPatchTransaction(id: String) {
        patchJournal.withLifecycleGuard(id) { }
    }

    suspend fun setDefaultRunner(id: String, runner: ManagedRunnerKind): ManagedGame {
        val game = requireNotNull(find(id)) { "Unknown managed-game id: $id" }
        return update(game.copy(defaultRunner = runner))
    }

    suspend fun setRunnerState(
        id: String,
        runner: ManagedRunnerKind,
        enabled: Boolean,
        compatible: Boolean,
    ): ManagedGame {
        val game = requireNotNull(find(id)) { "Unknown managed-game id: $id" }
        require(game.runnerBindings.any { it.kind == runner }) { "$runner is not bound to $id" }
        return update(
            game.copy(
                runnerBindings = game.runnerBindings.map {
                    if (it.kind == runner) it.withState(enabled, compatible) else it
                },
            ),
        )
    }

    /**
     * One-time migration of caller-supplied, already discovered legacy rows. This never queries UI
     * or runner state. Validation happens before the completion marker is persisted.
     */
    suspend fun migrateLegacyOnce(validatedLegacyApps: List<InstalledApp>): ManagedGameMigrationResult {
        val drafts = validatedLegacyApps.map(::legacyInstalledAppToManagedDraft)
        var result: ManagedGameMigrationResult? = null
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            if (envelope.legacyMigrationCompleted) {
                result = ManagedGameMigrationResult(true, validatedLegacyApps.size, 0, 0, envelope.games)
                return@edit
            }
            val previousPaths = envelope.games.mapTo(hashSetOf()) { it.canonicalPath }
            val migrated = upsertManagedGames(envelope.games, drafts, idFactory, clock())
            val incomingPaths = drafts.mapTo(hashSetOf()) { canonicalManagedGamePath(it.storagePath) }
            val created = incomingPaths.count { it !in previousPaths }
            result = ManagedGameMigrationResult(
                alreadyCompleted = false,
                supplied = validatedLegacyApps.size,
                created = created,
                merged = validatedLegacyApps.size - created,
                games = migrated,
            )
            migrated.forEach(::writeOwnershipMarker)
            prefs[MANAGED_GAMES_KEY] = encode(envelope.copy(games = migrated))
        }
        return checkNotNull(result)
    }

    suspend fun completeLegacyMigration() {
        appContext.managedGameDataStore.edit { prefs ->
            val envelope = decode(prefs[MANAGED_GAMES_KEY])
            prefs[MANAGED_GAMES_KEY] = encode(envelope.copy(legacyMigrationCompleted = true))
        }
    }

    suspend fun isLegacyMigrationCompleted(): Boolean {
        val prefs = appContext.managedGameDataStore.data.first()
        return decode(prefs[MANAGED_GAMES_KEY]).legacyMigrationCompleted
    }

    private fun decode(raw: String?): ManagedGameEnvelope {
        val envelope = raw?.let { json.decodeFromString(ManagedGameEnvelope.serializer(), it) }
            ?: ManagedGameEnvelope()
        require(envelope.version in 1..MANAGED_GAME_SCHEMA_VERSION) {
            "Unsupported managed-game store version: ${envelope.version}"
        }
        return envelope
    }

    private fun encode(envelope: ManagedGameEnvelope): String =
        json.encodeToString(ManagedGameEnvelope.serializer(), envelope)

    private fun writeOwnershipMarkerIfPresent(game: ManagedGame) {
        val directory = File(game.storagePath)
        if (!directory.exists()) return
        writeOwnershipMarker(game)
    }

    private fun writeOwnershipMarker(game: ManagedGame) {
        val directory = File(game.storagePath).canonicalFile
        require(directory.isDirectory && directory.canRead() && directory.canWrite()) {
            "Managed game folder must be readable and writable: ${game.storagePath}"
        }
        require(isSafeManagedGameDirectory(directory)) {
            "Refusing to manage a broad or system storage directory: ${directory.absolutePath}"
        }
        val marker = ManagedGameOwnership(
            id = game.id,
            canonicalPath = game.canonicalPath,
        )
        File(directory, MANAGED_GAME_OWNERSHIP_FILE).writeText(
            json.encodeToString(ManagedGameOwnership.serializer(), marker),
        )
    }

    private fun reconcileOwnershipMarkerForRestore(game: ManagedGame) {
        val directory = File(game.storagePath).canonicalFile
        if (!directory.exists()) return
        val markerFile = File(directory, MANAGED_GAME_OWNERSHIP_FILE)
        if (!markerFile.isFile) return
        val current = json.decodeFromString(
            ManagedGameOwnership.serializer(),
            markerFile.readText(),
        )
        require(current.canonicalPath == game.canonicalPath) {
            "Existing AGM ownership marker does not match ${game.storagePath}"
        }
        writeOwnershipMarker(game)
    }

    private fun isSafeManagedGameDirectory(directory: File): Boolean {
        if (directory.parentFile == null) return false
        val normalized = directory.invariantSeparatorsPath.trimEnd('/').lowercase()
        if (normalized in setOf(
                "/sdcard",
                "/storage/emulated/0",
                "/storage/self/primary",
            )
        ) {
            return false
        }
        if (
            Regex("^/storage/[^/]+$").matches(normalized) ||
            Regex("^/storage/emulated/\\d+$").matches(normalized) ||
            Regex("^/mnt/media_rw/[^/]+$").matches(normalized)
        ) {
            return false
        }
        return directory.name.lowercase() !in setOf(
            "alarms",
            "android",
            "audiobooks",
            "camera",
            "data",
            "dcim",
            "documents",
            "download",
            "downloads",
            "games",
            "movies",
            "music",
            "notifications",
            "pictures",
            "podcasts",
            "ringtones",
            "storage",
            "lost.dir",
        )
    }
}
