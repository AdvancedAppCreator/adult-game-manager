package com.example.f95updater

import android.content.Context
import android.content.Intent
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.File

const val WINLATOR_REGISTRATION_MISSING = "registration_missing"

enum class WinlatorRegistrationKind {
    PortableExternal,
    ContainerOwned,
}

fun winlatorRegistrationKind(
    binding: ManagedRunnerBinding.Winlator,
): WinlatorRegistrationKind =
    if (binding.executablePath.isNullOrBlank()) {
        WinlatorRegistrationKind.ContainerOwned
    } else {
        WinlatorRegistrationKind.PortableExternal
    }

fun markWinlatorRegistrationMissing(
    binding: ManagedRunnerBinding.Winlator,
): ManagedRunnerBinding.Winlator {
    if (binding.managedId.isNullOrBlank()) return binding
    return binding.copy(
        executableDosPath = null,
        containerId = null,
        state = WINLATOR_REGISTRATION_MISSING,
        containerShared = binding.containerPolicy == WinlatorApi.ContainerPolicy.SharedDefault.wireValue,
        containerReferenceCount = null,
        containerAllocatedSizeBytes = null,
        configSha256 = null,
        effectiveWinVersion = null,
        winVersionSource = null,
        metadata = binding.metadata - "agmBoundAtMs",
    )
}

data class WinlatorPortableRegistration(
    val gameId: String,
    val title: String,
    val gamePath: String,
    val executablePath: String,
    val containerPolicy: WinlatorApi.ContainerPolicy,
    val containerKey: String? = null,
    val metadata: String,
)

@Serializable
data class WinlatorPortableRecoveryCandidate(
    val managedGameId: String,
    val gameId: String,
    val title: String,
    val storageRoot: String,
    val gamePath: String,
    val executablePath: String,
    val containerPolicy: String,
    val containerKey: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val desiredConfigJson: String? = null,
) {
    fun registration(): WinlatorPortableRegistration = WinlatorPortableRegistration(
        gameId = gameId,
        title = title,
        gamePath = gamePath,
        executablePath = executablePath,
        containerPolicy = requireNotNull(WinlatorApi.ContainerPolicy.fromWireValue(containerPolicy)),
        containerKey = containerKey,
        metadata = JSONObject().apply {
            metadata.forEach { (key, value) -> put(key, value) }
            put("source", "agm-registration-recovery")
            put("sourcePath", executablePath)
            put("managedGameId", managedGameId)
            put("recoveredAt", System.currentTimeMillis())
        }.toString(),
    )
}

sealed interface WinlatorPortableRecoveryEligibility {
    data class Eligible(
        val candidate: WinlatorPortableRecoveryCandidate,
    ) : WinlatorPortableRecoveryEligibility
    data class Ineligible(val message: String) : WinlatorPortableRecoveryEligibility
}

sealed interface WinlatorRegistrationIdentity {
    data object Match : WinlatorRegistrationIdentity
    data class Conflict(val reason: String) : WinlatorRegistrationIdentity
}

sealed interface WinlatorRegistrationPreflight {
    data class Registered(val game: WinlatorApi.ManagedGame) : WinlatorRegistrationPreflight
    data object Missing : WinlatorRegistrationPreflight
    data class Conflict(
        val reason: String,
        val game: WinlatorApi.ManagedGame,
    ) : WinlatorRegistrationPreflight
    data class ProviderError(val code: String, val message: String) : WinlatorRegistrationPreflight
    data class Unavailable(val reason: String) : WinlatorRegistrationPreflight
}

sealed interface WinlatorCreateReconciliation {
    data class Recovered(
        val game: WinlatorApi.ManagedGame,
        val createReconciled: Boolean,
    ) : WinlatorCreateReconciliation
    data class Missing(
        val operationCode: String?,
        val operationMessage: String?,
    ) : WinlatorCreateReconciliation
    data class Conflict(
        val reason: String,
        val game: WinlatorApi.ManagedGame?,
    ) : WinlatorCreateReconciliation
    data class OperationRejected(
        val code: String,
        val message: String,
        val game: WinlatorApi.ManagedGame?,
    ) : WinlatorCreateReconciliation
    data class ProviderError(val code: String, val message: String) : WinlatorCreateReconciliation
    data class Unavailable(val reason: String) : WinlatorCreateReconciliation
}

