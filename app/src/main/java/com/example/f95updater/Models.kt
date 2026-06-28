package com.example.f95updater

import kotlinx.serialization.Serializable

enum class AppSource { Android, JoiPlay }

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
    val mappedCatalogTitle: String = "",
    val mappedCatalogVersion: String? = null,
    val mappedCatalogUrl: String? = null,
    val mappedCatalogUpdatedAt: Long = 0L,
    val mappedCatalogPublishedAt: Long = 0L,
    val mappedCatalogModifiedAt: Long = 0L,
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
)

val CatalogGame.canonicalUrl: String
    get() = sourceUrl?.takeIf { it.isNotBlank() }
        ?: SourceRegistry.threadUrl(source, sourceId ?: thread_id.takeIf { it > 0 }?.toString())
        ?: if (source == SOURCE_F95ZONE && thread_id > 0) "https://f95zone.to/threads/$thread_id/" else ""

val CatalogGame.f95ThreadIdOrNull: Int?
    get() = thread_id.takeIf { source == SOURCE_F95ZONE && it > 0 }

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

enum class UpdateStatus { Unknown, UpToDate, UpdateAvailable, NotMapped, CheckFailed }

/** Extracts the numeric thread ID from a F95Zone URL like https://f95zone.to/threads/53058/ */
object F95UrlParser {
    private val pattern = Regex("""f95zone\.to/threads/(?:[^/]*\.)?(\d+)""")
    fun extractThreadId(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        return pattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
    }
}
