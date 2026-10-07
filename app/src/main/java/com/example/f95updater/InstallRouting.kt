package com.example.f95updater

object InstallRouting {
    // JoiPlay-runnable launch files that clearly indicate a game (NOT html — html is ambiguous
    // with plain video collections and is classified separately, see [routeArchive]).
    private val joiPlayGameLaunchExtensions = setOf("sh", "py", "swf", "jgp")
    private val htmlExtensions = setOf("html", "htm")

    // All extensions the picker should surface (html included so users can pick a bare html game).
    private val joiPlayLaunchExtensions = joiPlayGameLaunchExtensions + htmlExtensions

    private val archiveExtensions = setOf("zip", "rar", "7z")
    private val splitBundleExtensions = setOf("xapk", "apks", "apkm")

    /** Video containers used to distinguish a video collection from an HTML game or "other" junk. */
    val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "avi", "mov", "wmv", "webm", "flv",
        "mpg", "mpeg", "ts", "m2ts", "vob", "ogv", "3gp", "3g2",
    )

    val pickerExtensions: Set<String> =
        setOf("apk", "exe", "xp3", "tjs") +
            joiPlayLaunchExtensions +
            archiveExtensions +
            splitBundleExtensions

    /** Containers a game patch may ship in. The patch picker offers nothing else. */
    val patchArchiveExtensions: Set<String> = archiveExtensions

    enum class Target {
        Android,
        Winlator,
        JoiPlay,
        Kirikiroid,
        Managed,
        Auto,
    }

    /** Which non-library bucket an extracted payload belongs to. Bucket content is copied to a
     *  configured root folder and is NOT added to the AGM library. */
    enum class Bucket {
        Videos,
        Other,
    }

    sealed interface PickRoute {
        data object InstallApk : PickRoute

        /** A Windows .exe: offer a Winlator-vs-JoiPlay choice (with a recommendation). */
        data object ChooseRunner : PickRoute
        data object LaunchJoiPlayFile : PickRoute

        /** A bare .html/.htm file: inform the user and offer to try adding it to JoiPlay. */
        data object HtmlOnly : PickRoute
        data object InspectArchive : PickRoute
        data class Unsupported(val message: String) : PickRoute
    }

    sealed interface ArchiveRoute {
        data class Extract(val target: Target) : ArchiveRoute
        data class ExtractNested(val relativePath: String) : ArchiveRoute

        /** Archive contains a Windows .exe: offer a Winlator-vs-JoiPlay choice after extraction. */
        data object ChooseRunner : ArchiveRoute

        /** Archive is an HTML-only game (no videos): inform + offer JoiPlay. */
        data object HtmlOnly : ArchiveRoute

        /** Archive is a video collection: extract to the Videos root, do not add to the library. */
        data object Videos : ArchiveRoute

        /** Archive has no recognized game/video content: extract to the Other root. */
        data object Other : ArchiveRoute
        data class Unsupported(val message: String) : ArchiveRoute
    }

    enum class UpgradeInspectionRoute {
        ShowUpgradePrompt,
        ExtractAsNewInstall,
    }

    fun routePick(fileNameOrExtension: String): PickRoute {
        val ext = normalizedExtension(fileNameOrExtension)
        return when {
            ext in splitBundleExtensions -> PickRoute.Unsupported(splitBundleMessage)
            ext == "apk" -> PickRoute.InstallApk
            ext in setOf("exe", "xp3", "tjs") -> PickRoute.ChooseRunner
            ext in htmlExtensions -> PickRoute.HtmlOnly
            ext in joiPlayGameLaunchExtensions -> PickRoute.LaunchJoiPlayFile
            ext in archiveExtensions -> PickRoute.InspectArchive
            else -> PickRoute.Unsupported("AGM can't install that file type.")
        }
    }

    fun routeArchive(entryNames: List<String>): ArchiveRoute {
        val filePaths = entryNames
            .asSequence()
            .map { it.replace('\\', '/').trim() }
            .filter { it.isNotBlank() && !it.endsWith('/') }
            .map { it.trimStart('/') }
            .toList()
        val fileNames = filePaths
            .asSequence()
            .map { it.substringAfterLast('/') }
            .filter { it.isNotBlank() }
            .toList()
        val apkNames = fileNames.filter { normalizedExtension(it) == "apk" }
        val apkBases = apkNames.map { it.lowercase() }
        val hasSplitMarkers = apkBases.any {
            it.startsWith("split_") ||
                it.startsWith("config.") ||
                it.startsWith("config-") ||
                it.contains(".config.")
        }
        if (apkNames.size > 1 || hasSplitMarkers) {
            return ArchiveRoute.Unsupported(splitBundleMessage)
        }
        if (apkNames.size == 1) return ArchiveRoute.Extract(Target.Android)

        // A Windows executable: the caller resolves Winlator vs JoiPlay after extraction.
        if (fileNames.any { normalizedExtension(it) == "exe" }) {
            return ArchiveRoute.ChooseRunner
        }
        // A KiriKiri/KAG game shipped as .xp3 data with no .exe still needs a runner decision
        // (Kirikiroid2 natively, or Winlator for encrypted/plugin titles).
        if (fileNames.any { normalizedExtension(it) == "xp3" }) {
            return ArchiveRoute.ChooseRunner
        }
        // Clear JoiPlay game launch files (Ren'Py/RPGM/Flash) — these win over videos/html.
        if (fileNames.any { normalizedExtension(it) in joiPlayGameLaunchExtensions }) {
            return ArchiveRoute.Extract(Target.JoiPlay)
        }
        // Video collections win over bare HTML (an HTML thumbnail beside a movie is still a video).
        if (fileNames.any { normalizedExtension(it) in videoExtensions }) {
            return ArchiveRoute.Videos
        }
        if (fileNames.any { normalizedExtension(it) in htmlExtensions }) {
            return ArchiveRoute.HtmlOnly
        }
        val nestedArchives = filePaths.filter { normalizedExtension(it) in archiveExtensions }
        if (nestedArchives.size == 1) {
            return ArchiveRoute.ExtractNested(nestedArchives.single())
        }
        if (nestedArchives.size > 1) {
            return ArchiveRoute.Unsupported(
                "This archive contains multiple nested archives, which AGM can't install automatically."
            )
        }
        // Anything else with real files is treated as miscellaneous "other" content.
        if (fileNames.isNotEmpty()) return ArchiveRoute.Other
        return ArchiveRoute.Unsupported("This archive appears to be empty.")
    }

    fun routeUpgradeInspection(matches: List<Any>): UpgradeInspectionRoute =
        if (matches.isNotEmpty()) UpgradeInspectionRoute.ShowUpgradePrompt else UpgradeInspectionRoute.ExtractAsNewInstall

    /**
     * Whether a classified archive may replace an installed managed game. Android packages, video
     * and "other" payloads, nested wrappers and unsupported archives never can.
     */
    fun isManagedUpgradeEligible(route: ArchiveRoute): Boolean = when (route) {
        is ArchiveRoute.Extract -> when (route.target) {
            Target.JoiPlay, Target.Winlator, Target.Kirikiroid, Target.Managed -> true
            Target.Android, Target.Auto -> false
        }
        ArchiveRoute.ChooseRunner, ArchiveRoute.HtmlOnly -> true
        is ArchiveRoute.ExtractNested,
        ArchiveRoute.Videos,
        ArchiveRoute.Other,
        is ArchiveRoute.Unsupported -> false
    }

    /** JoiPlay can launch these directly: native .exe games (Ren'Py/RPGM) plus its script/html launchers. */
    fun isJoiPlayLaunchCandidate(fileName: String): Boolean {
        val ext = normalizedExtension(fileName)
        return ext == "exe" || ext in joiPlayGameLaunchExtensions || ext in htmlExtensions
    }

    fun isManagedLaunchCandidate(fileName: String): Boolean {
        val ext = normalizedExtension(fileName)
        return ext == "exe" ||
            ext == "xp3" ||
            ext == "tjs" ||
            ext in joiPlayGameLaunchExtensions ||
            ext in htmlExtensions
    }

    private fun normalizedExtension(fileNameOrExtension: String): String {
        val raw = fileNameOrExtension.trim().lowercase()
        if (raw.isBlank()) return ""
        return raw.substringAfterLast('.', raw).trimStart('.')
    }

    const val splitBundleMessage =
        "Split APK bundles (.xapk, .apks, .apkm, base + split APKs) aren't supported yet. AGM will not install only base.apk."
}
