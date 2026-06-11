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

@Serializable
enum class CatalogSource(val displayName: String) {
    @SerialName("f95zone")
    F95Zone("F95Zone"),
    @SerialName("adultgameworld")
    AdultGameWorld("AdultGameWorld"),
}

@Serializable
data class SourceCatalogEntry(
    val source: CatalogSource,
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
    val source: CatalogSource,
    val count: Int,
    val entries: List<SourceCatalogEntry>,
)

@Serializable
data class SourceCatalogIndex(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val catalogs: List<SourceCatalogIndexEntry>,
)

@Serializable
data class SourceCatalogIndexEntry(
    val source: CatalogSource,
    val url: String,
    val generatedAt: String? = null,
    val count: Int? = null,
)

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
                ?: compareValues(sourcePriority(left.source), sourcePriority(right.source))
        }
    }

    private fun sourcePriority(source: CatalogSource): Int = when (source) {
        CatalogSource.F95Zone -> 2
        CatalogSource.AdultGameWorld -> 1
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