fun interface WinlatorGameReader {
    suspend fun getGame(gameId: String): WinlatorClient.Read
}

class AndroidWinlatorGameReader(
    private val context: Context,
) : WinlatorGameReader {
    override suspend fun getGame(gameId: String): WinlatorClient.Read =
        WinlatorClient.getGame(context.applicationContext, gameId)
}

class WinlatorRecoverySingleFlight {
    private val active = mutableSetOf<String>()

    @Synchronized
    fun begin(managedGameId: String): Boolean = active.add(managedGameId)

    @Synchronized
    fun end(managedGameId: String) {
        active.remove(managedGameId)
    }
}

fun WinlatorPortableRegistration.createIntent(): Intent =
    WinlatorApi.createPortable(
        gameId = gameId,
        title = title,
        gamePath = gamePath,
        executablePath = executablePath,
        metadata = metadata,
        containerPolicy = containerPolicy,
    )

suspend fun preflightWinlatorRegistration(
    expected: WinlatorPortableRegistration,
    reader: WinlatorGameReader,
): WinlatorRegistrationPreflight =
    when (val read = reader.getGame(expected.gameId)) {
        is WinlatorClient.Read.Ok -> {
            val game = parseWinlatorGame(read.payload)
                ?: return WinlatorRegistrationPreflight.ProviderError(
                    "INVALID_GAME_JSON",
                    "Winlator returned invalid managed-game data.",
                )
            when (val identity = matchWinlatorRegistration(expected, game)) {
                WinlatorRegistrationIdentity.Match -> WinlatorRegistrationPreflight.Registered(game)
                is WinlatorRegistrationIdentity.Conflict ->
                    WinlatorRegistrationPreflight.Conflict(identity.reason, game)
            }
        }
        is WinlatorClient.Read.Err ->
            if (read.code == "GAME_NOT_FOUND") {
                WinlatorRegistrationPreflight.Missing
            } else {
                WinlatorRegistrationPreflight.ProviderError(read.code, read.message)
            }
        is WinlatorClient.Read.Unavailable -> WinlatorRegistrationPreflight.Unavailable(read.reason)
    }

suspend fun reconcileWinlatorPortableCreate(
    expected: WinlatorPortableRegistration,
    operation: WinlatorApi.OperationResult,
    reader: WinlatorGameReader,
): WinlatorCreateReconciliation {
    val operationGame = when (operation) {
        is WinlatorApi.OperationResult.Success -> operation.gameJson
        is WinlatorApi.OperationResult.Failure -> operation.gameJson
    }?.let(::parseWinlatorGame)
    return when (val read = reader.getGame(expected.gameId)) {
        is WinlatorClient.Read.Ok -> {
            val game = parseWinlatorGame(read.payload)
                ?: return WinlatorCreateReconciliation.ProviderError(
                    "INVALID_GAME_JSON",
                    "Winlator returned invalid managed-game data.",
                )
            when (val identity = matchWinlatorRegistration(expected, game)) {
                WinlatorRegistrationIdentity.Match -> WinlatorCreateReconciliation.Recovered(
                    game = game,
                    createReconciled =
                        (operation as? WinlatorApi.OperationResult.Success)?.createReconciled == true,
                )
                is WinlatorRegistrationIdentity.Conflict ->
                    WinlatorCreateReconciliation.Conflict(identity.reason, game)
            }
        }
        is WinlatorClient.Read.Err -> {
            if (read.code != "GAME_NOT_FOUND") {
                return WinlatorCreateReconciliation.ProviderError(read.code, read.message)
            }
            when (operation) {
                is WinlatorApi.OperationResult.Success ->
                    WinlatorCreateReconciliation.Missing(null, null)
                is WinlatorApi.OperationResult.Failure -> {
                    if (operation.code in AMBIGUOUS_CREATE_RESULTS) {
                        WinlatorCreateReconciliation.Missing(operation.code, operation.message)
                    } else {
                        WinlatorCreateReconciliation.OperationRejected(
                            operation.code,
                            operation.message,
                            operationGame,
                        )
                    }
                }
            }
        }
        is WinlatorClient.Read.Unavailable -> WinlatorCreateReconciliation.Unavailable(read.reason)
    }
}

