package com.example.f95updater

import java.io.File

data class ManagedRunnerCandidate(
    val kind: ManagedRunnerKind,
    val compatible: Boolean,
    val available: Boolean,
    val explanation: String,
    val binding: ManagedRunnerBinding?,
)

data class ManagedGameInspection(
    val root: File,
    val title: String,
    val advice: InstallRecommendation.RunnerAdvice,
    val candidates: List<ManagedRunnerCandidate>,
) {
    val recommendedRunner: ManagedRunnerKind
        get() = when (advice.recommended) {
            InstallRecommendation.Runner.JoiPlay -> ManagedRunnerKind.JoiPlay
            InstallRecommendation.Runner.Winlator -> ManagedRunnerKind.Winlator
            InstallRecommendation.Runner.Kirikiroid -> ManagedRunnerKind.Kirikiroid
        }

}

data class ManagedEngineDiscoveryProgress(
    val current: Int,
    val total: Int,
    val gameTitle: String,
    val stage: String,
)

data class ManagedEngineDiscoverySummary(
    val scanned: Int,
    val updated: Int,
    val failed: Int,
)

object ManagedGameDiscovery {
    private const val MAX_FILES = 20_000

    fun inspect(
        root: File,
        selectedLaunchFile: File? = null,
        joiPlayAvailable: Boolean,
        winlatorAvailable: Boolean,
        kirikiroidAvailable: Boolean,
    ): ManagedGameInspection {
        require(root.isAbsolute) { "Managed game folder must use an absolute path." }
        require(root.isDirectory && root.canRead() && root.canWrite()) {
            "Managed game folder must be readable and writable: ${root.absolutePath}"
        }
        selectedLaunchFile?.let {
            require(it.isFile && it.canRead()) { "Selected launch file is not readable: ${it.absolutePath}" }
            require(isInside(root, it)) { "Selected launch file must be inside the managed game folder." }
        }

        val files = listFiles(root)
        require(files.isNotEmpty()) { "Managed game folder contains no files." }
        val relativePaths = files.map { it.relativeTo(root).invariantSeparatorsPath }
        val advice = InstallRecommendation.analyze(relativePaths)
        val executable = selectedLaunchFile?.takeIf { it.extension.equals("exe", true) }
            ?: chooseWindowsExecutable(root, files)
        val joiPlayLaunch = selectedLaunchFile?.takeIf { InstallRouting.isJoiPlayLaunchCandidate(it.name) }
            ?: chooseJoiPlayLaunch(root, files, advice.engine)
        val kirikiroidStartup = chooseKirikiroidStartup(root, files)
        val joiPlayType = joiPlayType(advice.engine, files)

        val joiBinding = if (advice.joiPlayCapable && joiPlayLaunch != null && joiPlayType != null) {
            ManagedRunnerBinding.JoiPlay(
                type = joiPlayType,
                execFile = joiPlayLaunch.relativeTo(root).invariantSeparatorsPath,
            )
        } else {
            null
        }
        val winlatorBinding = executable?.let {
            ManagedRunnerBinding.Winlator(
                executablePath = it.absolutePath,
                state = "setup_required",
            )
        }
        val kirikiroidBinding = kirikiroidStartup?.let {
            ManagedRunnerBinding.Kirikiroid(
                startupPath = it.relativeTo(root).invariantSeparatorsPath.ifBlank { "." },
            )
        }
        val title = readGameReadmeTitleNear(root)
            ?: root.name.trim().ifBlank { selectedLaunchFile?.nameWithoutExtension ?: "Managed game" }

        return ManagedGameInspection(
            root = root.canonicalFile,
            title = title,
            advice = advice,
            candidates = listOf(
                ManagedRunnerCandidate(
                    kind = ManagedRunnerKind.JoiPlay,
                    compatible = joiBinding != null,
                    available = joiPlayAvailable,
                    explanation = when {
                        joiBinding == null -> "No validated JoiPlay engine and launch file were found."
                        !joiPlayAvailable -> "Compatible, but the required JoiPlay runtime is not installed."
                        else -> advice.reasons.firstOrNull()
                            ?: "A compatible JoiPlay runtime and launch file were detected."
                    },
                    binding = joiBinding,
                ),
                ManagedRunnerCandidate(
                    kind = ManagedRunnerKind.Winlator,
                    compatible = winlatorBinding != null,
                    available = winlatorAvailable,
                    explanation = when {
                        winlatorBinding == null ->
                            "No Windows executable exists in shared files, so Winlator cannot launch this game."
                        !winlatorAvailable -> "Compatible, but Winlator Secure is not installed or available."
                        advice.recommended == InstallRecommendation.Runner.Winlator ->
                            advice.reasons.firstOrNull() ?: "A Windows executable was detected."
                        else -> "A Windows executable was detected; Winlator is available as an alternative."
                    },
                    binding = winlatorBinding,
                ),
                ManagedRunnerCandidate(
                    kind = ManagedRunnerKind.Kirikiroid,
                    compatible = kirikiroidBinding != null,
                    available = kirikiroidAvailable,
                    explanation = when {
                        kirikiroidBinding == null -> "No KiriKiri startup.tjs or XP3 archive was found."
                        !kirikiroidAvailable -> "Compatible, but Kirikiroid2 is not installed."
                        else -> advice.reasons.firstOrNull()
                            ?: "KiriKiri game data was detected."
                    },
                    binding = kirikiroidBinding,
                ),
            ),
        )
    }

