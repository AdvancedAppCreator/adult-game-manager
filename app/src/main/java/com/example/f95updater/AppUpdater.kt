package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val size: Long = 0L,
    val released: String = "",
    val notes: String = "",
)

sealed class UpdateCheckResult {
    data class Available(val info: AppUpdateInfo, val currentVersionCode: Int, val currentVersionName: String) : UpdateCheckResult()
    data class UpToDate(val currentVersionName: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class AppUpdater(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val config = AppConfigStore.current(context)
            val versionUrls = config.effectiveVersionInfoUrls
            if (versionUrls.isEmpty()) {
                error("No version feed configured")
            }
            val info = fetchLatestVersionInfo(versionUrls)
                .let { selected ->
                    config.apkUrlOverride
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { selected.copy(apkUrl = it) }
                        ?: selected
                }
            val pm = context.packageManager
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
            }
            val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
            val currentName = pi.versionName ?: ""
            if (info.versionCode > currentCode) UpdateCheckResult.Available(info, currentCode, currentName)
            else UpdateCheckResult.UpToDate(currentName)
        }.getOrElse { UpdateCheckResult.Error(it.message ?: "unknown") }
    }

    private fun fetchLatestVersionInfo(versionUrls: List<String>): AppUpdateInfo {
        val successes = mutableListOf<AppUpdateInfo>()
        val failures = mutableListOf<String>()
        versionUrls.forEach { versionUrl ->
            runCatching {
                val req = Request.Builder().url(versionUrl).build()
                val body = client.newCall(req).execute().use {
                    if (!it.isSuccessful) error("HTTP ${it.code}")
                    it.body?.string() ?: error("empty response")
                }
                json.decodeFromString(AppUpdateInfo.serializer(), body)
            }.onSuccess { successes += it }
                .onFailure { failures += "$versionUrl: ${it.message ?: "unknown"}" }
        }
        return newestVersionInfo(successes)
            ?: error(
                if (failures.isEmpty()) "No version feed configured"
                else "All version feeds failed: ${failures.joinToString("; ")}",
            )
    }

    internal fun newestVersionInfo(infos: List<AppUpdateInfo>): AppUpdateInfo? =
        infos.maxByOrNull { it.versionCode }

    /** Downloads the APK to the app's external files dir, returns the File. */
    suspend fun download(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val outDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        // Clean any old APKs
        outDir.listFiles()?.forEach { if (it.extension == "apk") it.delete() }
        val outFile = File(outDir, "AdultGameManager-v${info.versionName}.apk")
        val req = Request.Builder().url(info.apkUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: info.size
            resp.body?.source()?.use { source ->
                outFile.sink().buffer().use { sink ->
                    var downloaded = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val n = source.read(buf, 64 * 1024L)
                        if (n == -1L) break
                        sink.write(buf, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                    sink.flush()
                }
            } ?: error("empty body")
        }
        outFile
    }

    /** Fires the system installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
