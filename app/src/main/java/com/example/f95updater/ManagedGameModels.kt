package com.example.f95updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator
import java.util.UUID

const val MANAGED_GAME_SCHEMA_VERSION = 1
const val MANAGED_GAME_PACKAGE_PREFIX = "managed:"
const val MANAGED_GAME_OWNERSHIP_FILE = ".agm-managed-game.json"

@Serializable
data class ManagedGameOwnership(
    val schemaVersion: Int = 1,
    val id: String,
    val canonicalPath: String,
)

@Serializable
enum class ManagedRunnerKind { JoiPlay, Winlator, Kirikiroid }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("runner")
sealed class ManagedRunnerBinding {
    abstract val enabled: Boolean
    abstract val compatible: Boolean
    abstract val kind: ManagedRunnerKind

    @Serializable
    @SerialName("joiplay")
    data class JoiPlay(
        override val enabled: Boolean = true,
        override val compatible: Boolean = true,
        val type: String,
        val execFile: String,
        val settingsJson: String? = null,
        val importId: String? = null,
    ) : ManagedRunnerBinding() {
        override val kind: ManagedRunnerKind = ManagedRunnerKind.JoiPlay
    }

    @Serializable
    @SerialName("winlator")
    data class Winlator(
        override val enabled: Boolean = true,
        override val compatible: Boolean = true,
        val managedId: String? = null,
        val executablePath: String? = null,
        val executableDosPath: String? = null,
        val containerId: Int? = null,
        val state: String? = null,
        val containerPolicy: String? = null,
        val containerShared: Boolean = false,
        val containerKey: String? = null,
        val containerReferenceCount: Int? = null,
        val containerAllocatedSizeBytes: Long? = null,
        val configJson: String? = null,
        val configSha256: String? = null,
        val effectiveWinVersion: String? = null,
        val winVersionSource: String? = null,
        val metadata: Map<String, String> = emptyMap(),
    ) : ManagedRunnerBinding() {
        override val kind: ManagedRunnerKind = ManagedRunnerKind.Winlator
    }

    @Serializable
    @SerialName("kirikiroid")
    data class Kirikiroid(
        override val enabled: Boolean = true,
        override val compatible: Boolean = true,
        val startupPath: String,
        val entryPoint: String? = null,
        val launchArguments: List<String> = emptyList(),
        val metadata: Map<String, String> = emptyMap(),
    ) : ManagedRunnerBinding() {
        override val kind: ManagedRunnerKind = ManagedRunnerKind.Kirikiroid
    }
}

@Serializable
data class ManagedGame(
    val id: String,
    val canonicalPath: String,
    val storagePath: String,
    val storageFolderName: String? = null,
    val label: String,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val readmeTitle: String? = null,
    val defaultRunner: ManagedRunnerKind,
    val runnerBindings: List<ManagedRunnerBinding>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val packageName: String get() = "$MANAGED_GAME_PACKAGE_PREFIX$id"
}

data class ManagedGameDraft(
    val storagePath: String,
    val storageFolderName: String? = null,
    val label: String,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val readmeTitle: String? = null,
    val defaultRunner: ManagedRunnerKind? = null,
    val runnerBindings: List<ManagedRunnerBinding>,
)

sealed class ManagedGameValidationException(message: String) : IllegalArgumentException(message) {
    class InvalidId(id: String) : ManagedGameValidationException("Invalid managed-game UUID: $id")
    class InvalidPath : ManagedGameValidationException("Managed-game storage path must be absolute.")
    class BlankLabel : ManagedGameValidationException("Managed-game label must not be blank.")
    class MissingRunner : ManagedGameValidationException("Managed game must have at least one runner binding.")
    class DuplicateRunner(kind: ManagedRunnerKind) :
        ManagedGameValidationException("Managed game has duplicate $kind runner bindings.")
    class InvalidDefaultRunner :
        ManagedGameValidationException("Default runner must exist and be enabled and compatible.")
    class InvalidRunner(message: String) : ManagedGameValidationException(message)
}