fun matchWinlatorRegistration(
    expected: WinlatorPortableRegistration,
    actual: WinlatorApi.ManagedGame,
): WinlatorRegistrationIdentity {
    if (actual.id != expected.gameId) {
        return WinlatorRegistrationIdentity.Conflict("Winlator returned a different managed game id.")
    }
    val actualGamePath = actual.gamePath
        ?: return WinlatorRegistrationIdentity.Conflict("The Winlator entry is not a portable game.")
    val actualExecutablePath = actual.executablePath
        ?: return WinlatorRegistrationIdentity.Conflict(
            "The Winlator entry has no external executable.",
        )
    if (normalizeWinlatorRegistrationPath(actualGamePath) !=
        normalizeWinlatorRegistrationPath(expected.gamePath)
    ) {
        return WinlatorRegistrationIdentity.Conflict("The Winlator entry uses a different game folder.")
    }
    if (normalizeWinlatorRegistrationPath(actualExecutablePath) !=
        normalizeWinlatorRegistrationPath(expected.executablePath)
    ) {
        return WinlatorRegistrationIdentity.Conflict("The Winlator entry uses a different executable.")
    }
    if (actual.containerPolicy != expected.containerPolicy) {
        return WinlatorRegistrationIdentity.Conflict(
            "The Winlator entry uses a different container policy.",
        )
    }
    if (expected.containerPolicy == WinlatorApi.ContainerPolicy.SharedDefault) {
        val expectedKey = expected.containerKey ?: "agm.default"
        if (actual.containerKey != expectedKey) {
            return WinlatorRegistrationIdentity.Conflict(
                "The Winlator entry uses a different shared container.",
            )
        }
    }
    return WinlatorRegistrationIdentity.Match
}

fun matchContainerOwnedWinlatorRegistration(
    app: InstalledApp,
    actual: WinlatorApi.ManagedGame,
): WinlatorRegistrationIdentity {
    val gameId = app.winlatorGameId
        ?: return WinlatorRegistrationIdentity.Conflict("The saved Winlator game id is missing.")
    if (actual.id != gameId) {
        return WinlatorRegistrationIdentity.Conflict("Winlator returned a different managed game id.")
    }
    val expectedDosPath = app.winlatorExecutableDosPath
    if (
        !expectedDosPath.isNullOrBlank() &&
        normalizeWinlatorDosPath(actual.executableDosPath) !=
        normalizeWinlatorDosPath(expectedDosPath)
    ) {
        return WinlatorRegistrationIdentity.Conflict(
            "The Winlator entry uses a different installed executable.",
        )
    }
    val expectedPolicy = WinlatorApi.ContainerPolicy.fromWireValue(app.winlatorContainerPolicy)
    if (expectedPolicy != null && actual.containerPolicy != expectedPolicy) {
        return WinlatorRegistrationIdentity.Conflict(
            "The Winlator entry uses a different container policy.",
        )
    }
    if (expectedPolicy == WinlatorApi.ContainerPolicy.SharedDefault) {
        val expectedKey = app.winlatorContainerKey ?: "agm.default"
        if (actual.containerKey != expectedKey) {
            return WinlatorRegistrationIdentity.Conflict(
                "The Winlator entry uses a different shared container.",
            )
        }
    }
    return WinlatorRegistrationIdentity.Match
}

