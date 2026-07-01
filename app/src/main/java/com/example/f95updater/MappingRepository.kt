package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val Context.dataStore by preferencesDataStore("adult_game_manager")
private val MAPPINGS_KEY = stringPreferencesKey("mappings_json")
private val HIDDEN_KEY = stringPreferencesKey("hidden_packages")

/** Backup envelope: includes mappings + hidden list + JoiPlay backup snapshot + version overrides. */
@Serializable
data class BackupBundle(
    val version: Int = 2,
    val mappings: Map<String, AppMapping> = emptyMap(),
    val hidden: List<String> = emptyList(),
    /** Raw games.json from .joiback (null if no backup imported). */
    val joiplayGamesJson: String? = null,
    /** Raw metadata.json from .joiback's metadata.zip (used as catalog fallback for ?-version games). */
    val joiplayMetadataJson: String? = null,
    /** Raw configuration/settings.json from .joiback, used for JoiPlay runtime launch settings. */
    val joiplaySettingsJson: String? = null,
    /** Per-game version overrides user picked via the conflict dialog. */
    val joiplayVersionOverrides: Map<String, String> = emptyMap(),
    /** JoiPlay game IDs the user previously deleted. Carried across export/import so
     *  restored backups don't resurrect rows the user already removed. */
    val joiplayDeletedIds: List<String> = emptyList(),
)

data class ImportSummary(
    val mappings: Int,
    val hidden: Int,
    val joiplayGames: Int,
    val joiplayOverrides: Int,
)

/** Outcome of reconciling persisted mappings against the live scan. */
data class PruneStaleResult(val removed: Int, val retainedWithData: Int)

/** Plan produced by [computeStalePrune]: which mapping keys to delete plus a
 *  count of stale-but-preserved mappings (kept because they carry user data). */
internal data class StalePrunePlan(
    val removeKeys: Set<String>,
    val retainedWithData: Int,
)

/** A mapping carries user-authored data worth preserving even when its game is
 *  (temporarily) absent from the scan. Such mappings are never auto-deleted; the
 *  install badge is cleared for them via the live-scan derivation instead. */
internal fun AppMapping.hasUserData(): Boolean =
    personalRating != null ||
        personalNotes.isNotBlank() ||
        userStatus != UserGameStatus.None ||
        manualCorrectionNote.isNotBlank() ||
        manualInstalledVersion.isNotBlank() ||
        manualInstalledDate > 0L ||
        matchSource?.startsWith("manual") == true

/** Pure reconciliation decision. A mapping is stale (deletable) only when its
 *  game is absent from [installedPackageNames] AND the source that owns it
 *  actually produced results this scan ([androidScanned]/[joiplayScanned]) —
 *  otherwise a denied permission or an unloaded JoiPlay backup would be misread
 *  as "everything uninstalled" and wipe good mappings. Mappings with user data
 *  are retained, not removed. */
internal fun computeStalePrune(
    mappings: Map<String, AppMapping>,
    installedPackageNames: Set<String>,
    androidScanned: Boolean,
    joiplayScanned: Boolean,
): StalePrunePlan {
    val remove = HashSet<String>()
    var retained = 0
    for ((pkg, mapping) in mappings) {
        if (pkg in installedPackageNames) continue
        val isJoiplay = pkg.startsWith("joiplay:")
        if (isJoiplay && !joiplayScanned) continue
        if (!isJoiplay && !androidScanned) continue
        if (mapping.hasUserData()) {
            retained++
            continue
        }
        remove += pkg
    }
    return StalePrunePlan(remove, retained)
}

class MappingRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), AppMapping.serializer())

    val mappings: Flow<Map<String, AppMapping>> =
        context.dataStore.data.map { prefs ->
            prefs[MAPPINGS_KEY]?.let { runCatching { json.decodeFromString(mapSer, it) }.getOrNull() } ?: emptyMap()
        }

    val hidden: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            prefs[HIDDEN_KEY]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }

    suspend fun get(): Map<String, AppMapping> = mappings.first()
    suspend fun getHidden(): Set<String> = hidden.first()

    suspend fun hide(packageName: String) {
        val current = getHidden().toMutableSet().apply { add(packageName) }
        context.dataStore.edit { it[HIDDEN_KEY] = current.joinToString("\n") }
    }

    suspend fun unhide(packageName: String) {
        val current = getHidden().toMutableSet().apply { remove(packageName) }
        context.dataStore.edit { it[HIDDEN_KEY] = current.joinToString("\n") }
    }

    suspend fun upsert(mapping: AppMapping) {
        val current = get().toMutableMap()
        current[mapping.packageName] = mapping
        save(current)
    }

    suspend fun remove(packageName: String) {
        val current = get().toMutableMap()
        current.remove(packageName)
        save(current)
    }

    /** Reconcile persisted mappings against the live scan: delete mappings whose
     *  game is no longer installed and that hold no user data. Non-destructive
     *  for mappings carrying ratings/notes/status/manual choices, and guarded so
     *  a source that produced no results this scan can't trigger deletions. */
    suspend fun pruneStale(
        installedPackageNames: Set<String>,
        androidScanned: Boolean,
        joiplayScanned: Boolean,
    ): PruneStaleResult {
        val current = get()
        val plan = computeStalePrune(current, installedPackageNames, androidScanned, joiplayScanned)
        if (plan.removeKeys.isNotEmpty()) {
            save(current.filterKeys { it !in plan.removeKeys })
        }
        return PruneStaleResult(plan.removeKeys.size, plan.retainedWithData)
    }

    suspend fun importAll(incoming: Map<String, AppMapping>, replace: Boolean) {
        val merged = if (replace) incoming.toMutableMap() else get().toMutableMap().apply { putAll(incoming) }
        save(merged)
    }

    suspend fun resetAllAcknowledgements() {
        val current = get()
        val cleared = current.mapValues { (_, v) -> v.copy(acknowledgedVersion = null) }
        save(cleared)
    }

    /** Exports mappings + hidden + JoiPlay backup snapshot + version overrides as one bundle. */
    suspend fun exportJson(): String {
        val bundle = BackupBundle(
            mappings = get(),
            hidden = getHidden().toList(),
            joiplayGamesJson = JoiPlayBackupReader.cachedGamesJson(context),
            joiplayMetadataJson = JoiPlayBackupReader.cachedMetadataJson(context),
            joiplaySettingsJson = JoiPlayBackupReader.cachedSettingsJson(context),
            joiplayVersionOverrides = JoiPlayVersionOverrides.loadAll(context),
            joiplayDeletedIds = JoiPlayBackupReader.deletedIds(context).toList(),
        )
        return json.encodeToString(BackupBundle.serializer(), bundle)
    }

    /** Accepts either a BackupBundle (new format) or a bare {pkg: AppMapping} map (legacy/PC-tool format).
     *  Returns ImportSummary describing what was restored. */
    suspend fun importJson(text: String, replace: Boolean): ImportSummary {
        val element = json.parseToJsonElement(text)
        if (element is kotlinx.serialization.json.JsonArray) {
            error("This is an installed-apps export (a list), not a backup file.")
        }
        val obj = element as? JsonObject ?: error("Expected a JSON object at top level.")
        val looksLikeBundle = obj.containsKey("mappings") || obj.containsKey("hidden") || obj.containsKey("version")
        if (looksLikeBundle) {
            val bundle = json.decodeFromString(BackupBundle.serializer(), text)
            importAll(bundle.mappings, replace)
            val hiddenSet = if (replace) bundle.hidden.toSet()
                else getHidden() + bundle.hidden
            context.dataStore.edit { it[HIDDEN_KEY] = hiddenSet.joinToString("\n") }
            // Restore JoiPlay backup snapshot (only if present in this bundle).
            val restoredJoiplay = JoiPlayBackupReader.restoreFromBackup(
                context, bundle.joiplayGamesJson, bundle.joiplayMetadataJson, bundle.joiplaySettingsJson,
            )
            // Restore per-game version overrides.
            for ((id, ver) in bundle.joiplayVersionOverrides) {
                JoiPlayVersionOverrides.set(context, id, ver)
            }
            // Restore previously-deleted JoiPlay game IDs so rows the user removed
            // before the export stay removed after import.
            for (id in bundle.joiplayDeletedIds) {
                JoiPlayBackupReader.markDeleted(context, id)
            }
            // Validate: walk every JoiPlay game from the restored snapshot and mark
            // missing folders as deleted. This catches the "imported backup brought
            // back games whose folders are no longer on disk" case the user reported.
            val prunedJoiplay = runCatching {
                JoiPlayBackupReader.pruneMissingFolders(context)
            }.getOrDefault(0)
            if (prunedJoiplay > 0) {
                AppLog.i("Import", "Post-import prune removed $prunedJoiplay missing JoiPlay folders")
            }
            // Validate Android-side: drop mappings whose package is no longer installed
            // on THIS device. Keeps storage clean; reinstalls can re-match via catalog.
            val pm = context.packageManager
            val prunedAndroid = run {
                val current = get().toMutableMap()
                val before = current.size
                val it = current.entries.iterator()
                while (it.hasNext()) {
                    val (pkg, _) = it.next()
                    if (pkg.startsWith("joiplay:")) continue
                    val installed = runCatching {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0); true
                    }.getOrDefault(false)
                    if (!installed) it.remove()
                }
                if (current.size != before) save(current)
                before - current.size
            }
            if (prunedAndroid > 0) {
                AppLog.i("Import", "Post-import prune removed $prunedAndroid mappings for uninstalled Android packages")
            }
            return ImportSummary(
                mappings = bundle.mappings.size,
                hidden = bundle.hidden.size,
                joiplayGames = restoredJoiplay,
                joiplayOverrides = bundle.joiplayVersionOverrides.size,
            )
        }
        // Legacy map format: {pkg: AppMapping, ...}
        val map = json.decodeFromString(mapSer, text)
        importAll(map, replace)
        return ImportSummary(mappings = map.size, hidden = 0, joiplayGames = 0, joiplayOverrides = 0)
    }

    private suspend fun save(map: Map<String, AppMapping>) {
        context.dataStore.edit { it[MAPPINGS_KEY] = json.encodeToString(mapSer, map) }
    }
}