fun canonicalManagedGamePath(rawPath: String): String {
    val replaced = rawPath.trim().replace('\\', '/')
    val prefix = when {
        replaced.startsWith("/") -> "/"
        Regex("^[A-Za-z]:/").containsMatchIn(replaced) ->
            replaced.substring(0, 1).lowercase() + ":/"
        else -> throw ManagedGameValidationException.InvalidPath()
    }
    val body = if (prefix == "/") replaced.drop(1) else replaced.drop(3)
    val segments = ArrayDeque<String>()
    for (segment in body.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (segments.isEmpty()) throw ManagedGameValidationException.InvalidPath()
                segments.removeLast()
            }
            else -> segments.addLast(segment)
        }
    }
    return prefix + segments.joinToString("/")
}

fun validateManagedRunnerBinding(binding: ManagedRunnerBinding) {
    when (binding) {
        is ManagedRunnerBinding.JoiPlay -> {
            if (binding.type.isBlank() || binding.execFile.isBlank()) {
                throw ManagedGameValidationException.InvalidRunner(
                    "JoiPlay type and exec file must not be blank.",
                )
            }
        }
        is ManagedRunnerBinding.Winlator -> {
            if (binding.managedId.isNullOrBlank() &&
                binding.executablePath.isNullOrBlank() &&
                binding.executableDosPath.isNullOrBlank()
            ) {
                throw ManagedGameValidationException.InvalidRunner(
                    "Winlator binding requires a managed id or executable path.",
                )
            }
        }
        is ManagedRunnerBinding.Kirikiroid -> {
            if (binding.startupPath.isBlank()) {
                throw ManagedGameValidationException.InvalidRunner(
                    "Kirikiroid startup path must not be blank.",
                )
            }
        }
    }
}

fun validateManagedGame(game: ManagedGame): ManagedGame {
    try {
        UUID.fromString(game.id)
    } catch (_: IllegalArgumentException) {
        throw ManagedGameValidationException.InvalidId(game.id)
    }
    if (game.label.isBlank()) throw ManagedGameValidationException.BlankLabel()
    if (game.runnerBindings.isEmpty()) throw ManagedGameValidationException.MissingRunner()
    val duplicate = game.runnerBindings.groupingBy { it.kind }.eachCount().entries.firstOrNull { it.value > 1 }
    if (duplicate != null) throw ManagedGameValidationException.DuplicateRunner(duplicate.key)
    game.runnerBindings.forEach(::validateManagedRunnerBinding)
    val selected = game.runnerBindings.firstOrNull { it.kind == game.defaultRunner }
    if (selected == null || !selected.enabled || !selected.compatible) {
        throw ManagedGameValidationException.InvalidDefaultRunner()
    }
    val canonical = canonicalManagedGamePath(game.storagePath)
    if (canonical != game.canonicalPath) {
        throw ManagedGameValidationException.InvalidRunner("Managed-game canonical path is not normalized.")
    }
    return game
}

internal fun ManagedRunnerBinding.withState(enabled: Boolean, compatible: Boolean): ManagedRunnerBinding =
    when (this) {
        is ManagedRunnerBinding.JoiPlay -> copy(enabled = enabled, compatible = compatible)
        is ManagedRunnerBinding.Winlator -> copy(enabled = enabled, compatible = compatible)
        is ManagedRunnerBinding.Kirikiroid -> copy(enabled = enabled, compatible = compatible)
    }

fun mergeManagedRunnerBindings(
    existing: List<ManagedRunnerBinding>,
    incoming: List<ManagedRunnerBinding>,
): List<ManagedRunnerBinding> {
    val merged = existing.associateBy { it.kind }.toMutableMap()
    incoming.forEach { merged[it.kind] = it }
    return ManagedRunnerKind.entries.mapNotNull(merged::get)
}

