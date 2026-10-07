package com.example.f95updater

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

object WinlatorApi {
    const val PACKAGE = "com.winlator.secure"
    const val ACTIVITY = "com.winlator.api.GameManagerActivity"
    const val ACTION_GAME_EVENT = "com.winlator.secure.event.GAME_EVENT"
    const val PERMISSION_SEND_GAME_EVENTS = "com.winlator.secure.permission.SEND_GAME_EVENTS"

    const val ACTION_CREATE_GAME = "com.winlator.secure.action.CREATE_GAME"
    const val ACTION_CONFIGURE_GAME = "com.winlator.secure.action.CONFIGURE_GAME"
    const val ACTION_RUN_INSTALLER = "com.winlator.secure.action.RUN_INSTALLER"
    const val ACTION_LAUNCH_GAME = "com.winlator.secure.action.LAUNCH_GAME"
    const val ACTION_DELETE_GAME = "com.winlator.secure.action.DELETE_GAME"
    const val ACTION_MOVE_GAME_TO_ISOLATED = "com.winlator.secure.action.MOVE_GAME_TO_ISOLATED"

    private const val EXTRA_SUCCESS = "success"
    private const val EXTRA_ERROR_CODE = "error_code"
    private const val EXTRA_ERROR_MESSAGE = "error_message"
    private const val EXTRA_GAME_ID = "game_id"
    internal const val EXTRA_GAME_PATH = "game_path"
    internal const val EXTRA_EXECUTABLE_PATH = "executable_path"
    private const val EXTRA_CONTAINER_ID = "container_id"
    private const val EXTRA_GAME_JSON = "game_json"
    private const val EXTRA_CREATE_RECONCILED = "create_reconciled"
    private const val EXTRA_LAUNCH_DEFERRED = "launch_deferred"
    private const val EXTRA_CONTAINER_DELETED = "container_deleted"
    private const val EXTRA_CONTAINER_PRESERVED = "container_preserved"
    private const val EXTRA_CONTAINER_REFERENCE_COUNT = "container_reference_count"
    private const val EXTRA_CONFIG_UPDATE_JSON = "config_update_json"
    private const val EXTRA_SETTINGS_UPDATE_JSON = "settings_update_json"

    enum class ContainerPolicy(val wireValue: String) {
        SharedDefault("shared_default"),
        Isolated("isolated");

