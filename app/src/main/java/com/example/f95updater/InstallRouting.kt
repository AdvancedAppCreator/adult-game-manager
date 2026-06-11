package com.example.f95updater

object InstallRouting {
    private val joiPlayLaunchExtensions = setOf("exe", "sh", "py", "html", "swf", "jgp")
    private val archiveExtensions = setOf("zip", "rar", "7z")

    val joiPlayPickerExtensions: Set<String> = joiPlayLaunchExtensions + archiveExtensions
    val apkPickerExtensions: Set<String> = setOf("apk") + archiveExtensions

    enum class JoiPlayPickRoute {
        LaunchFile,
        InspectArchiveForUpgrade,
        Unsupported,
    }

    enum class ApkPickRoute {
        InstallApk,
        ExtractArchiveForApk,
        Unsupported,
    }

    enum class UpgradeInspectionRoute {
        ShowUpgradePrompt,
        ExtractAsNewInstall,
    }

    fun routeJoiPlayPick(fileNameOrExtension: String): JoiPlayPickRoute {
        val ext = normalizedExtension(fileNameOrExtension)
        return when {
            ext in archiveExtensions -> JoiPlayPickRoute.InspectArchiveForUpgrade
            ext in joiPlayLaunchExtensions -> JoiPlayPickRoute.LaunchFile
            else -> JoiPlayPickRoute.Unsupported
        }
    }

    fun routeApkPick(fileNameOrExtension: String): ApkPickRoute {
        val ext = normalizedExtension(fileNameOrExtension)
        return when {
            ext == "apk" -> ApkPickRoute.InstallApk
            ext in archiveExtensions -> ApkPickRoute.ExtractArchiveForApk
            else -> ApkPickRoute.Unsupported
        }
    }

    fun routeUpgradeInspection(matches: List<InstalledApp>): UpgradeInspectionRoute =
        if (matches.isNotEmpty()) UpgradeInspectionRoute.ShowUpgradePrompt else UpgradeInspectionRoute.ExtractAsNewInstall

    private fun normalizedExtension(fileNameOrExtension: String): String {
        val raw = fileNameOrExtension.trim().lowercase()
        if (raw.isBlank()) return ""
        return raw.substringAfterLast('.', raw).trimStart('.')
    }
}