internal fun buildManagedGame(
    draft: ManagedGameDraft,
    existing: ManagedGame?,
    idFactory: () -> String,
    now: Long,
): ManagedGame {
    val bindings = mergeManagedRunnerBindings(existing?.runnerBindings.orEmpty(), draft.runnerBindings)
    val defaultRunner = draft.defaultRunner
        ?: existing?.defaultRunner?.takeIf { kind ->
            bindings.any { it.kind == kind && it.enabled && it.compatible }
        }
        ?: bindings.firstOrNull { it.enabled && it.compatible }?.kind
        ?: throw ManagedGameValidationException.InvalidDefaultRunner()
    return validateManagedGame(
        ManagedGame(
            id = existing?.id ?: idFactory(),
            canonicalPath = canonicalManagedGamePath(draft.storagePath),
            storagePath = draft.storagePath.trim(),
            storageFolderName = draft.storageFolderName ?: existing?.storageFolderName,
            label = draft.label.trim(),
            versionName = draft.versionName,
            versionCode = draft.versionCode,
            firstInstallTime = draft.firstInstallTime,
            lastUpdateTime = draft.lastUpdateTime,
            readmeTitle = draft.readmeTitle ?: existing?.readmeTitle,
            defaultRunner = defaultRunner,
            runnerBindings = bindings,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        ),
    )
}

class ManagedGameRelocationException(message: String) : IllegalArgumentException(message)

/**
 * Validates an in-place relocation of an existing managed game to a new folder. The UUID,
 * creation time and every user-configured field of [replacement] are preserved as supplied; only
 * the canonical path and update timestamp are recomputed. Re-running the plan after the relocation
 * already committed is accepted so crash recovery is idempotent.
 */
fun planManagedGameRelocation(
    current: List<ManagedGame>,
    expectedOldCanonicalPath: String,
    replacement: ManagedGame,
    now: Long = System.currentTimeMillis(),
): ManagedGame {
    val existing = current.firstOrNull { it.id == replacement.id }
        ?: throw ManagedGameRelocationException("Unknown managed-game id: ${replacement.id}")
    val oldPath = canonicalManagedGamePath(expectedOldCanonicalPath)
    val newPath = canonicalManagedGamePath(replacement.storagePath)
    if (existing.canonicalPath != oldPath && existing.canonicalPath != newPath) {
        throw ManagedGameRelocationException(
            "Managed game ${replacement.id} is at ${existing.canonicalPath}, not $oldPath.",
        )
    }
    if (current.any { it.id != replacement.id && it.canonicalPath == newPath }) {
        throw ManagedGameRelocationException("Another managed game already uses $newPath")
    }
    return validateManagedGame(
        replacement.copy(
            id = existing.id,
            createdAt = existing.createdAt,
            canonicalPath = newPath,
            updatedAt = now,
        ),
    )
}

