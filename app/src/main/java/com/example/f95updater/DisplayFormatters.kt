package com.example.f95updater

import java.text.DateFormat
import java.util.Date

private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
private val dateTimeFmt: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

internal fun statusLabel(s: UpdateStatus): String = when (s) {
    UpdateStatus.UpdateAvailable -> "Update"
    UpdateStatus.UpToDate -> "Current"
    UpdateStatus.Unknown -> "Unknown"
    UpdateStatus.NotMapped -> "Unmapped"
    UpdateStatus.CheckFailed -> "Failed"
}

internal fun fmtDate(epochMs: Long): String =
    if (epochMs <= 0L) "—" else dateFmt.format(Date(epochMs))

internal fun appUpdatedAt(app: InstalledApp): Long = app.lastUpdateTime

internal fun fmtDateTime(epochMs: Long): String =
    if (epochMs <= 0L) "—" else dateTimeFmt.format(Date(epochMs))

internal fun fmtSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
