package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@Serializable
data class CatalogEntryDetails(
    val synopsis: String = "",
    val updatedAt: String? = null,
)

@Serializable
internal data class CatalogDetailsShard(
    val schemaVersion: Int = 1,
    val source: String = "",
    val shard: String = "",
    val generatedAt: String = "",
    val entries: Map<String, CatalogEntryDetails> = emptyMap(),
)

internal sealed interface CatalogDetailsLoadResult {
    data class Available(
        val details: CatalogEntryDetails,
        val stale: Boolean = false,
    ) : CatalogDetailsLoadResult

    object Missing : CatalogDetailsLoadResult
    data class Unavailable(val message: String) : CatalogDetailsLoadResult
}

internal class CatalogDetailsRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "catalog_details_v1")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val locks = Array(32) { Mutex() }

    suspend fun load(
        entry: SourceCatalogEntry,
        sourceInfo: CatalogSourceInfo,
    ): CatalogDetailsLoadResult = withContext(Dispatchers.IO) {
        val template = sourceInfo.detailsUrlTemplate?.trim()
            ?.takeIf { it.isNotEmpty() && it.contains("{shard}") }
            ?: return@withContext CatalogDetailsLoadResult.Missing
        val shard = catalogDetailsShard(entry.sourceId)
        val generation = sourceInfo.detailsGeneratedAt
            ?.takeIf { it.isNotBlank() }
            ?: sourceInfo.generatedAt?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val generationKey = shortHash(generation)
        val cacheKey = "${entry.source}:$shard:$generationKey"

        locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val sourceDir = File(cacheDir, safePathPart(entry.source)).apply { mkdirs() }
            val currentFile = File(sourceDir, "$shard-$generationKey.json")
            if (currentFile.isFile && currentFile.length() > 0L) {
                try {
                    val parsed = decodeCatalogDetailsShard(currentFile.readBytes())
                    validateShard(parsed, entry.source, shard)
                    currentFile.setLastModified(System.currentTimeMillis())
                    return@withLock resultForEntry(parsed, entry.sourceId)
                } catch (e: IOException) {
                    AppLog.w("CatalogDetails", "Cached shard read failed: ${e.message}")
                } catch (e: SerializationException) {
                    AppLog.w("CatalogDetails", "Cached shard parse failed: ${e.message}")
                }
                if (!currentFile.delete()) {
                    AppLog.w("CatalogDetails", "Could not remove invalid cache ${currentFile.name}")
                }
            }

            val url = catalogDetailsUrl(template, shard, generation)
                ?: return@withLock CatalogDetailsLoadResult.Missing
            try {
                val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.code == 404) {
                        return@withLock CatalogDetailsLoadResult.Missing
                    }
                    if (!response.isSuccessful) {
                        throw IOException("Details server returned HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: throw IOException("Details server returned an empty body")
                }
                val parsed = decodeCatalogDetailsShard(bytes)
                validateShard(parsed, entry.source, shard)
                writeAtomically(currentFile, bytes)
                pruneCache(currentFile)
                resultForEntry(parsed, entry.sourceId)
            } catch (e: IOException) {
                staleFallback(sourceDir, shard, entry, e.message)
            } catch (e: SerializationException) {
                staleFallback(sourceDir, shard, entry, e.message)
            }
        }
    }

    private fun staleFallback(
        sourceDir: File,
        shard: String,
        entry: SourceCatalogEntry,
        message: String?,
    ): CatalogDetailsLoadResult {
        val staleFiles = sourceDir.listFiles { file ->
            file.isFile && file.name.startsWith("$shard-") && file.name.endsWith(".json")
        }.orEmpty().sortedByDescending { it.lastModified() }
        for (file in staleFiles) {
            try {
                val parsed = decodeCatalogDetailsShard(file.readBytes())
                validateShard(parsed, entry.source, shard)
                val details = parsed.entries[entry.sourceId]
                    ?.takeIf { it.synopsis.isNotBlank() }
                    ?: continue
                return CatalogDetailsLoadResult.Available(details, stale = true)
            } catch (e: IOException) {
                AppLog.w("CatalogDetails", "Stale shard read failed: ${e.message}")
            } catch (e: SerializationException) {
                AppLog.w("CatalogDetails", "Stale shard parse failed: ${e.message}")
            }
        }
        return CatalogDetailsLoadResult.Unavailable(
            message?.takeIf { it.isNotBlank() } ?: "Could not load synopsis",
        )
    }

    private fun validateShard(parsed: CatalogDetailsShard, source: String, shard: String) {
        if (parsed.source.isNotBlank() && parsed.source != source) {
            throw SerializationException("Details source mismatch")
        }
        if (parsed.shard.isNotBlank() && parsed.shard != shard) {
            throw SerializationException("Details shard mismatch")
        }
    }

    private fun resultForEntry(
        shard: CatalogDetailsShard,
        sourceId: String,
    ): CatalogDetailsLoadResult =
        shard.entries[sourceId]
            ?.takeIf { it.synopsis.isNotBlank() }
            ?.let { CatalogDetailsLoadResult.Available(it) }
            ?: CatalogDetailsLoadResult.Missing

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        temp.writeBytes(bytes)
        if (destination.exists() && !destination.delete()) {
            temp.delete()
            throw IOException("Could not replace cached details")
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            throw IOException("Could not finalize cached details")
        }
    }

    private fun pruneCache(currentFile: File) {
        val files = cacheDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .toList()
        var total = files.sumOf { it.length() }
        for (file in files.asReversed()) {
            if (total <= DISK_CACHE_LIMIT_BYTES) break
            if (file == currentFile) continue
            val length = file.length()
            if (file.delete()) {
                total -= length
            } else {
                AppLog.w("CatalogDetails", "Could not prune ${file.name}")
            }
        }
    }

    companion object {
        private const val DISK_CACHE_LIMIT_BYTES = 32L * 1024L * 1024L
    }
}

internal fun catalogDetailsShard(sourceId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(sourceId.toByteArray(Charsets.UTF_8))
        .take(1)
        .joinToString("") { "%02x".format(it) }

internal fun catalogDetailsUrl(
    template: String,
    shard: String,
    generation: String,
): String? = template.takeIf { it.contains("{shard}") }
    ?.replace("{shard}", shard)
    ?.replace(
        "{generation}",
        java.net.URLEncoder.encode(generation, Charsets.UTF_8.name()),
    )

internal fun decodeCatalogDetailsShard(bytes: ByteArray): CatalogDetailsShard {
    val input = ByteArrayInputStream(bytes)
    val decoded = if (bytes.size >= 2 &&
        bytes[0] == 0x1f.toByte() &&
        bytes[1] == 0x8b.toByte()
    ) {
        GZIPInputStream(input)
    } else {
        input
    }
    return decoded.use {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }.decodeFromStream(CatalogDetailsShard.serializer(), it)
    }
}

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

private fun safePathPart(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_")