fun upsertManagedGames(
    current: List<ManagedGame>,
    drafts: List<ManagedGameDraft>,
    idFactory: () -> String = { UUID.randomUUID().toString() },
    now: Long = System.currentTimeMillis(),
): List<ManagedGame> {
    val byPath = current.associateBy { it.canonicalPath }.toMutableMap()
    drafts.forEach { draft ->
        val path = canonicalManagedGamePath(draft.storagePath)
        byPath[path] = buildManagedGame(draft, byPath[path], idFactory, now)
    }
    return byPath.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

fun legacyInstalledAppToManagedDraft(app: InstalledApp): ManagedGameDraft {
    require(
        app.source == AppSource.JoiPlay ||
            app.source == AppSource.Winlator ||
            app.source == AppSource.Kirikiroid,
    ) {
        "Legacy migration accepts only legacy non-Android InstalledApp rows."
    }
    val path = app.storagePath?.takeIf { it.isNotBlank() }
        ?: throw ManagedGameValidationException.InvalidPath()
    val binding = when (app.source) {
        AppSource.JoiPlay -> ManagedRunnerBinding.JoiPlay(
            type = app.joiPlayType.orEmpty(),
            execFile = app.joiPlayExecFile.orEmpty(),
            settingsJson = app.joiPlaySettingsJson,
            importId = app.joiPlayGameId,
        )
        AppSource.Winlator -> ManagedRunnerBinding.Winlator(
            managedId = app.winlatorGameId,
            executablePath = app.winlatorExecutablePath,
            executableDosPath = app.winlatorExecutableDosPath,
            containerId = app.winlatorContainerId,
            state = app.winlatorState,
            containerPolicy = app.winlatorContainerPolicy,
            containerShared = app.winlatorContainerShared,
            containerKey = app.winlatorContainerKey,
            containerReferenceCount = app.winlatorContainerReferenceCount,
            containerAllocatedSizeBytes = app.winlatorContainerAllocatedSizeBytes,
            configJson = app.winlatorConfigJson,
            configSha256 = app.winlatorConfigSha256,
            effectiveWinVersion = app.winlatorEffectiveWinVersion,
            winVersionSource = app.winlatorWinVersionSource,
        )
        AppSource.Kirikiroid -> ManagedRunnerBinding.Kirikiroid(
            startupPath = app.kirikiroidStartupPath ?: app.storagePath,
            entryPoint = app.kirikiroidEntryPoint,
            launchArguments = app.kirikiroidLaunchArguments,
            metadata = app.kirikiroidMetadata,
        )
        AppSource.Android, AppSource.Managed -> error("Checked above")
    }
    validateManagedRunnerBinding(binding)
    return ManagedGameDraft(
        storagePath = path,
        storageFolderName = app.storageFolderName,
        label = app.label,
        versionName = app.versionName,
        versionCode = app.versionCode,
        firstInstallTime = app.firstInstallTime,
        lastUpdateTime = app.lastUpdateTime,
        readmeTitle = app.readmeTitle,
        defaultRunner = binding.kind,
        runnerBindings = listOf(binding),
    )
}

fun ManagedGame.toInstalledApp(): InstalledApp {
    val joiPlay = runnerBindings.filterIsInstance<ManagedRunnerBinding.JoiPlay>().singleOrNull()
    val winlator = runnerBindings.filterIsInstance<ManagedRunnerBinding.Winlator>().singleOrNull()
    val kirikiroid = runnerBindings.filterIsInstance<ManagedRunnerBinding.Kirikiroid>().singleOrNull()
    return InstalledApp(
        packageName = packageName,
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        source = AppSource.Managed,
        managedGameId = id,
        managedDefaultRunner = defaultRunner,
        managedRunnerBindings = runnerBindings,
        storagePath = storagePath,
        storageFolderName = storageFolderName,
        joiPlayGameId = joiPlay?.importId,
        joiPlayType = joiPlay?.type,
        joiPlayExecFile = joiPlay?.execFile,
        joiPlaySettingsJson = joiPlay?.settingsJson,
        readmeTitle = readmeTitle,
        winlatorGameId = winlator?.managedId,
        winlatorContainerId = winlator?.containerId,
        winlatorState = winlator?.state,
        winlatorExecutablePath = winlator?.executablePath,
        winlatorExecutableDosPath = winlator?.executableDosPath,
        winlatorContainerPolicy = winlator?.containerPolicy,
        winlatorContainerShared = winlator?.containerShared ?: false,
        winlatorContainerKey = winlator?.containerKey,
        winlatorContainerReferenceCount = winlator?.containerReferenceCount,
        winlatorContainerAllocatedSizeBytes = winlator?.containerAllocatedSizeBytes,
        winlatorConfigJson = winlator?.configJson,
        winlatorConfigSha256 = winlator?.configSha256,
        winlatorEffectiveWinVersion = winlator?.effectiveWinVersion,
        winlatorWinVersionSource = winlator?.winVersionSource,
        kirikiroidStartupPath = kirikiroid?.startupPath,
        kirikiroidEntryPoint = kirikiroid?.entryPoint,
        kirikiroidLaunchArguments = kirikiroid?.launchArguments.orEmpty(),
        kirikiroidMetadata = kirikiroid?.metadata.orEmpty(),
    )
}
