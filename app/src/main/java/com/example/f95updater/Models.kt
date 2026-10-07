package com.example.f95updater

import kotlinx.serialization.Serializable

enum class AppSource {
    Android,
    Managed,
    JoiPlay,
    Winlator,
    Kirikiroid,
}

@Serializable
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Label shown on the user's home-screen / app drawer for this app's launcher
     *  activity, which can differ from [label] (the manifest application label).
     *  Example: app info shows "Awakening", but the launcher icon says "AWK". Null
     *  for apps with no launcher activity (services, JoiPlay games) or when it
     *  equals [label]. */
    val launcherLabel: String? = null,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val lastUsedTime: Long = 0L,
    val installedDateSource: String = "",
    val apkSize: Long = 0L,
    val dataSize: Long = 0L,
    val cacheSize: Long = 0L,
    val source: AppSource = AppSource.Android,
    /** AGM-owned identity for unified non-Android games. */
    val managedGameId: String? = null,
    /** Runner selected by default for a unified managed game. */
    val managedDefaultRunner: ManagedRunnerKind? = null,
    /** All runner bindings retained by the unified managed-game registry. */
    val managedRunnerBindings: List<ManagedRunnerBinding> = emptyList(),
    /** For JoiPlay games: absolute path to the game folder (from the backup). */
    val storagePath: String? = null,
    /** For JoiPlay games: folder name relative to the configured games root, used for SAF delete. */
    val storageFolderName: String? = null,
    /** For JoiPlay games: the JoiPlay-internal game id (from .joiback). */
    val joiPlayGameId: String? = null,
    /** For JoiPlay games: the engine type, e.g. rpgmmv, rpgmmz, renpy, rpgmxp, tyrano. */
    val joiPlayType: String? = null,
    /** For JoiPlay games: the execFile within the game folder. */
    val joiPlayExecFile: String? = null,
    /** For JoiPlay games: global settings JSON imported from configuration/settings.json in .joiback. */
    val joiPlaySettingsJson: String? = null,
    /** Original-language title read from a top-level readme.txt, when available. */
    val readmeTitle: String? = null,
    /** For Winlator games: stable managed-game id used by the authenticated API. */
    val winlatorGameId: String? = null,
    /** For Winlator games: private container id reported by Winlator. */
    val winlatorContainerId: Int? = null,
    /** For Winlator games: ready, setup_required, registration_missing, or installing. */
    val winlatorState: String? = null,
    /** For portable Winlator games: absolute executable path in shared storage. */
    val winlatorExecutablePath: String? = null,
    /** For installed Winlator games: executable path inside Wine. */
    val winlatorExecutableDosPath: String? = null,
    /** Winlator API v3 container policy: shared_default or isolated. */
    val winlatorContainerPolicy: String? = null,
    /** True when this record references an integration-scoped shared container. */
    val winlatorContainerShared: Boolean = false,
    /** Stable shared-container key. Numeric container ids are not stable shared identities. */
    val winlatorContainerKey: String? = null,
    /** Number of managed games currently referencing this container. */
    val winlatorContainerReferenceCount: Int? = null,
    /** Allocated bytes for the associated container, when Winlator can calculate it safely. */
    val winlatorContainerAllocatedSizeBytes: Long? = null,
    /** API v4 complete effective per-game Winlator configuration. */
    val winlatorConfigJson: String? = null,
    /** Canonical uppercase SHA-256 of [winlatorConfigJson]. */
    val winlatorConfigSha256: String? = null,
    /** Effective Windows compatibility version reported by Winlator (for example, win10). */
    val winlatorEffectiveWinVersion: String? = null,
    /** Provenance of the effective Windows version: explicit, default, or registry_legacy. */
    val winlatorWinVersionSource: String? = null,
    /** For legacy/unified Kirikiroid rows: startup file relative to or inside [storagePath]. */
    val kirikiroidStartupPath: String? = null,
    val kirikiroidEntryPoint: String? = null,
    val kirikiroidLaunchArguments: List<String> = emptyList(),
    val kirikiroidMetadata: Map<String, String> = emptyMap(),
) {
    val totalSize: Long get() = apkSize + dataSize + cacheSize
}

