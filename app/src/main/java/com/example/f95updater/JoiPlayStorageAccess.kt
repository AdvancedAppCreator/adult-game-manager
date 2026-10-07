package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

internal fun humanReadableUri(uri: String): String {
    if (uri.startsWith("file://")) {
        val path = Uri.decode(uri.removePrefix("file://"))
        val ext = runCatching {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        }.getOrDefault("")
        val rel = if (ext.isNotEmpty() && path.startsWith(ext)) path.removePrefix(ext) else path
        return if (rel.startsWith("/")) {
            "Internal storage" + rel.replace("/", " \u203a ")
        } else "Internal storage"
    }
    // Legacy support: tree URIs from older versions.
    val decoded = Uri.decode(uri)
    val docId = decoded.substringAfterLast("/tree/").substringBefore("/document/")
    val (volume, path) = docId.split(":", limit = 2).let { p ->
        if (p.size == 2) p[0] to p[1] else "" to decoded
    }
    val volumeLabel = when (volume) {
        "primary" -> "Internal storage"
        "" -> ""
        else -> volume
    }
    val parts = mutableListOf<String>()
    if (volumeLabel.isNotEmpty()) parts.add(volumeLabel)
    if (path.isNotBlank()) parts.addAll(path.split('/').filter { it.isNotBlank() })
    val joined = parts.joinToString(" \u203a ")
    return joined.ifBlank { decoded }
}

/** Returns true if MANAGE_EXTERNAL_STORAGE has been granted (Android 11+).
 *  Returns true unconditionally on Android 10 and below where legacy storage applies. */
internal fun hasAllFilesAccess(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        StaticContext.appContext?.let { hasLegacyStorageAccess(it) } ?: false
    }
}

internal fun hasLegacyStorageAccess(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) return true
    val readGranted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
    val writeGranted = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    return readGranted && writeGranted
}

/** Open the system Settings page for granting MANAGE_EXTERNAL_STORAGE to this app. */
internal fun requestAllFilesAccess(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
    val intent = Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }
        .onFailure {
            // Fallback to the broad list page if the per-app deeplink fails.
            val list = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(list) }
        }
}
