package com.example.f95updater

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * Shared SAF-source helpers for the archive flows. Both the install/extract flow and the patch
 * installer need the same "name it, size it, copy it to cache with progress" step, so it lives here
 * once instead of in each flow.
 */
object ArchiveUriSource {

    private const val BUFFER_SIZE = 64 * 1024

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index) ?: ""
            }
        }
        return uri.lastPathSegment ?: ""
    }

    suspend fun sizeBytes(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val length = uri.path?.let { File(it) }?.takeIf { it.isFile }?.length() ?: 0L
            if (length > 0) return@withContext length
        }
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) {
                    val raw = cursor.getLong(index)
                    if (raw > 0) return@withContext raw
                }
            }
        }
        0L
    }

    /** Copies [uri] into [context]'s cache. Reports every 256 KiB or 200 ms, whichever comes first. */
    suspend fun copyToCache(
        context: Context,
        uri: Uri,
        name: String,
        prefix: String,
        onProgress: (bytesCopied: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val cache = File(context.cacheDir, "$prefix${System.currentTimeMillis()}_$safeName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    var lastReportBytes = 0L
                    var lastReportTimeNs = System.nanoTime()
                    while (true) {
                        yield()
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.nanoTime()
                        if (written - lastReportBytes >= 256 * 1024L ||
                            now - lastReportTimeNs >= 200_000_000L
                        ) {
                            lastReportBytes = written
                            lastReportTimeNs = now
                            onProgress(written)
                        }
                    }
                    onProgress(written)
                }
            } ?: error("Could not open archive uri $uri")
            cache
        } catch (t: Throwable) {
            cache.delete()
            throw t
        }
    }
}
