package com.example.f95updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Canonical id of the primary source. Sources are data-driven (string ids resolved
 *  against the downloaded registry), so adding a new source needs no new app build. */
const val SOURCE_F95ZONE = "f95zone"
const val SOURCE_ADULTGAMEWORLD = "adultgameworld"

@Serializable
data class SourceCatalogEntry(
    val source: String,
    val sourceId: String,
    val canonicalUrl: String,
    val title: String,
    val developer: String? = null,
    val versionText: String? = null,
    val publishedAt: String? = null,
    val modifiedAt: String? = null,
    val platforms: List<String> = emptyList(),
    @Serializable(with = LooseStringListSerializer::class)
    val tags: List<String> = emptyList(),
    val rating: Double? = null,
    val popularity: Double? = null,
    val coverUrl: String? = null,
)

object LooseStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = input.decodeJsonElement()
        return runCatching {
            element.jsonArray.mapNotNull { item: JsonElement ->
                item.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        delegate.serialize(encoder, value)
    }
}

@Serializable
data class SourceCatalogEnvelope(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val source: String,
    val count: Int,
    val entries: List<SourceCatalogEntry>,
)

/** v2 catalog registry (index-v2.json). The crawler emits one entry per source from
 *  its server-side registry; the app renders sources generically from these fields,
 *  so a brand-new source appears with NO new app build. */
@Serializable
data class CatalogSourceRegistry(
    val schemaVersion: Int = 2,
    val generatedAt: String = "",
    val catalogs: List<CatalogSourceInfo> = emptyList(),
)

@Serializable
data class CatalogSourceInfo(
    val id: String,
    val displayName: String = "",
    val url: String = "",
    val count: Int? = null,
    val generatedAt: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    /** Builds a canonical URL from an entry's sourceId via the "{id}" placeholder;
     *  null when entries already carry an absolute canonicalUrl. */
    val threadUrlTemplate: String? = null,
    /** Minimum app version that can render this source; null = any v2 client. */
    val minAppVersion: String? = null,
)

/** Runtime view of the downloaded source registry. Populated by [CatalogRepository]
 *  on load/sync and consulted wherever the app needs per-source display name, thread
 *  URL, priority, or gating — replacing the old closed enum + hardcoded source logic. */
object SourceRegistry {
    @Volatile private var infos: Map<String, CatalogSourceInfo> = emptyMap()

    fun update(catalogs: List<CatalogSourceInfo>) {
        infos = catalogs.associateBy { it.id }
    }

    fun all(): List<CatalogSourceInfo> = infos.values.sortedByDescending { it.priority }
    fun info(id: String): CatalogSourceInfo? = infos[id]

    fun displayName(id: String): String =
        infos[id]?.displayName?.takeIf { it.isNotBlank() }
            ?: id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun priority(id: String): Int = infos[id]?.priority ?: 0
    fun isEnabled(id: String): Boolean = infos[id]?.enabled ?: true
    fun minAppVersion(id: String): String? = infos[id]?.minAppVersion?.takeIf { it.isNotBlank() }

    /** Canonical URL for an entry whose own canonicalUrl is blank, using the source's
     *  thread-URL template. Returns null when no template is registered. */
    fun threadUrl(id: String, sourceId: String?): String? {
        val template = infos[id]?.threadUrlTemplate?.takeIf { it.isNotBlank() } ?: return null
        if (sourceId.isNullOrBlank()) return null
        return template.replace("{id}", sourceId)
    }
}

/** Display name for a source id, resolved from the downloaded registry (falls back to
 *  a capitalized id before the registry has loaded). */
val String.sourceDisplayName: String get() = SourceRegistry.displayName(this)

data class AdultGameWorldTitleParts(
    val gameTitle: String,
    val versionText: String?,
    val developer: String?,
)

object AdultGameWorldTitleParser {
    private val developerSuffix = Regex("""\s*\[([^\[\]]+)]\s*$""")
    private val updateSeparators = listOf(" - New Version ", " – New Version ", " - New Episode ", " – New Episode ")

    fun parse(rawTitle: String): AdultGameWorldTitleParts {
        val developer = developerSuffix.find(rawTitle)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        val withoutDeveloper = rawTitle.replace(developerSuffix, "").trim()
        val separator = updateSeparators.firstOrNull { withoutDeveloper.contains(it, ignoreCase = true) }
        if (separator != null) {
            val parts = withoutDeveloper.split(separator, limit = 2)
            val updatePrefix = separator.substringAfter("New ").trim()
            return AdultGameWorldTitleParts(
                gameTitle = parts[0].trim(' ', '-', '–'),
                versionText = "$updatePrefix ${parts.getOrElse(1) { "" }}".trim(),
                developer = developer,
            )
        }
        val parsedVersion = VersionCompare.extract(withoutDeveloper)
        val title = parsedVersion?.let { withoutDeveloper.removeSuffix(it).trim(' ', '-', '–') } ?: withoutDeveloper
        return AdultGameWorldTitleParts(title.ifBlank { withoutDeveloper }, parsedVersion, developer)
    }
}

object LatestReleaseSelector {
    fun choose(entries: List<SourceCatalogEntry>): SourceCatalogEntry? {
        if (entries.isEmpty()) return null
        return entries.maxWithOrNull { left, right ->
            compareVersionScores(comparableVersionScore(left.versionText), comparableVersionScore(right.versionText))
                .takeIf { it != 0 }
                ?: compareValues(left.modifiedAt ?: left.publishedAt ?: "", right.modifiedAt ?: right.publishedAt ?: "")
                    .takeIf { it != 0 }
                ?: compareValues(SourceRegistry.priority(left.source), SourceRegistry.priority(right.source))
        }
    }

    private fun comparableVersionScore(versionText: String?): List<Int> =
        versionText
            ?.let { Regex("""\d+""").findAll(it).map { m -> m.value.toIntOrNull() ?: 0 }.take(4).toList() }
            ?.takeIf { it.isNotEmpty() }
            ?: emptyList()

    private fun compareVersionScores(left: List<Int>, right: List<Int>): Int {
        val width = maxOf(left.size, right.size)
        for (index in 0 until width) {
            val comparison = compareValues(left.getOrElse(index) { 0 }, right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
