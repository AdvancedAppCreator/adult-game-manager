package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * AGM 2.x compiled-feed reader: downloads and caches the server-computed group cards and canonical
 * tag taxonomy from the separate `catalog2` feed (URLs come from [AppConfig]). Entirely additive and
 * self-contained — when the feed URLs are blank (public/v1 build) it is simply inert, so the classic
 * per-source catalog UI is unaffected. Cross-source grouping, latest-release selection, canonical
 * tags and engine hints are all precomputed server-side; this just deserializes and renders.
 */
class CatalogGroupRepository(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val groupCardsFile: File get() = File(context.filesDir, "catalog2_group_cards.json")
    private val taxonomyFile: File get() = File(context.filesDir, "catalog2_canonical_tags.json")

    private val _cards = MutableStateFlow<List<CatalogGroupCard>>(emptyList())
    val cards: StateFlow<List<CatalogGroupCard>> = _cards.asStateFlow()

    private val _taxonomy = MutableStateFlow(CanonicalTagTaxonomy())
    val taxonomy: StateFlow<CanonicalTagTaxonomy> = _taxonomy.asStateFlow()

    /** True when the running build is pointed at a 2.x compiled feed. */
    fun isEnabled(config: AppConfig): Boolean = config.groupCardsUrl.isNotBlank()

    /** Load whatever is already cached on disk (no network). Safe to call on startup. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        readCards(groupCardsFile)?.let { _cards.value = it }
        readTaxonomy(taxonomyFile)?.let { _taxonomy.value = it }
    }

    /** Refresh from the 2.x feed if configured. Returns true when new data was fetched. */
    suspend fun refresh(config: AppConfig): Boolean = withContext(Dispatchers.IO) {
        if (!isEnabled(config)) return@withContext false
        var changed = false
        downloadBytes(config.groupCardsUrl)?.let { bytes ->
            parseCards(bytes)?.let { cards ->
                groupCardsFile.writeBytes(bytes)
                _cards.value = cards
                changed = true
            }
        }
        if (config.canonicalTagsUrl.isNotBlank()) {
            downloadBytes(config.canonicalTagsUrl)?.let { bytes ->
                parseTaxonomy(bytes)?.let { tax ->
                    taxonomyFile.writeBytes(bytes)
                    _taxonomy.value = tax
                    changed = true
                }
            }
        }
        changed
    }

    /** Refresh only the canonical-tag taxonomy (used by the Catalog tab's faceted filters). Skips
     *  the heavy group-cards download. Returns true when new taxonomy data was fetched. */
    suspend fun refreshTaxonomy(config: AppConfig): Boolean = withContext(Dispatchers.IO) {
        if (config.canonicalTagsUrl.isBlank()) return@withContext false
        downloadBytes(config.canonicalTagsUrl)?.let { bytes ->
            parseTaxonomy(bytes)?.let { tax ->
                taxonomyFile.writeBytes(bytes)
                _taxonomy.value = tax
                return@withContext true
            }
        }
        false
    }

    private fun downloadBytes(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    }.getOrNull()

    private fun parseCards(bytes: ByteArray): List<CatalogGroupCard>? = runCatching {
        openMaybeGzipped(bytes.inputStream()).use { src ->
            json.decodeFromString(
                CatalogGroupCardsEnvelope.serializer(),
                src.readBytes().decodeToString(),
            ).cards
        }
    }.getOrNull()

    private fun parseTaxonomy(bytes: ByteArray): CanonicalTagTaxonomy? = runCatching {
        openMaybeGzipped(bytes.inputStream()).use { src ->
            json.decodeFromString(
                CanonicalTagTaxonomy.serializer(),
                src.readBytes().decodeToString(),
            )
        }
    }.getOrNull()

    private fun readCards(file: File): List<CatalogGroupCard>? =
        if (file.isFile) parseCards(file.readBytes()) else null

    private fun readTaxonomy(file: File): CanonicalTagTaxonomy? =
        if (file.isFile) parseTaxonomy(file.readBytes()) else null

    /** gzip-transparent: some hosts serve `.gz` without a Content-Encoding header. */
    private fun openMaybeGzipped(raw: InputStream): InputStream {
        val buffered = raw.buffered()
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}

/** Pure filtering used by the 2.x Games screen — group cards narrowed by selected canonical tag ids
 *  (AND across selected tags) and a case-insensitive title/canonicalName substring query. Kept free
 *  of Android types so it is unit-testable. */
object CatalogGroupFiltering {
    fun filter(
        cards: List<CatalogGroupCard>,
        selectedTagIds: Set<String>,
        query: String,
    ): List<CatalogGroupCard> {
        val q = query.trim().lowercase()
        return cards.filter { card ->
            (selectedTagIds.isEmpty() || card.canonicalTags.toSet().containsAll(selectedTagIds)) &&
                (q.isEmpty() ||
                    card.title.lowercase().contains(q) ||
                    (card.canonicalName?.lowercase()?.contains(q) == true))
        }
    }
}
