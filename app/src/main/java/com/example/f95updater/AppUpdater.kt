package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class AppUpdateTarget(
    val id: String,
    val displayName: String,
    val packageName: String?,
    val versionInfoRelativePath: String?,
    val downloadFileStem: String,
    /** Shown when the companion app is not installed (managed companions only; null for AGM). */
    val optionalInstallDescription: String? = null,
) {
    AdultGameManager(
        id = "adult-game-manager",
        displayName = "Adult Game Manager",
        packageName = null,
        versionInfoRelativePath = null,
        downloadFileStem = "AdultGameManager",
    ),
    WinlatorSecure(
        id = "winlator-secure",
        displayName = "Winlator Secure",
        packageName = WinlatorApi.PACKAGE,
        versionInfoRelativePath = "../winlator-secure/version.json",
        downloadFileStem = "WinlatorSecure",
        optionalInstallDescription =
            "Optional: install Winlator Secure to run and manage supported Windows PC games from AGM.",
    ),
    Kirikiroid(
        id = "kirikiroid",
        displayName = "Kirikiroid2",
        packageName = KirikiroidLauncher.MANAGED_PACKAGE,
        versionInfoRelativePath = "../kirikiroid/version.json",
        downloadFileStem = "Kirikiroid2",
        optionalInstallDescription =
            "Optional: install Kirikiroid2 to run KiriKiri/KAG (.xp3) visual novels natively from AGM.",
    ),
}

@Serializable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val size: Long = 0L,
    val released: String = "",
    val notes: String = "",
    val releaseNotes: String = "",
) {
    val effectiveNotes: String
        get() = notes.ifBlank { releaseNotes }
}

sealed class UpdateCheckResult {
    abstract val target: AppUpdateTarget

    data class Available(
        override val target: AppUpdateTarget,
        val info: AppUpdateInfo,
        val currentVersionCode: Int,
        val currentVersionName: String,
    ) : UpdateCheckResult()

    data class NotInstalled(
        override val target: AppUpdateTarget,
        val info: AppUpdateInfo,
    ) : UpdateCheckResult()

    data class UpToDate(
        override val target: AppUpdateTarget,
        val currentVersionName: String,
    ) : UpdateCheckResult()

    data class Error(
        override val target: AppUpdateTarget,
        val message: String,
    ) : UpdateCheckResult()
}

data class UpdateDownloadProgress(
    val target: AppUpdateTarget,
    val downloaded: Long,
    val total: Long,
)

class AppUpdater(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(
        context: Context,
        target: AppUpdateTarget = AppUpdateTarget.AdultGameManager,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val config = AppConfigStore.current(context)
            val versionUrls = versionInfoUrls(config, target)
            if (versionUrls.isEmpty()) {
                error("No version feed configured")
            }
            val info = fetchLatestVersionInfo(versionUrls)
                .let { selected ->
                    if (target != AppUpdateTarget.AdultGameManager) {
                        selected
                    } else {
                        applyApkUrlOverride(selected, config.apkUrlOverride)
                    }
                }
            val pm = context.packageManager
            val packageName = target.packageName ?: context.packageName
            val pi = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                if (target != AppUpdateTarget.AdultGameManager) {
                    // A managed companion app that simply isn't installed yet — offer it.
                    return@runCatching UpdateCheckResult.NotInstalled(target, info)
                }
                throw IllegalStateException("$packageName is not installed")
            }
            val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
            val currentName = pi.versionName ?: ""
            if (info.versionCode > currentCode) {
                UpdateCheckResult.Available(target, info, currentCode, currentName)
            } else {
                UpdateCheckResult.UpToDate(target, currentName)
            }
        }.getOrElse { UpdateCheckResult.Error(target, it.message ?: "unknown") }
    }

    suspend fun checkAll(context: Context): List<UpdateCheckResult> =
        AppUpdateTarget.entries.map { check(context, it) }

    internal fun versionInfoUrls(config: AppConfig, target: AppUpdateTarget): List<String> {
        val relativePath = target.versionInfoRelativePath ?: return config.effectiveVersionInfoUrls
        return config.effectiveVersionInfoUrls
            .map { deriveRelatedVersionUrl(it, relativePath) }
            .distinct()
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
        target: AppUpdateTarget,
        info: AppUpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val outDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        // Clean any old APKs
        outDir.listFiles()?.forEach { if (it.extension == "apk") it.delete() }
        val outFile = File(outDir, "${target.downloadFileStem}-v${info.versionName}.apk")
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
        verifySha256(outFile, info.sha256)
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

internal fun deriveRelatedVersionUrl(versionInfoUrl: String, relativePath: String): String =
    URI(versionInfoUrl.trim()).resolve(relativePath).toString()

internal fun shouldOfferOptionalInstall(
    dismissedVersionCode: Int?,
    availableVersionCode: Int,
): Boolean = dismissedVersionCode == null || availableVersionCode > dismissedVersionCode

internal fun applyApkUrlOverride(info: AppUpdateInfo, apkUrlOverride: String?): AppUpdateInfo {
    val override = apkUrlOverride?.trim()?.takeIf { it.isNotBlank() } ?: return info
    return info.copy(apkUrl = override, sha256 = "")
}

internal fun verifySha256(file: File, expectedSha256: String) {
    val expected = expectedSha256.trim()
    if (expected.isBlank()) return
    val digest = MessageDigest.getInstance("SHA-256")
    val actual = file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02X".format(it) }
    }
    if (!actual.equals(expected, ignoreCase = true)) {
        file.delete()
        error("SHA-256 mismatch")
    }
}

object UpdatePromptPrefs {
    private const val PREFS = "app_update_prompts"

    fun shouldOfferOptionalInstall(
        context: Context,
        target: AppUpdateTarget,
        availableVersionCode: Int,
    ): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "dismissed_optional_${target.id}"
        val dismissed = if (prefs.contains(key)) prefs.getInt(key, 0) else null
        return shouldOfferOptionalInstall(dismissed, availableVersionCode)
    }

    fun dismissOptionalInstall(context: Context, target: AppUpdateTarget, versionCode: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("dismissed_optional_${target.id}", versionCode)
            .apply()
    }
}
