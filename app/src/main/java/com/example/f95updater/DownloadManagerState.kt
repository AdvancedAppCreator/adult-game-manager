package com.example.f95updater

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.util.Locale

internal data class DownloadManagerSnapshot(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

internal data class PendingDownloadConstraints(
    val thermalStatus: Int? = null,
    val powerSaveMode: Boolean? = null,
    val restrictBackgroundStatus: Int? = null,
    val hasActiveNetwork: Boolean? = null,
    val hasValidatedNetwork: Boolean? = null,
)

internal fun queryPendingDownloadConstraints(context: Context): PendingDownloadConstraints {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val activeNetwork = connectivityManager?.activeNetwork
    val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    return PendingDownloadConstraints(
        thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus
        } else {
            null
        },
        powerSaveMode = powerManager?.isPowerSaveMode,
        restrictBackgroundStatus = connectivityManager?.restrictBackgroundStatus,
        hasActiveNetwork = connectivityManager?.let { activeNetwork != null },
        hasValidatedNetwork = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ?: if (activeNetwork == null && connectivityManager != null) false else null,
    )
}

internal fun queryDownloadManagerSnapshots(
    downloadManager: DownloadManager,
    ids: List<Long>,
): Map<Long, DownloadManagerSnapshot> {
    if (ids.isEmpty()) return emptyMap()
    val snapshots = HashMap<Long, DownloadManagerSnapshot>()
    downloadManager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
        val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
        val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        while (cursor.moveToNext()) {
            if (idIndex < 0) continue
            val id = cursor.getLong(idIndex)
            snapshots[id] = DownloadManagerSnapshot(
                status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1,
                reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0,
                downloadedBytes = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L,
                totalBytes = if (totalIndex >= 0) cursor.getLong(totalIndex) else 0L,
            )
        }
    }
    return snapshots
}

internal fun downloadRuntimeStatusText(
    record: DownloadRecord,
    snapshot: DownloadManagerSnapshot?,
    queryCompleted: Boolean,
    nowMs: Long,
    pendingConstraints: PendingDownloadConstraints = PendingDownloadConstraints(),
): String {
    if (!queryCompleted) return "Checking Android Download Manager…"
    if (snapshot == null) return "Unavailable — no longer registered with Android Download Manager"
    return when (snapshot.status) {
        DownloadManager.STATUS_PENDING -> {
            val status = pendingDownloadStatus(pendingConstraints)
                ?: "Queued — waiting to start"
            "$status${downloadElapsedSuffix(record.createdAt, nowMs)}"
        }
        DownloadManager.STATUS_RUNNING -> when {
            snapshot.totalBytes > 0L -> {
                val percent = (snapshot.downloadedBytes * 100L / snapshot.totalBytes).coerceIn(0L, 100L)
                "Downloading — ${humanDownloadBytes(snapshot.downloadedBytes)} / " +
                    "${humanDownloadBytes(snapshot.totalBytes)} ($percent%)"
            }
            snapshot.downloadedBytes > 0L ->
                "Downloading — ${humanDownloadBytes(snapshot.downloadedBytes)} received; total size unknown"
            else -> "Connecting — waiting for data${downloadElapsedSuffix(record.createdAt, nowMs)}"
        }
        DownloadManager.STATUS_PAUSED -> {
            val progress = if (snapshot.totalBytes > 0L) {
                val percent =
                    (snapshot.downloadedBytes * 100L / snapshot.totalBytes).coerceIn(0L, 100L)
                " — ${humanDownloadBytes(snapshot.downloadedBytes)} / " +
                    "${humanDownloadBytes(snapshot.totalBytes)} ($percent%)"
            } else {
                ""
            }
            "Paused — ${downloadManagerReasonLabel(snapshot.reason)}$progress"
        }
        DownloadManager.STATUS_SUCCESSFUL -> "Downloaded — finalizing file"
        DownloadManager.STATUS_FAILED -> "Failed — ${downloadManagerReasonLabel(snapshot.reason)}"
        else -> "Unknown Android Download Manager status ${snapshot.status}"
    }
}

internal fun pendingDownloadStatus(constraints: PendingDownloadConstraints): String? = when {
    constraints.hasActiveNetwork == false -> "Queued — no active network"
    constraints.hasValidatedNetwork == false -> "Queued — no validated active network"
    constraints.thermalStatus != null &&
        constraints.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
        "Queued — device thermal status is ${thermalStatusLabel(constraints.thermalStatus)}"
    constraints.restrictBackgroundStatus ==
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
        "Queued — Data Saver restricts background data"
    constraints.powerSaveMode == true -> "Queued — Battery Saver is on"
    else -> null
}

private fun thermalStatusLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "None"
    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
    else -> "Unknown ($status)"
}

internal fun humanDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun downloadElapsedSuffix(createdAt: Long, nowMs: Long): String {
    if (createdAt <= 0L || nowMs <= createdAt) return ""
    val totalSeconds = (nowMs - createdAt) / 1_000L
    if (totalSeconds < 5L) return ""
    val text = when {
        totalSeconds < 60L -> "${totalSeconds}s"
        totalSeconds < 3_600L -> "${totalSeconds / 60L}m ${totalSeconds % 60L}s"
        else -> "${totalSeconds / 3_600L}h ${(totalSeconds % 3_600L) / 60L}m"
    }
    return " for $text"
}