    fun draft(
        inspection: ManagedGameInspection,
        enabledRunners: Set<ManagedRunnerKind>,
        defaultRunner: ManagedRunnerKind,
        label: String = inspection.title,
    ): ManagedGameDraft {
        require(enabledRunners.isNotEmpty()) { "Select at least one execution engine." }
        require(defaultRunner in enabledRunners) { "Default engine must be selected." }
        val bindings = inspection.candidates.mapNotNull { candidate ->
            val binding = candidate.binding ?: return@mapNotNull null
            binding.withState(
                enabled = candidate.kind in enabledRunners,
                compatible = candidate.compatible,
            )
        }

        require(enabledRunners.all { selected -> bindings.any { it.kind == selected } }) {
            "Every selected engine must have a validated launch configuration."
        }
        return ManagedGameDraft(
            storagePath = inspection.root.absolutePath,
            storageFolderName = inspection.root.name,
            label = label.trim().ifBlank { inspection.title },
            firstInstallTime = inspection.root.lastModified(),
            lastUpdateTime = inspection.root.lastModified(),
            readmeTitle = readGameReadmeTitleNear(inspection.root),
            defaultRunner = defaultRunner,
            runnerBindings = bindings,
        )
    }

    fun mergeDiscoveredBindings(
        existing: List<ManagedRunnerBinding>,
        inspection: ManagedGameInspection,
    ): List<ManagedRunnerBinding> {
        val byKind = existing.associateBy { it.kind }.toMutableMap()
        inspection.candidates.forEach { candidate ->
            if (candidate.binding != null && candidate.kind !in byKind) {
                byKind[candidate.kind] = candidate.binding.withState(
                    enabled = candidate.kind != ManagedRunnerKind.Winlator,
                    compatible = candidate.compatible,
                )
            }
        }
        return ManagedRunnerKind.entries.mapNotNull(byKind::get)
    }

