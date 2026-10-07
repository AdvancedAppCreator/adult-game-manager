package com.example.f95updater

import java.io.File

/**
 * Rebuilds every runner binding of an installed managed game against a freshly extracted upgrade
 * folder. The result is a complete, validated replacement [ManagedGame] (same UUID, same user
 * state) plus the Winlator re-point that still has to be applied natively, or an explicit
 * rejection produced *before* anything is mutated.
 */
object ManagedUpgradeBindingMigration {
    data class WinlatorRepath(
        val winlatorGameId: String,
        val oldGamePath: String,
        val oldExecutablePath: String,
        val newGamePath: String,
        val newExecutablePath: String,
    )

    sealed interface Outcome {
        data class Ready(
            val replacement: ManagedGame,
            val winlator: WinlatorRepath?,
            val droppedRunners: List<ManagedRunnerKind>,
        ) : Outcome

        data class Rejected(val message: String) : Outcome
    }

    fun plan(
        game: ManagedGame,
        newRoot: File,
        discovered: List<ManagedRunnerBinding>,
        now: Long = System.currentTimeMillis(),
    ): Outcome {
        val newCanonicalPath = runCatching { canonicalManagedGamePath(newRoot.absolutePath) }
            .getOrElse { return Outcome.Rejected("The upgraded folder does not resolve to a normal path.") }
        val discoveredByKind = discovered.associateBy { it.kind }
        val migrated = mutableListOf<ManagedRunnerBinding>()
        val dropped = mutableListOf<ManagedRunnerKind>()
        var winlatorRepath: WinlatorRepath? = null

        for (binding in game.runnerBindings) {
            val required = binding.enabled || binding.kind == game.defaultRunner
            when (binding) {
                is ManagedRunnerBinding.JoiPlay -> {
                    val next = discoveredByKind[ManagedRunnerKind.JoiPlay] as? ManagedRunnerBinding.JoiPlay
                    if (next == null) {
                        if (required) {
                            return Outcome.Rejected(
                                "The new archive has no JoiPlay-compatible launch file, so AGM can't move " +
                                    "the JoiPlay runner for ${game.label}.",
                            )
                        }
                        dropped += binding.kind
                    } else {
                        migrated += next.copy(
                            enabled = binding.enabled,
                            compatible = true,
                            settingsJson = binding.settingsJson,
                            importId = binding.importId,
                        )
                    }
                }

                is ManagedRunnerBinding.Kirikiroid -> {
                    val next = discoveredByKind[ManagedRunnerKind.Kirikiroid] as? ManagedRunnerBinding.Kirikiroid
                    if (next == null) {
                        if (required) {
                            return Outcome.Rejected(
                                "The new archive has no KiriKiri startup file or XP3 archive, so AGM can't " +
                                    "move the Kirikiroid runner for ${game.label}.",
                            )
                        }
                        dropped += binding.kind
                    } else {
                        migrated += next.copy(
                            enabled = binding.enabled,
                            compatible = true,
                            entryPoint = binding.entryPoint?.takeIf { File(newRoot, it).isFile },
                            launchArguments = binding.launchArguments,
                            metadata = binding.metadata,
                        )
                    }
                }

                is ManagedRunnerBinding.Winlator -> {
                    val oldExecutable = binding.executablePath?.trim()?.takeIf { it.isNotBlank() }
                    val portable = oldExecutable != null && isInside(game.canonicalPath, oldExecutable)
                    val newExecutable = if (portable) {
                        resolveNewExecutable(game.canonicalPath, oldExecutable!!, newRoot, discoveredByKind)
                    } else {
                        null
                    }
                    if (newExecutable == null) {
                        if (required) {
                            return Outcome.Rejected(
                                if (!portable) {
                                    "${game.label} runs in Winlator from an executable installed inside its " +
                                        "container, not from its AGM game folder, so AGM can't upgrade it from " +
                                        "an archive."
                                } else {
                                    "The new archive has no Windows executable, so AGM can't move the Winlator " +
                                        "runner for ${game.label}."
                                },
                            )
                        }
                        dropped += binding.kind
                    } else {
                        migrated += binding.copy(
                            compatible = true,
                            executablePath = newExecutable.absolutePath,
                            executableDosPath = null,
                        )
                        val winlatorGameId = binding.managedId?.trim()?.takeIf { it.isNotBlank() }
                        if (winlatorGameId != null) {
                            winlatorRepath = WinlatorRepath(
                                winlatorGameId = winlatorGameId,
                                oldGamePath = File(oldExecutable!!).parent ?: game.storagePath,
                                oldExecutablePath = oldExecutable,
                                newGamePath = requireNotNull(newExecutable.parentFile).absolutePath,
                                newExecutablePath = newExecutable.absolutePath,
                            )
                        }
                    }
                }
            }
        }

        if (migrated.isEmpty()) {
            return Outcome.Rejected("The new archive has no runner AGM can use for ${game.label}.")
        }
        val defaultBinding = migrated.firstOrNull { it.kind == game.defaultRunner }
        if (defaultBinding == null || !defaultBinding.enabled || !defaultBinding.compatible) {
            return Outcome.Rejected(
                "The new archive can't run under ${game.defaultRunner}, which is the default engine for " +
                    "${game.label}.",
            )
        }
        val replacement = runCatching {
            validateManagedGame(
                game.copy(
                    canonicalPath = newCanonicalPath,
                    storagePath = newRoot.absolutePath,
                    storageFolderName = newRoot.name,
                    runnerBindings = ManagedRunnerKind.entries.mapNotNull { kind ->
                        migrated.firstOrNull { it.kind == kind }
                    },
                    lastUpdateTime = now,
                    updatedAt = now,
                ),
            )
        }.getOrElse {
            return Outcome.Rejected("The upgraded configuration is not valid: ${it.message}")
        }
        return Outcome.Ready(replacement, winlatorRepath, dropped)
    }

    private fun resolveNewExecutable(
        oldCanonicalRoot: String,
        oldExecutablePath: String,
        newRoot: File,
        discoveredByKind: Map<ManagedRunnerKind, ManagedRunnerBinding>,
    ): File? {
        val relative = relativeUnder(oldCanonicalRoot, oldExecutablePath)
        val sameRelative = relative?.let { File(newRoot, it) }?.takeIf { it.isFile }
        if (sameRelative != null) return sameRelative
        val discovered = discoveredByKind[ManagedRunnerKind.Winlator] as? ManagedRunnerBinding.Winlator
        return discovered?.executablePath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    }

    private fun relativeUnder(canonicalRoot: String, absolutePath: String): String? {
        val canonicalChild = runCatching { canonicalManagedGamePath(absolutePath) }.getOrNull() ?: return null
        val prefix = "$canonicalRoot/"
        if (!canonicalChild.startsWith(prefix)) return null
        return canonicalChild.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    private fun isInside(canonicalRoot: String, absolutePath: String): Boolean =
        relativeUnder(canonicalRoot, absolutePath) != null
}
