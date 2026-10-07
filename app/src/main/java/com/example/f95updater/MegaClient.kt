package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native implementation of MEGA's documented public-file protocol.
 *
 * Supports public file links only, deriving the AES-CTR key/nonce from the link key, resolving the
 * storage URL through MEGA's API, and streaming encrypted bytes through javax.crypto without any
 * third-party MEGA library.
 */
object MegaClient {
    private const val TAG = "MegaClient"
    private const val API_URL = "https://g.api.mega.co.nz/cs?id=0"
    private const val CHUNK_SIZE = 64 * 1024L

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class ParsedFileLink(val fileId: String, val aesKey: ByteArray, val ivHigh8: ByteArray)
    data class MegaFileInfo(val downloadUrl: String, val size: Long)

    fun parseFileLink(url: String): ParsedFileLink? = runCatching {
        val uri = URI(url.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host != "mega.nz") {
            return@runCatching null
        }
        val path = uri.path.orEmpty()
        val fragment = uri.fragment.orEmpty()
        val (fileId, keyB64) = when {
            path.startsWith("/file/") && fragment.isNotBlank() -> {
                path.removePrefix("/file/").substringBefore('/') to fragment
            }
            fragment.startsWith("!") -> {
                val parts = fragment.drop(1).split('!', limit = 2)
                if (parts.size != 2) return@runCatching null
                parts[0] to parts[1]
            }
            else -> return@runCatching null
        }
        if (fileId.isBlank() || keyB64.isBlank()) return@runCatching null

        val keyBytes = decodeMegaBase64(keyB64)
        if (keyBytes.size != 32) return@runCatching null
        val keyWords = IntArray(8)
        val keyBuffer = ByteBuffer.wrap(keyBytes).order(ByteOrder.BIG_ENDIAN)
        repeat(8) { keyWords[it] = keyBuffer.getInt() }

        val aesKey = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            repeat(4) { putInt(keyWords[it] xor keyWords[it + 4]) }
        }.array()
        val ivHigh8 = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(keyWords[4])
            putInt(keyWords[5])
        }.array()

        ParsedFileLink(fileId = fileId, aesKey = aesKey, ivHigh8 = ivHigh8)
    }.getOrNull()

    suspend fun resolveDownload(fileId: String, client: OkHttpClient = defaultClient): MegaFileInfo =
        withContext(Dispatchers.IO) {
            val payload = JSONArray().put(
                org.json.JSONObject()
                    .put("a", "g")
                    .put("g", 1)
                    .put("p", fileId),
            ).toString()
            val request = Request.Builder()
                .url(API_URL)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("MEGA API HTTP ${response.code}")
                val raw = response.body?.string() ?: error("MEGA API empty response")
                parseMegaFileInfo(raw)
            }
        }

    suspend fun downloadPublicFile(
        url: String,
        outFile: File,
        client: OkHttpClient = defaultClient,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val parsed = parseFileLink(url) ?: throw IllegalArgumentException("Unparseable MEGA public file link")
        val info = resolveDownload(parsed.fileId, client)
        val cipher = createCtrCipher(parsed.aesKey, parsed.ivHigh8, Cipher.DECRYPT_MODE)
        AppLog.i(TAG, "Downloading MEGA file ${parsed.fileId} size=${info.size} to ${outFile.absolutePath}")

        val request = Request.Builder().url(info.downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("MEGA download HTTP ${response.code}")
            response.body?.source()?.use { source ->
                outFile.sink().buffer().use { sink ->
                    var downloaded = 0L
                    val buffer = Buffer()
                    while (true) {
                        val read = source.read(buffer, CHUNK_SIZE)
                        if (read == -1L) break
                        val encrypted = buffer.readByteArray(read)
                        val decrypted = cipher.update(encrypted)
                        if (decrypted != null && decrypted.isNotEmpty()) {
                            sink.write(decrypted)
                        }
                        downloaded += read
                        onProgress(downloaded, info.size)
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes.isNotEmpty()) sink.write(finalBytes)
                    sink.flush()
                }
            } ?: error("MEGA download empty body")
        }
        outFile
    }

    internal fun createCtrCipher(aesKey: ByteArray, ivHigh8: ByteArray, mode: Int): Cipher {
        require(aesKey.size == 16) { "MEGA AES key must be 16 bytes" }
        require(ivHigh8.size == 8) { "MEGA IV nonce must be 8 bytes" }
        val iv16 = ByteArray(16)
        ivHigh8.copyInto(iv16, destinationOffset = 0)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv16))
        }
    }

    private fun decodeMegaBase64(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.getUrlDecoder().decode(padded)
    }

    private fun parseMegaFileInfo(raw: String): MegaFileInfo {
        val parsed = JSONTokener(raw).nextValue()
        val first = when (parsed) {
            is JSONArray -> {
                if (parsed.length() == 0) error("MEGA API empty array")
                parsed.get(0)
            }
            else -> parsed
        }
        if (first is Number) error("MEGA API error ${first.toLong()}")
        val obj = first as? org.json.JSONObject ?: error("MEGA API unexpected response")
        val downloadUrl = obj.optString("g").takeIf { it.isNotBlank() }
            ?: error("MEGA API response missing download URL")
        val size = obj.optLong("s", -1L).takeIf { it >= 0L }
            ?: error("MEGA API response missing size")
        val httpsUrl = if (downloadUrl.startsWith("http://")) {
            "https://" + downloadUrl.removePrefix("http://")
        } else {
            downloadUrl
        }
        return MegaFileInfo(downloadUrl = httpsUrl, size = size)
    }
}

