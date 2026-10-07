package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DownloadState {
    Running,
    Finalizing,
    Completed,
    Failed,
    FinalizationFailed,
    Cancelled,
}

@Serializable
enum class DownloadBackend {
    AndroidDownloadManager,
    ExternalBrowser,
}

@Serializable
data class ExternalFileSnapshot(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
)

internal val DownloadState.isActive: Boolean
    get() = this == DownloadState.Running || this == DownloadState.Finalizing

@Serializable
data class DownloadRecord(
    val id: Long,
    val url: String,
    val fileName: String,
    val backend: DownloadBackend = DownloadBackend.AndroidDownloadManager,
    val catalogGame: CatalogGame? = null,
    val userAgent: String? = null,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val referrer: String? = null,
    val stagedPath: String? = null,
    val targetPath: String? = null,
    val destPath: String? = null,
    val state: DownloadState = DownloadState.Running,
    val createdAt: Long = 0,
    val expectedBytes: Long = -1,
    val totalBytes: Long = -1,
    val finalizedBytes: Long = 0,
    val externalSearchRoots: List<String> = emptyList(),
    val externalBaseline: List<ExternalFileSnapshot> = emptyList(),
    val error: String? = null,
)

/**
 * Persistent, app-scoped registry of downloads started from the in-app browser. Backed by a JSON
 * file in filesDir and exposed as a [StateFlow] so both the browser scope (which enqueues +
 * completes) and the Download Manager UI observe the same list. Survives process death.
 */
object DownloadStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, "browser_downloads.json")

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = file(context)
            if (!f.isFile) return@withContext
            try {
                val elements = json.parseToJsonElement(f.readText()) as? JsonArray
                    ?: error("Download list root is not an array")
                _records.value = elements.mapIndexedNotNull { index, element ->
                    runCatching {
                        json.decodeFromJsonElement(DownloadRecord.serializer(), element)
                    }.onFailure {
                        AppLog.w("Downloads", "Skipping invalid download record at index $index", it)
                    }.getOrNull()
                }
            } catch (error: Exception) {
                val backup = File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")
                val moved = f.renameTo(backup)
                AppLog.w(
                    "Downloads",
                    "Failed to load download list; corrupt file " +
                        if (moved) "moved to ${backup.name}" else "could not be backed up",
                    error,
                )
            }
        }
    }

    private fun persist(context: Context, records: List<DownloadRecord>) {
        try {
            val destination = file(context)
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            val encoded = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(DownloadRecord.serializer()),
                records,
            )
            FileOutputStream(temporary).use { output ->
                output.write(encoded.toByteArray())
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            AppLog.w("Downloads", "Failed to persist download list", error)
            throw IOException("Could not save download state", error)
        }
    }

    suspend fun add(context: Context, record: DownloadRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = insertDownloadRecord(_records.value, record)
            persist(context, updated)
            _records.value = updated
        }
    }

    suspend fun replace(context: Context, oldId: Long, record: DownloadRecord) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = replaceDownloadRecord(_records.value, oldId, record)
                persist(context, updated)
                _records.value = updated
            }
        }

    suspend fun update(context: Context, id: Long, transform: (DownloadRecord) -> DownloadRecord) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = _records.value.map { if (it.id == id) transform(it) else it }
                persist(context, updated)
                _records.value = updated
            }
        }

    suspend fun remove(context: Context, id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = _records.value.firstOrNull { it.id == id }
            val updated = _records.value.filterNot { it.id == id }
            persist(context, updated)
            _records.value = updated
            removed?.let(::deleteTransientDownloadFiles)
        }
    }

    suspend fun clearFinished(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = _records.value.filterNot { it.state.isActive }
            val updated = _records.value.filter { it.state.isActive }
            persist(context, updated)
            _records.value = updated
            removed.forEach(::deleteTransientDownloadFiles)
        }
    }

    suspend fun removeMissingFiles(context: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = _records.value
            val updated = before.filterNot(::downloadFileIsMissing)
            val removed = before.size - updated.size
            if (removed > 0) {
                persist(context, updated)
                _records.value = updated
            }
            removed
        }
    }
}

private fun deleteTransientDownloadFiles(record: DownloadRecord) {
    record.stagedPath?.let { File(it).delete() }
    record.targetPath
        ?.takeUnless { it == record.destPath }
        ?.let { path ->
            File(path).takeIf { it.length() == 0L }?.delete()
        }
}

internal fun downloadFileIsMissing(
    record: DownloadRecord,
    exists: (String) -> Boolean = { File(it).isFile },
): Boolean =
    record.state == DownloadState.Completed &&
        (record.destPath.isNullOrBlank() || !exists(record.destPath))

internal fun DownloadRecord.retryRequest(): BrowserDownloadRequest = BrowserDownloadRequest(
    url = url,
    userAgent = userAgent,
    contentDisposition = contentDisposition,
    mimeType = mimeType,
    pageTitle = null,
    referrer = referrer,
    contentLength = expectedBytes,
)

internal fun insertDownloadRecord(
    records: List<DownloadRecord>,
    record: DownloadRecord,
): List<DownloadRecord> {
    val existing = records.firstOrNull { it.id == record.id }
    val selected = if (existing == null || record.createdAt > existing.createdAt) record else existing
    return listOf(selected) + records.filterNot { it.id == record.id }
}

internal fun replaceDownloadRecord(
    records: List<DownloadRecord>,
    oldId: Long,
    record: DownloadRecord,
): List<DownloadRecord> {
    val existing = records.firstOrNull { it.id == record.id }
    val selected = if (existing == null || record.createdAt > existing.createdAt) record else existing
    return listOf(selected) + records.filterNot { it.id == oldId || it.id == record.id }
}

internal fun downloadManagerReasonLabel(reason: Int): String = when (reason) {
    android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network; will continue automatically"
    android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi; will continue automatically"
    android.app.DownloadManager.PAUSED_WAITING_TO_RETRY -> "Temporarily paused; retrying automatically"
    android.app.DownloadManager.PAUSED_UNKNOWN -> "Temporarily paused"
    android.app.DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume"
    android.app.DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage unavailable"
    android.app.DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
    android.app.DownloadManager.ERROR_FILE_ERROR -> "File error"
    android.app.DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network data error"
    android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient storage"
    android.app.DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
    android.app.DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "HTTP error"
    android.app.DownloadManager.ERROR_UNKNOWN -> "Unknown error"
    else -> if (reason in 400..599) "HTTP $reason" else "Code $reason"
}

internal fun downloadManagerStatusLabel(status: Int, reason: Int): String = when (status) {
    android.app.DownloadManager.STATUS_PENDING -> "pending"
    android.app.DownloadManager.STATUS_RUNNING -> "running"
    android.app.DownloadManager.STATUS_PAUSED -> "paused: ${downloadManagerReasonLabel(reason)}"
    android.app.DownloadManager.STATUS_SUCCESSFUL -> "successful"
    android.app.DownloadManager.STATUS_FAILED -> "failed: ${downloadManagerReasonLabel(reason)}"
    else -> "unknown status $status"
}