fun portableWinlatorRecoveryCandidate(
    app: InstalledApp,
): WinlatorPortableRecoveryEligibility {
    val managedGameId = app.managedGameId
        ?: return WinlatorPortableRecoveryEligibility.Ineligible(
            "AGM cannot restore a Winlator-only library entry. Add its files or installer again.",
        )
    val gameId = app.winlatorGameId
        ?: return WinlatorPortableRecoveryEligibility.Ineligible(
            "The saved Winlator game identity is missing.",
        )
    val executablePath = app.winlatorExecutablePath
        ?: return WinlatorPortableRecoveryEligibility.Ineligible(
            "This game was installed inside Winlator. Restore it from its installer or a backup; " +
                "the container-owned game files and saves were erased with Winlator.",
        )
    val storagePath = app.storagePath
        ?: return WinlatorPortableRecoveryEligibility.Ineligible("The managed game folder is missing.")
    val executable = File(executablePath)
    val storageRoot = File(storagePath)
    if (!executable.isAbsolute || !executable.isFile || !executable.canRead()) {
        return WinlatorPortableRecoveryEligibility.Ineligible(
            "The external game executable is missing or unreadable. Restore the game files first.",
        )
    }
    if (!storageRoot.isAbsolute || !storageRoot.isDirectory || !storageRoot.canRead()) {
        return WinlatorPortableRecoveryEligibility.Ineligible(
            "The managed game folder is missing or unreadable.",
        )
    }
    val canonicalExecutable = runCatching { executable.canonicalFile }.getOrNull()
        ?: return WinlatorPortableRecoveryEligibility.Ineligible(
            "The external executable path could not be resolved.",
        )
    val canonicalRoot = runCatching { storageRoot.canonicalFile }.getOrNull()
        ?: return WinlatorPortableRecoveryEligibility.Ineligible(
            "The managed game folder path could not be resolved.",
        )
    if (!canonicalExecutable.toPath().startsWith(canonicalRoot.toPath())) {
        return WinlatorPortableRecoveryEligibility.Ineligible(
            "The saved executable is outside AGM's managed game folder.",
        )
    }
    val policy = WinlatorApi.ContainerPolicy.fromWireValue(app.winlatorContainerPolicy)
        ?: return WinlatorPortableRecoveryEligibility.Ineligible(
            "The previous container policy is unavailable, so AGM will not guess how to recreate it.",
        )
    val key = when (policy) {
        WinlatorApi.ContainerPolicy.SharedDefault ->
            app.winlatorContainerKey?.takeIf { it.isNotBlank() } ?: "agm.default"
        WinlatorApi.ContainerPolicy.Isolated -> null
    }
    val bindingMetadata = app.managedRunnerBindings
        .filterIsInstance<ManagedRunnerBinding.Winlator>()
        .singleOrNull()
        ?.metadata
        .orEmpty()
    return WinlatorPortableRecoveryEligibility.Eligible(
        WinlatorPortableRecoveryCandidate(
            managedGameId = managedGameId,
            gameId = gameId,
            title = app.label,
            storageRoot = canonicalRoot.absolutePath,
            gamePath = requireNotNull(canonicalExecutable.parentFile).absolutePath,
            executablePath = canonicalExecutable.absolutePath,
            containerPolicy = policy.wireValue,
            containerKey = key,
            metadata = bindingMetadata,
            desiredConfigJson = app.winlatorConfigJson,
        ),
    )
}

fun recoveredWinlatorBinding(
    existing: ManagedRunnerBinding.Winlator,
    actual: WinlatorApi.ManagedGame,
): ManagedRunnerBinding.Winlator {
    val live = actual.toInstalledApp()
    return refreshManagedWinlatorBinding(existing, live).copy(
        managedId = actual.id,
        state = actual.state,
        metadata = existing.metadata +
            ("agmBoundAtMs" to System.currentTimeMillis().toString()),
    )
}

suspend fun persistMissingWinlatorRegistration(
    context: Context,
    managedGameId: String,
): ManagedGame? {
    val store = ManagedGameStore(context.applicationContext)
    val game = store.find(managedGameId) ?: return null
    val binding = game.runnerBindings
        .filterIsInstance<ManagedRunnerBinding.Winlator>()
        .singleOrNull() ?: return game
    val missing = markWinlatorRegistrationMissing(binding)
    if (missing == binding) return game
    return store.update(
        game.copy(
            runnerBindings = game.runnerBindings.map {
                if (it.kind == ManagedRunnerKind.Winlator) missing else it
            },
        ),
    )
}

