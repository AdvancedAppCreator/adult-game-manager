package com.example.f95updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One entry from JoiPlay's official downloads manifest (joiplay.net/assets/json/downloads.json). */
@Serializable
data class JoiPlayDownload(
    val id: Int = 0,
    val title: String = "",
    val version: String = "",
    val type: String = "",
    val size: String = "",
    val description: String = "",
    val link: String = "",
    val date: String = "",
    val badge: String = "",
)

/**
 * Reads JoiPlay's official downloads manifest to tell the user whether their installed JoiPlay is
 * out of date. JoiPlay is a third-party app (package cyou.joiplay.joiplay), distributed off-store
 * (Patreon / MEGA links), so AGM can NOT host or auto-install it — it only reports the latest
 * versions and deep-links the user to the official site. The core app version is compared against
 * the installed package; plugin rows are surfaced as informational "latest available" versions.
 */
object JoiPlayUpdateChecker {
    const val PACKAGE = "cyou.joiplay.joiplay"
    private const val CORE_TITLE = "JoiPlay"
    private const val CORE_BADGE = "Required"

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(JoiPlayDownload.serializer())

    data class Status(
        val installedVersion: String?,
        val core: JoiPlayDownload?,
        val downloads: List<JoiPlayDownload>,
    ) {
        val installed: Boolean get() = installedVersion != null

        /** True only when JoiPlay is installed and the manifest's core version is strictly newer. */
        val updateAvailable: Boolean
            get() = installedVersion != null &&
                core != null &&
                VersionCompare.compare(core.version, installedVersion) > 0
    }

    /** Parses the manifest JSON array. Returns empty on malformed input rather than throwing. */
    fun parse(text: String): List<JoiPlayDownload> =
        runCatching { json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())

    /** Picks the core JoiPlay app entry (by title, falling back to the "Required" badge). */
    fun core(downloads: List<JoiPlayDownload>): JoiPlayDownload? =
        downloads.firstOrNull { it.title.trim().equals(CORE_TITLE, ignoreCase = true) }
            ?: downloads.firstOrNull { it.badge.trim().equals(CORE_BADGE, ignoreCase = true) }

    /** The installed JoiPlay app's versionName, or null when JoiPlay isn't installed. */
    fun installedVersion(context: Context): String? = runCatching {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, 0)
        }
        pi.versionName?.trim()?.ifBlank { null }
    }.getOrNull()

    /**
     * Fetches the manifest from [url] and resolves the current status. Network/parse failures yield
     * a Status with an empty manifest (updateAvailable=false) — this is a best-effort nudge, never
     * a hard error.
     */
    suspend fun check(
        context: Context,
        url: String,
        client: OkHttpClient = defaultClient,
    ): Status = withContext(Dispatchers.IO) {
        val installed = installedVersion(context)
        val downloads = runCatching { fetch(url, client) }
            .onFailure { AppLog.w("JoiPlayUpdate", "Could not fetch JoiPlay manifest from $url", it) }
            .getOrDefault(emptyList())
        Status(installedVersion = installed, core = core(downloads), downloads = downloads).also {
            AppLog.i(
                "JoiPlayUpdate",
                "check: installed=${installed ?: "none"} latest=${it.core?.version ?: "?"} " +
                    "downloads=${downloads.size} updateAvailable=${it.updateAvailable}",
            )
        }
    }

    suspend fun fetch(url: String, client: OkHttpClient = defaultClient): List<JoiPlayDownload> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (AdultGameManager)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                parse(resp.body?.string().orEmpty())
            }
        }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