@Serializable
data class AppMapping(
    val packageName: String,
    val f95Url: String? = null,
    val lastSeenVersion: String? = null,
    val lastChecked: Long = 0L,
    /** Snapshot of lastSeenVersion that the user has ack'd as "installed".
     *  Update is flagged when lastSeenVersion != acknowledgedVersion. */
    val acknowledgedVersion: String? = null,
    /** Thread ID resolved against the cached catalog (for fast lookups). */
    val threadId: Int? = null,
    /** User marked this app as not in the catalog. Auto-matching skips it from now on. */
    val notOnF95: Boolean = false,
    /** "manual" mappings are preserved even when the catalog title no longer looks similar. */
    val matchSource: String? = null,
    /** User-owned lifecycle status, independent from the source update-check status. */
    val userStatus: UserGameStatus = UserGameStatus.None,
    /** Personal rating from 1..5. Null means unrated. */
    val personalRating: Int? = null,
    /** Private note shown only in this app and included in backups. */
    val personalNotes: String = "",
    /** Why the user manually chose/corrected this mapping/version. Preserved across auto-refresh. */
    val manualCorrectionNote: String = "",
    /** User-confirmed installed version. Applies while [manualInstalledVersionFingerprint] still matches the current app evidence. */
    val manualInstalledVersion: String = "",
    /** Fingerprint of the installed app evidence at the time [manualInstalledVersion] was set. */
    val manualInstalledVersionFingerprint: String = "",
    /** User-confirmed installed date in epoch millis. Applies while [manualInstalledDateFingerprint] still matches the current app evidence. */
    val manualInstalledDate: Long = 0L,
    /** Fingerprint of the installed app evidence at the time [manualInstalledDate] was set. */
    val manualInstalledDateFingerprint: String = "",
    /** Human-readable source for [manualInstalledDate], e.g. catalog published date or manually picked date. */
    val manualInstalledDateSource: String = "",
    /** Local app/JoiPlay identity tokens captured when this mapping was manually chosen. */
    val manualLocalIdentity: List<String> = emptyList(),
    /** Source-aware catalog id for manual/external mappings, including negative external ids. */
    val mappedCatalogId: Int? = null,
    val mappedCatalogSource: String? = null,
    val mappedCatalogSourceId: String? = null,
    /** Stable AGM cross-source group id for the associated catalog game. */
    val mappedAgmGroupId: String? = null,
    val mappedCatalogTitle: String = "",
    val mappedCatalogVersion: String? = null,
    val mappedCatalogUrl: String? = null,
    val mappedCatalogUpdatedAt: Long = 0L,
    val mappedCatalogPublishedAt: Long = 0L,
    val mappedCatalogModifiedAt: Long = 0L,
    val mappedCatalogCoverUrl: String? = null,
    val mappedCatalogThumbnailUrl: String? = null,
    /** Optional AGM-only game name. Blank means follow the associated catalog title,
     *  falling back to the locally detected name when no association exists. */
    val displayNameOverride: String = "",
)

@Serializable
enum class UserGameStatus(val label: String) {
    None("No status"),
    Playing("Playing"),
    WaitingForUpdate("Waiting for update"),
    Completed("Completed"),
    Archived("Archived"),
}

@Serializable
data class CatalogGame(
    val thread_id: Int,
    val title: String = "",
    val creator: String? = null,
    @Serializable(with = LooseStringSerializer::class)
    val version: String? = null,
    val category: String = "games",
    val prefixes: List<Int> = emptyList(),
    val tags: List<Int> = emptyList(),
    val rating: Double? = null,
    val views: Long = 0L,
    val likes: Long = 0L,
    val ts: Long = 0L,
    val publishedAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val cover: String? = null,
    val source: String = SOURCE_F95ZONE,
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    /** Server-compiled cross-source identity. Source records sharing this id are one game. */
    val agmGroupId: String? = null,
    /** Display-sized listing image, distinct from [cover] (the full-resolution cover used for
     *  details/full-screen). Nullable with a default so existing serialized CatalogGame JSON
     *  (which predates this field) keeps deserializing without changes. Falls back to [cover]
     *  at call sites when absent. */
    val thumbnailUrl: String? = null,
)

val CatalogGame.canonicalUrl: String
    get() = sourceUrl?.takeIf { it.isNotBlank() }
        ?: SourceRegistry.threadUrl(source, sourceId ?: thread_id.takeIf { it > 0 }?.toString())
        ?: if (source == SOURCE_F95ZONE && thread_id > 0) "https://f95zone.to/threads/$thread_id/" else ""

internal val CatalogGame.matchIdentityKey: String
    get() = agmGroupId?.trim()?.takeIf { it.isNotEmpty() }
        ?: catalogTextSearchKey(source, sourceId, thread_id)

val CatalogGame.f95ThreadIdOrNull: Int?
    get() = thread_id.takeIf { source == SOURCE_F95ZONE && it > 0 }

/** Resolved image URLs for rendering a [CatalogGame]: [thumbnailUrl] for list/grid listing
 *  surfaces (display-sized) and [coverUrl] for details/full-screen (full resolution). Each
 *  falls back to the other when only one is known, so older cached games without a distinct
 *  thumbnail still render in both contexts. */
data class CatalogImageUrls(val thumbnailUrl: String?, val coverUrl: String?)

/** Pure helper (nullable-receiver, testable) used by list/grid cards to pick which URL to
 *  request for the listing thumbnail vs. which to open on a full-screen cover click. */
fun catalogImageUrls(game: CatalogGame?): CatalogImageUrls {
    val cover = game?.cover?.takeIf { it.isNotBlank() }
    val thumb = game?.thumbnailUrl?.takeIf { it.isNotBlank() }
    if (cover == null && thumb == null) {
        // The otomi/kimochi crawlers often omit covers, but those entries are DLsite doujin (RJ)
        // products whose CDN image URL is derivable from the RJ code in the source URL.
        dlsiteImageUrls(game?.sourceUrl ?: game?.let { it.canonicalUrl })?.let { return it }
    }
    return CatalogImageUrls(
        thumbnailUrl = thumb ?: cover,
        coverUrl = cover ?: thumb,
    )
}

