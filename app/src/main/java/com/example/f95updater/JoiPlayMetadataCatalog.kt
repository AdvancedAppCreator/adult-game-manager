package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reads JoiPlay's own catalog (metadata.json inside metadata.zip inside ff.joiback).
 * Each entry has: id, title, version, developer, tags, prefixes, ...
 */
@Serializable
private data class JoiPlayCatalogEntry(
    val id: String = "",
    val title: String = "",
    val version: String = "",
    val developer: String = "",
)

object JoiPlayMetadataCatalog {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = ListSerializer(JoiPlayCatalogEntry.serializer())

    private data class Loaded(val list: List<JoiPlayCatalogEntry>, val byNormTitle: Map<String, String>)

    @Volatile private var cached: Loaded? = null

    // Unicode-aware on purpose: keeps non-ASCII letters/digits so titles in non-Latin
    // scripts still normalize sensibly. Deliberately NOT the ASCII-only
    // CatalogRepository.normalizeTitle (which strips non-[a-z0-9]).
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun load(context: Context): Loaded? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            val text = JoiPlayBackupReader.cachedMetadataJson(context) ?: return@withContext null
            runCatching {
                val list = json.decodeFromString(serializer, text)
                val map = HashMap<String, String>(list.size)
                for (e in list) {
                    if (e.version.isBlank()) continue
                    val key = normalize(e.title)
                    if (key.isBlank()) continue
                    // Keep first occurrence; titles can repeat in the catalog.
                    map.putIfAbsent(key, e.version)
                }
                Loaded(list, map).also { cached = it }
            }.onFailure {
                AppLog.w("JoiPlayCatalog", "metadata parse failed", it)
            }.getOrNull()
        }
    }

    /** Look up the catalog's version for the given game title (fuzzy: alphanum-only, case-insensitive). */
    suspend fun lookupVersion(context: Context, title: String): String? {
        val key = normalize(title)
        if (key.isBlank()) return null
        val l = load(context) ?: return null
        return l.byNormTitle[key]
    }
}