suspend fun persistRecoveredWinlatorRegistration(
    context: Context,
    candidate: WinlatorPortableRecoveryCandidate,
    actual: WinlatorApi.ManagedGame,
): ManagedGame {
    val store = ManagedGameStore(context.applicationContext)
    val game = requireNotNull(store.find(candidate.managedGameId)) {
        "Managed game no longer exists: ${candidate.managedGameId}"
    }
    val existing = game.runnerBindings
        .filterIsInstance<ManagedRunnerBinding.Winlator>()
        .singleOrNull() ?: error("Managed game has no Winlator binding.")
    val recovered = recoveredWinlatorBinding(existing, actual)
    return store.update(
        game.copy(
            runnerBindings = game.runnerBindings.map {
                if (it.kind == ManagedRunnerKind.Winlator) recovered else it
            },
        ),
    )
}

fun buildWinlatorRecoveryConfigSubmission(
    candidate: WinlatorPortableRecoveryCandidate,
    actual: WinlatorApi.ManagedGame,
    schema: WinlatorConfigSchema,
): WinlatorConfigSubmission? {
    val desiredRaw = candidate.desiredConfigJson ?: return null
    val currentRaw = actual.configJson ?: return null
    val freshHash = actual.configSha256 ?: return null
    val desired = runCatching { JSONObject(desiredRaw) }.getOrNull() ?: return null
    val current = runCatching { JSONObject(currentRaw) }.getOrNull() ?: return null
    val set = JSONObject()
    schema.fields.forEach { field ->
        if (!desired.has(field.key)) return@forEach
        val desiredValue = desired.opt(field.key)
        if (!field.acceptsRecoveryValue(desiredValue)) return@forEach
        if (!winlatorRecoveryJsonEquals(current.opt(field.key), desiredValue)) {
            set.put(field.key, desiredValue)
        }
    }
    if (set.length() == 0) return null
    return WinlatorConfigSubmission(
        gameId = candidate.gameId,
        title = candidate.title,
        executableDosPath = null,
        baseConfigSha256 = freshHash,
        setJson = set.toString(),
        beforeJson = currentRaw,
        afterJson = WinlatorConfigJson.applySet(currentRaw, set.toString()),
        source = "registration-recovery",
        retryAfterApply = true,
    )
}

fun isWinlatorRecoveryConfigApplied(
    submission: WinlatorConfigSubmission,
    actual: WinlatorApi.ManagedGame,
): Boolean {
    val current = actual.configJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
    val expected = runCatching { JSONObject(submission.setJson) }.getOrNull() ?: return false
    return expected.keys().asSequence().all { key ->
        current.has(key) && winlatorRecoveryJsonEquals(current.opt(key), expected.opt(key))
    }
}

private val AMBIGUOUS_CREATE_RESULTS = setOf(
    "CANCELLED",
    "NO_RESULT",
    "GAME_ALREADY_EXISTS",
    "GAME_NOT_FOUND",
)

private fun parseWinlatorGame(raw: String): WinlatorApi.ManagedGame? =
    runCatching { WinlatorApi.ManagedGame.parse(JSONObject(raw)) }.getOrNull()

private fun normalizeWinlatorRegistrationPath(path: String): String =
    runCatching { File(path).canonicalPath }
        .getOrDefault(path)
        .replace('\\', '/')
        .trimEnd('/')

private fun normalizeWinlatorDosPath(path: String?): String? =
    path?.trim()?.replace('/', '\\')?.lowercase()

private fun WinlatorConfigField.acceptsRecoveryValue(value: Any?): Boolean {
    if (value == null || value == JSONObject.NULL) return false
    val typeMatches = when (wireType) {
        "boolean" -> value is Boolean
        "integer" -> value is Number
        else -> value is String
    }
    if (!typeMatches) return false
    return options.isEmpty() || options.any {
        winlatorRecoveryJsonEquals(it.value, value)
    }
}

private fun winlatorRecoveryJsonEquals(left: Any?, right: Any?): Boolean =
    when {
        left === right -> true
        left == null || right == null -> false
        left == JSONObject.NULL || right == JSONObject.NULL -> left == right
        left is Number && right is Number ->
            left.toString().toBigDecimalOrNull() == right.toString().toBigDecimalOrNull()
        else -> left == right
    }