        companion object {
            fun fromWireValue(value: String?): ContainerPolicy? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    fun createPortable(
        gameId: String,
        title: String,
        gamePath: String,
        executablePath: String,
        metadata: String,
        containerPolicy: ContainerPolicy,
    ): Intent = request(ACTION_CREATE_GAME).apply {
        portableCreateExtras(
            gameId,
            title,
            gamePath,
            executablePath,
            metadata,
            containerPolicy,
        ).forEach { (key, value) ->
            when (value) {
                is Boolean -> putExtra(key, value)
                else -> putExtra(key, value.toString())
            }
        }
    }

    internal fun portableCreateExtras(
        gameId: String,
        title: String,
        gamePath: String,
        executablePath: String,
        metadata: String,
        containerPolicy: ContainerPolicy,
    ): Map<String, Any> {
        require(gameId.isNotBlank()) { "Winlator game_id must not be blank." }
        require(title.isNotBlank()) { "Winlator title must not be blank." }
        require(gamePath.isNotBlank()) { "Winlator game_path must not be blank." }
        require(executablePath.isNotBlank()) { "Winlator executable_path must not be blank." }
        return linkedMapOf(
            EXTRA_GAME_ID to gameId,
            "title" to title,
            "game_path" to gamePath,
            "executable_path" to executablePath,
            "launch_after_create" to false,
            "agm_metadata" to metadata,
            "container_policy" to containerPolicy.wireValue,
        )
    }

    fun createInstaller(
        gameId: String,
        title: String,
        installerPath: String,
        executableDosPath: String?,
        metadata: String,
        async: Boolean,
        containerPolicy: ContainerPolicy,
    ): Intent = request(ACTION_CREATE_GAME).apply {
        putExtra(EXTRA_GAME_ID, gameId)
        putExtra("title", title)
        putExtra("installer_path", installerPath)
        executableDosPath?.takeIf { it.isNotBlank() }?.let {
            putExtra("executable_dos_path", it)
        }
        putExtra("launch_after_create", false)
        putExtra("async_installer", async)
        putExtra("agm_metadata", metadata)
        putExtra("container_policy", containerPolicy.wireValue)
    }

    fun configureExecutable(gameId: String, executableDosPath: String): Intent =
        request(ACTION_CONFIGURE_GAME).apply {
            putExtra(EXTRA_GAME_ID, gameId)
            putExtra("executable_dos_path", executableDosPath)
        }

    /**
     * Re-points an existing portable game at a new shared-storage location, keeping its Winlator
     * game id and container. Winlator Secure natively accepts `game_path` + `executable_path` on
     * [ACTION_CONFIGURE_GAME]; callers must verify the applied paths afterwards.
     */
    fun configurePortableLocation(
        gameId: String,
        gamePath: String,
        executablePath: String,
    ): Intent = request(ACTION_CONFIGURE_GAME).apply {
        putExtra(EXTRA_GAME_ID, gameId)
        portableLocationExtras(gamePath, executablePath).forEach { (key, value) -> putExtra(key, value) }
    }

    internal fun portableLocationExtras(
        gamePath: String,
        executablePath: String,
    ): Map<String, String> {
        require(gamePath.isNotBlank()) { "Winlator game_path must not be blank." }
        require(executablePath.isNotBlank()) { "Winlator executable_path must not be blank." }
        return linkedMapOf(
            EXTRA_GAME_PATH to gamePath,
            EXTRA_EXECUTABLE_PATH to executablePath,
        )
    }

    fun configureGame(
        gameId: String,
        executableDosPath: String?,
        configUpdateJson: String?,
    ): Intent = request(ACTION_CONFIGURE_GAME).apply {
        putExtra(EXTRA_GAME_ID, gameId)
        executableDosPath?.let { putExtra("executable_dos_path", it) }
        configUpdateJson?.let { putExtra(EXTRA_CONFIG_UPDATE_JSON, it) }
    }

    /**
     * Applies a per-game *settings* update (e.g. localization/runtimeLocale). Settings live on a
     * separate surface from runtime/container config; Winlator validates [settingsUpdateJson]
     * against its settings schema and rejects config-only fields sent here (and vice versa).
     */
    fun configureGameSettings(
        gameId: String,
        settingsUpdateJson: String,
    ): Intent = request(ACTION_CONFIGURE_GAME).apply {
        putExtra(EXTRA_GAME_ID, gameId)
        putExtra(EXTRA_SETTINGS_UPDATE_JSON, settingsUpdateJson)
    }

    fun runInstaller(gameId: String, installerPath: String, async: Boolean): Intent =
        request(ACTION_RUN_INSTALLER).apply {
            putExtra(EXTRA_GAME_ID, gameId)
            putExtra("installer_path", installerPath)
            putExtra("async_installer", async)
        }

    fun launch(gameId: String): Intent =
        request(ACTION_LAUNCH_GAME).putExtra(EXTRA_GAME_ID, gameId)

    fun delete(gameId: String): Intent =
        request(ACTION_DELETE_GAME).putExtra(EXTRA_GAME_ID, gameId)

    fun moveToIsolated(gameId: String): Intent =
        request(ACTION_MOVE_GAME_TO_ISOLATED).putExtra(EXTRA_GAME_ID, gameId)

    private fun request(action: String): Intent =
        Intent(action).setClassName(PACKAGE, ACTIVITY)

    sealed interface OperationResult {
        data class Success(
            val gameId: String?,
            val containerId: Int?,
            val gameJson: String?,
            val createReconciled: Boolean,
            val launchDeferred: Boolean,
            val containerDeleted: Boolean,
            val containerPreserved: Boolean,
            val containerReferenceCount: Int?,
        ) : OperationResult

        data class Failure(
            val code: String,
            val message: String,
            val gameJson: String? = null,
        ) : OperationResult
    }

    fun parseResult(resultCode: Int, data: Intent?): OperationResult {
        if (data == null) return OperationResult.Failure("NO_RESULT", "Winlator returned no result data.")
        val success = data.getBooleanExtra(EXTRA_SUCCESS, resultCode == Activity.RESULT_OK)
        return operationResult(
            resultCode = resultCode,
            success = success,
            errorCode = data.getStringExtra(EXTRA_ERROR_CODE),
            errorMessage = data.getStringExtra(EXTRA_ERROR_MESSAGE),
            gameId = data.getStringExtra(EXTRA_GAME_ID),
            containerId = data.getIntExtra(EXTRA_CONTAINER_ID, -1).takeIf { it >= 0 },
            gameJson = data.getStringExtra(EXTRA_GAME_JSON),
            createReconciled = data.getBooleanExtra(EXTRA_CREATE_RECONCILED, false),
            launchDeferred = data.getBooleanExtra(EXTRA_LAUNCH_DEFERRED, false),
            containerDeleted = data.getBooleanExtra(EXTRA_CONTAINER_DELETED, false),
            containerPreserved = data.getBooleanExtra(EXTRA_CONTAINER_PRESERVED, false),
            containerReferenceCount =
                data.getIntExtra(EXTRA_CONTAINER_REFERENCE_COUNT, -1).takeIf { it >= 0 },
        )
    }

    internal fun operationResult(
        resultCode: Int,
        success: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
        gameId: String? = null,
        containerId: Int? = null,
        gameJson: String? = null,
        createReconciled: Boolean = false,
        launchDeferred: Boolean = false,
        containerDeleted: Boolean = false,
        containerPreserved: Boolean = false,
        containerReferenceCount: Int? = null,
    ): OperationResult {
        if (!success || resultCode != Activity.RESULT_OK) {
            return OperationResult.Failure(
                errorCode ?: "CANCELLED",
                errorMessage ?: "Winlator cancelled or rejected the request.",
                gameJson,
            )
        }
        return OperationResult.Success(
            gameId = gameId,
            containerId = containerId,
            gameJson = gameJson,
            createReconciled = createReconciled,
            launchDeferred = launchDeferred,
            containerDeleted = containerDeleted,
            containerPreserved = containerPreserved,
            containerReferenceCount = containerReferenceCount,
        )
    }

    data class Capabilities(
        val apiVersion: Int,
        val supportsPortableGames: Boolean,
        val supportsWindowsInstallers: Boolean,
        val asyncInstallers: Boolean,
        val installerProgress: Boolean,
        val sameProfilePaths: Boolean,
        val safUri: Boolean,
        val sharedContainers: Boolean,
        val sharedDefaultContainer: Boolean,
        val defaultContainerPolicy: String?,
        val defaultSharedContainerKey: String?,
        val moveGameToIsolated: Boolean,
        val referenceSafeContainerDeletion: Boolean,
        val managedGameConfiguration: Boolean,
        val gameConfigSchemaVersion: Int,
        val configSchemaPath: String?,
        val configConflictDetection: Boolean,
        val configUpdateExtra: String?,
        val managedGameSettings: Boolean,
        val gameSettingsSchemaVersion: Int,
        val settingsSchemaPath: String?,
        val settingsConflictDetection: Boolean,
        val settingsUpdateExtra: String?,
        val managedDiagnostics: Boolean,
        val diagnosticsSchemaVersion: Int,
        val diagnosticHistory: Boolean,
        val diagnosticHistoryPerGame: Int,
        val gameEventDiagnosticRefs: Boolean,
        val diagnosticsPath: String?,
        val perGameControlsProfile: Boolean,
        val configSuggestions: Boolean,
        val configSuggestionsPath: String?,
        val runnerRecommendation: Boolean,
    ) {
        companion object {
            fun parse(json: String): Capabilities {
                val value = JSONObject(json)
                return Capabilities(
                    apiVersion = value.optInt("apiVersion", 0),
                    supportsPortableGames = value.optBoolean("supportsPortableGames"),
                    supportsWindowsInstallers = value.optBoolean("supportsWindowsInstallers"),
                    asyncInstallers = value.optBoolean("asyncInstallers"),
                    installerProgress = value.optBoolean("installerProgress"),
                    sameProfilePaths = value.optBoolean("sameProfilePaths"),
                    safUri = value.optBoolean("safUri"),
                    sharedContainers = value.optBoolean("sharedContainers"),
                    sharedDefaultContainer = value.optBoolean("sharedDefaultContainer"),
                    defaultContainerPolicy = value.optString("defaultContainerPolicy").takeIf { it.isNotBlank() },
                    defaultSharedContainerKey = value.optString("defaultSharedContainerKey").takeIf { it.isNotBlank() },
                    moveGameToIsolated = value.optBoolean("moveGameToIsolated"),
                    referenceSafeContainerDeletion = value.optBoolean("referenceSafeContainerDeletion"),
                    managedGameConfiguration = value.optBoolean("managedGameConfiguration"),
                    gameConfigSchemaVersion = value.optInt("gameConfigSchemaVersion", 0),
                    configSchemaPath = value.optString("configSchemaPath").takeIf { it.isNotBlank() },
                    configConflictDetection = value.optBoolean("configConflictDetection"),
                    configUpdateExtra = value.optString("configUpdateExtra").takeIf { it.isNotBlank() },
                    managedGameSettings = value.optBoolean("managedGameSettings"),
                    gameSettingsSchemaVersion = value.optInt("gameSettingsSchemaVersion", 0),
                    settingsSchemaPath = value.optString("settingsSchemaPath").takeIf { it.isNotBlank() },
                    settingsConflictDetection = value.optBoolean("settingsConflictDetection"),
                    settingsUpdateExtra = value.optString("settingsUpdateExtra").takeIf { it.isNotBlank() },
                    managedDiagnostics = value.optBoolean("managedDiagnostics"),
                    diagnosticsSchemaVersion = value.optInt("diagnosticsSchemaVersion", 0),
                    diagnosticHistory = value.optBoolean("diagnosticHistory"),
                    diagnosticHistoryPerGame = value.optInt("diagnosticHistoryPerGame", 0),
                    gameEventDiagnosticRefs = value.optBoolean("gameEventDiagnosticRefs"),
                    diagnosticsPath = value.optString("diagnosticsPath").takeIf { it.isNotBlank() },
                    perGameControlsProfile = value.optBoolean("perGameControlsProfile"),
                    configSuggestions = value.optBoolean("configSuggestions"),
                    configSuggestionsPath = value.optString("configSuggestionsPath").takeIf { it.isNotBlank() },
                    runnerRecommendation = value.optBoolean("runnerRecommendation"),
                )
            }
        }
    }

    data class ManagedGame(
        val id: String,
        val title: String,
        val containerId: Int?,
        val gamePath: String?,
        val executablePath: String?,
        val executableDosPath: String?,
        val state: String,
        val containerPolicy: ContainerPolicy?,
        val containerShared: Boolean,
        val containerKey: String?,
        val containerReferenceCount: Int?,
        val containerAllocatedSizeBytes: Long?,
        val configJson: String?,
        val configSha256: String?,
        val effectiveWinVersion: String?,
        val winVersionSource: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val metadata: String?,
    ) {
        fun toInstalledApp(): InstalledApp {
            val metadataObject = metadata?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
            val version = metadataObject?.optString("version").orEmpty()
            val sourceDirectory = metadataObject
                ?.optString("sourcePath")
                ?.replace('\\', '/')
                ?.substringBeforeLast('/', "")
                ?.takeIf { it.isNotBlank() }
            val managedStoragePath = gamePath ?: sourceDirectory
            return InstalledApp(
                packageName = "winlator:$id",
                label = title,
                versionName = version,
                versionCode = 0L,
                firstInstallTime = createdAt,
                lastUpdateTime = updatedAt,
                source = AppSource.Winlator,
                storagePath = managedStoragePath,
                storageFolderName = managedStoragePath?.substringAfterLast('/'),
                readmeTitle = managedStoragePath?.let { readGameReadmeTitleNear(java.io.File(it)) },
                winlatorGameId = id,
                winlatorContainerId = containerId,
                winlatorState = state,
                winlatorExecutablePath = executablePath,
                winlatorExecutableDosPath = executableDosPath,
                winlatorContainerPolicy = containerPolicy?.wireValue,
                winlatorContainerShared = containerShared,
                winlatorContainerKey = containerKey,
                winlatorContainerReferenceCount = containerReferenceCount,
                winlatorContainerAllocatedSizeBytes = containerAllocatedSizeBytes,
                winlatorConfigJson = configJson,
                winlatorConfigSha256 = configSha256,
                winlatorEffectiveWinVersion = effectiveWinVersion,
                winlatorWinVersionSource = winVersionSource,
            )
        }

        companion object {
            fun parseList(json: String): List<ManagedGame> {
                val array = JSONArray(json)
                return buildList {
                    for (index in 0 until array.length()) {
                        add(parse(array.getJSONObject(index)))
                    }
                }
            }

            fun parse(value: JSONObject): ManagedGame = ManagedGame(
                id = value.getString("id"),
                title = value.optString("title").ifBlank { value.getString("id") },
                containerId = value.optInt("containerId", -1).takeIf { it >= 0 },
                gamePath = value.optString("gamePath").takeIf { it.isNotBlank() },
                executablePath = value.optString("executablePath").takeIf { it.isNotBlank() },
                executableDosPath = value.optString("executableDosPath").takeIf { it.isNotBlank() },
                state = value.optString("state", "setup_required"),
                containerPolicy = ContainerPolicy.fromWireValue(
                    value.optString("containerPolicy").takeIf { it.isNotBlank() }
                ),
                containerShared = value.optBoolean("containerShared"),
                containerKey = value.optString("containerKey").takeIf { it.isNotBlank() },
                containerReferenceCount = value.optInt("containerReferenceCount", -1).takeIf { it >= 0 },
                containerAllocatedSizeBytes = value.optLong("containerAllocatedSizeBytes", -1L).takeIf { it >= 0L },
                configJson = value.optJSONObject("configJson")?.toString()
                    ?: value.optJSONObject("containerConfig")?.toString(),
                configSha256 = value.optString("configSha256").takeIf { it.isNotBlank() },
                effectiveWinVersion = value.optString("effectiveWinVersion").takeIf { it.isNotBlank() },
                winVersionSource = value.optString("winVersionSource").takeIf { it.isNotBlank() },
                createdAt = value.optLong("createdAt"),
                updatedAt = value.optLong("updatedAt"),
                metadata = value.optString("agm_metadata").takeIf { it.isNotBlank() },
            )
        }
    }

    data class GameEvent(
        val type: String,
        val gameId: String?,
        val success: Boolean,
        val percent: Int?,
        val stage: String?,
        val errorCode: String?,
        val errorMessage: String?,
        val reportId: String?,
        val outcome: String?,
        val phase: String?,
        val category: String?,
        val confidence: String?,
        val configHealth: String?,
        val durationMillis: Long?,
        val exitCode: Int?,
        val signal: Int?,
        val runtimeReached: Boolean?,
        val appliedConfigSha256: String?,
    ) {
        companion object {
            fun from(intent: Intent): GameEvent = GameEvent(
                type = intent.getStringExtra("event_type") ?: "unknown",
                gameId = intent.getStringExtra(EXTRA_GAME_ID),
                success = intent.getBooleanExtra(EXTRA_SUCCESS, false),
                percent = intent.getIntExtra("percent", -1).takeIf { it >= 0 },
                stage = intent.getStringExtra("stage"),
                errorCode = intent.getStringExtra(EXTRA_ERROR_CODE),
                errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE),
                reportId = intent.getStringExtra("report_id"),
                outcome = intent.getStringExtra("outcome"),
                phase = intent.getStringExtra("phase"),
                category = intent.getStringExtra("category"),
                confidence = intent.getStringExtra("confidence"),
                configHealth = intent.getStringExtra("config_health"),
                durationMillis = intent.getLongExtra("duration_millis", -1L).takeIf { it >= 0L },
                exitCode = intent.getIntExtra("exit_code", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                signal = intent.getIntExtra("signal", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                runtimeReached = intent.getBooleanExtra("runtime_reached", false)
                    .takeIf { intent.hasExtra("runtime_reached") },
                appliedConfigSha256 = intent.getStringExtra("applied_config_sha256"),
            )
        }
    }
}

object WinlatorEventBus {
    private val mutableEvents = MutableSharedFlow<WinlatorApi.GameEvent>(extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()

    fun publish(event: WinlatorApi.GameEvent) {
        mutableEvents.tryEmit(event)
    }
}

class WinlatorGameEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WinlatorApi.ACTION_GAME_EVENT) return
        val event = WinlatorApi.GameEvent.from(intent)
        AppLog.i(
            "WinlatorEvent",
            "type=${event.type} game=${event.gameId} success=${event.success} " +
                "runtimeReached=${event.runtimeReached} outcome=${event.outcome} phase=${event.phase} " +
                "reportId=${event.reportId} exit=${event.exitCode} signal=${event.signal} " +
                "stage=${event.stage} percent=${event.percent} error=${event.errorCode}:${event.errorMessage}",
        )
        WinlatorEventBus.publish(event)
    }
}