private val RJ_CODE_REGEX = Regex("""(?i)\bRJ(\d{6,8})\b""")

/**
 * Derives DLsite doujin cover/thumbnail CDN URLs from an RJ product code found in [url]
 * (e.g. `https://otomi-games.com/rj01568168/`). Returns null when no RJ code is present.
 * The CDN groups works into folders of 1000 (folder = ceil(id/1000)*1000), zero-padded to the
 * code's width. A resized variant is used for the listing thumbnail.
 */
fun dlsiteImageUrls(url: String?): CatalogImageUrls? {
    val match = url?.let { RJ_CODE_REGEX.find(it) } ?: return null
    val codeDigits = match.groupValues[1]
    val id = codeDigits.toLongOrNull() ?: return null
    val group = ((id + 999) / 1000) * 1000
    val product = "RJ$codeDigits"
    val folder = "RJ" + group.toString().padStart(codeDigits.length, '0')
    val base = "https://img.dlsite.jp"
    return CatalogImageUrls(
        thumbnailUrl = "$base/resize/images2/work/doujin/$folder/${product}_img_main_240x240.jpg",
        coverUrl = "$base/modpub/images2/work/doujin/$folder/${product}_img_main.jpg",
    )
}

/** Accepts JSON strings, numbers, or null and returns a String?.
 *  Works around F95's API occasionally returning numeric versions like 0.04. */
object LooseStringSerializer : kotlinx.serialization.KSerializer<String?> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "LooseString",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String? {
        val input = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: return decoder.decodeString()
        return when (val el = input.decodeJsonElement()) {
            is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (el.isString) el.content else el.content // works for both string + number primitives
            }
            else -> el.toString()
        }
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

@Serializable
data class CatalogLabels(
    val generated_at: String = "",
    val tags: Map<String, String> = emptyMap(),
    val prefixes: Map<String, String> = emptyMap(),
)

/** v2 namespaced labels (labels-v2.json): tag/prefix id->name maps scoped per source,
 *  so numeric ids from different sources can never collide. */
@Serializable
data class CatalogLabelsV2(
    val schemaVersion: Int = 2,
    val generated_at: String = "",
    val sources: Map<String, SourceLabels> = emptyMap(),
) {
    fun forSource(source: String): SourceLabels? = sources[source]

    /** Resolve a tag id to its display name within a specific source. */
    fun tagName(source: String, id: String): String? = sources[source]?.tags?.get(id)

    /** Resolve a prefix id to its display name within a specific source. */
    fun prefixName(source: String, id: String): String? = sources[source]?.prefixes?.get(id)

    /** All tag + prefix display names across every source (for autocomplete). */
    val allLabelNames: List<String>
        get() = sources.values.flatMap { it.tags.values + it.prefixes.values }
}

@Serializable
data class SourceLabels(
    val tags: Map<String, String> = emptyMap(),
    val prefixes: Map<String, String> = emptyMap(),
)

data class AppRow(
    val installed: InstalledApp,
    val mapping: AppMapping?,
    val status: UpdateStatus
)

internal fun defaultGameName(
    app: InstalledApp,
    mapping: AppMapping?,
    associatedCatalogTitle: String? = null,
): String =
    associatedCatalogTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: mapping?.mappedCatalogTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: app.label

internal fun effectiveGameName(
    app: InstalledApp,
    mapping: AppMapping?,
    associatedCatalogTitle: String? = null,
): String =
    mapping?.displayNameOverride?.trim()?.takeIf { it.isNotBlank() }
        ?: defaultGameName(app, mapping, associatedCatalogTitle)

internal fun displayNameOverrideFor(requestedName: String, defaultName: String): String =
    requestedName.trim().takeIf { it.isNotBlank() && it != defaultName } ?: ""

internal fun libraryNameSortKey(
    app: InstalledApp,
    mapping: AppMapping?,
    associatedCatalogTitle: String? = null,
): String = effectiveGameName(app, mapping, associatedCatalogTitle).lowercase()

internal fun matchesLibrarySearchText(
    app: InstalledApp,
    mapping: AppMapping?,
    associatedCatalogTitle: String?,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    return sequenceOf(
        effectiveGameName(app, mapping, associatedCatalogTitle),
        app.label,
        app.launcherLabel,
        associatedCatalogTitle,
        mapping?.mappedCatalogTitle,
        app.packageName,
        app.storagePath,
        app.storageFolderName,
    ).filterNotNull().any { it.contains(query, ignoreCase = true) }
}

enum class UpdateStatus { Unknown, UpToDate, UpdateAvailable, NotMapped, CheckFailed }

/** Extracts the numeric thread ID from a F95Zone URL like https://f95zone.to/threads/53058/ */
object F95UrlParser {
    private val pattern = Regex("""f95zone\.to/threads/(?:[^/]*\.)?(\d+)""")
    fun extractThreadId(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        return pattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
    }
}