    suspend fun discoverAll(
        context: android.content.Context,
        onProgress: (ManagedEngineDiscoveryProgress) -> Unit,
    ): ManagedEngineDiscoverySummary {
        val appContext = context.applicationContext
        val store = ManagedGameStore(appContext)
        val games = store.get()
        val joiPlayAvailable = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(JoiPlayUpdateChecker.PACKAGE, 0)
            true
        }.getOrDefault(false)
        val winlatorAvailable = WinlatorClient.isInstalled(appContext)
        val kirikiroidAvailable = KirikiroidLauncher.isInstalled(appContext)
        var updated = 0
        var failed = 0
        games.forEachIndexed { index, game ->
            onProgress(
                ManagedEngineDiscoveryProgress(
                    current = index,
                    total = games.size,
                    gameTitle = game.label,
                    stage = "Inspecting files",
                ),
            )
            runCatching {
                val inspection = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    inspect(
                        root = File(game.storagePath),
                        joiPlayAvailable = joiPlayAvailable,
                        winlatorAvailable = winlatorAvailable,
                        kirikiroidAvailable = kirikiroidAvailable,
                    )
                }
                val latest = store.find(game.id) ?: return@runCatching
                val bindings = mergeDiscoveredBindings(latest.runnerBindings, inspection)
                if (bindings != latest.runnerBindings) {
                    store.update(latest.copy(runnerBindings = bindings))
                    updated++
                }
            }.onFailure {
                failed++
                AppLog.w("EngineDiscovery", "Could not inspect ${game.label}", it)
            }
            onProgress(
                ManagedEngineDiscoveryProgress(
                    current = index + 1,
                    total = games.size,
                    gameTitle = game.label,
                    stage = "Completed",
                ),
            )
        }
        return ManagedEngineDiscoverySummary(
            scanned = games.size,
            updated = updated,
            failed = failed,
        )
    }

    private fun listFiles(root: File): List<File> {
        val result = ArrayList<File>()
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = directory.listFiles()
                ?: throw IllegalStateException("Cannot read directory: ${directory.absolutePath}")
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (child.isDirectory) {
                    pending.addLast(child)
                } else if (child.isFile) {
                    result += child
                    require(result.size <= MAX_FILES) {
                        "Game contains more than $MAX_FILES files; choose its actual game folder."
                    }
                }
            }
        }
        return result
    }

    private fun chooseWindowsExecutable(root: File, files: List<File>): File? =
        files.asSequence()
            .filter { it.extension.equals("exe", true) }
            .filterNot { it.name.equals("unins000.exe", true) || it.name.contains("config", true) }
            .minWithOrNull(compareBy<File>({ executableRank(it.name) }, { depth(root, it) }, { it.name.lowercase() }))

    private fun chooseJoiPlayLaunch(
        root: File,
        files: List<File>,
        engine: InstallRecommendation.GameEngine,
    ): File? {
        val preferred = when (engine) {
            InstallRecommendation.GameEngine.HTML -> setOf("html", "htm")
            InstallRecommendation.GameEngine.RENPY,
            InstallRecommendation.GameEngine.RPGM_RGSS,
            InstallRecommendation.GameEngine.RPGM_MV_MZ -> setOf("exe", "html", "htm", "py", "sh")
            else -> emptySet()
        }
        return files.asSequence()
            .filter { it.extension.lowercase() in preferred }
            .minWithOrNull(compareBy<File>({ joiPlayRank(it.name) }, { depth(root, it) }, { it.name.lowercase() }))
    }

    private fun chooseKirikiroidStartup(root: File, files: List<File>): File? =
        files.firstOrNull { it.name.equals("startup.tjs", true) }
            ?: files.firstOrNull { it.name.equals("data.xp3", true) }
            ?: files.asSequence()
                .filter { it.extension.equals("xp3", true) }
                .minWithOrNull(compareBy<File>({ depth(root, it) }, { it.name.lowercase() }))

    private fun joiPlayType(engine: InstallRecommendation.GameEngine, files: List<File>): String? =
        when (engine) {
            InstallRecommendation.GameEngine.RENPY -> "renpy"
            InstallRecommendation.GameEngine.RPGM_RGSS -> when {
                files.any { it.extension.equals("rgss3a", true) } -> "rpgmvxa"
                files.any { it.extension.equals("rgss2a", true) } -> "rpgmvx"
                else -> "rpgmxp"
            }
            InstallRecommendation.GameEngine.RPGM_MV_MZ ->
                if (files.any { it.name.equals("rmmz_core.js", true) }) "rpgmmz" else "rpgmmv"
            InstallRecommendation.GameEngine.HTML -> "html"
            else -> null
        }

    private fun executableRank(name: String): Int = when {
        name.equals("game.exe", true) -> 0
        name.endsWith("-win64-shipping.exe", true) -> 1
        else -> 2
    }

    private fun joiPlayRank(name: String): Int = when {
        name.equals("game.exe", true) -> 0
        name.equals("index.html", true) -> 1
        name.endsWith(".exe", true) -> 2
        else -> 3
    }

    private fun depth(root: File, file: File): Int =
        file.relativeTo(root).invariantSeparatorsPath.count { it == '/' }

    private fun isInside(root: File, file: File): Boolean {
        val canonicalRoot = root.canonicalFile.toPath()
        return file.canonicalFile.toPath().startsWith(canonicalRoot)
    }
}
