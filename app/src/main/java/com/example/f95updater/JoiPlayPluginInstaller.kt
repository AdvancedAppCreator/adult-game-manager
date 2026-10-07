package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs / updates a JoiPlay engine-runtime plugin from AGM.
 *
 * Strategy (chosen by the user): **B with A fallback** — try a native in-app download of the
 * plugin APK straight from its MEGA link ([MegaClient]) and hand it to the system package installer
 * ([AppUpdater.install]); if anything fails (network, MEGA protocol, install), fall back to A —
 * deep-linking the user to the official download so they can install it manually. AGM never hosts
 * or redistributes the plugin APK; the bytes come straight from MEGA to the device, exactly as the
 * user's browser would fetch them.
 */
object JoiPlayPluginInstaller {

    /** Outcome of an install attempt. */
    sealed interface Outcome {
        /** The APK downloaded + the system installer was launched. */
        data object InstallerLaunched : Outcome

        /** In-app install failed; the caller should deep-link [link] (the official MEGA download). */
        data class FellBack(val link: String, val reason: String) : Outcome
    }

    /**
     * Downloads [download]'s APK from MEGA and launches the system installer, reporting byte
     * progress via [onProgress]. On any failure returns [Outcome.FellBack] so the UI can open the
     * official link instead. Never throws for expected network/protocol errors.
     */
    suspend fun downloadAndInstall(
        context: Context,
        download: JoiPlayDownload,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val link = download.link.trim()
        if (link.isBlank()) {
            return@withContext Outcome.FellBack("https://joiplay.net/#downloads", "no download link")
        }
        try {
            val outDir = File(context.getExternalFilesDir(null), "plugin-updates").apply { mkdirs() }
            outDir.listFiles()?.forEach { if (it.extension == "apk") it.delete() }
            val outFile = File(outDir, "${safeStem(download.title)}.apk")
            AppLog.i("JoiPlayPluginInstall", "Downloading ${download.title} from MEGA to ${outFile.name}")
            MegaClient.downloadPublicFile(url = link, outFile = outFile, onProgress = onProgress)
            AppLog.i("JoiPlayPluginInstall", "Downloaded ${outFile.length()} bytes; launching installer")
            withContext(Dispatchers.Main) { AppUpdater().install(context, outFile) }
            Outcome.InstallerLaunched
        } catch (t: Throwable) {
            AppLog.w("JoiPlayPluginInstall", "In-app install failed; falling back to deep-link", t)
            Outcome.FellBack(link, t.message ?: "download failed")
        }
    }

    private fun safeStem(title: String): String =
        title.trim().replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "plugin" }
}
