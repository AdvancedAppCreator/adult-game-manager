package com.example.f95updater

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val WINLATOR_LOCALES: List<Pair<String, String>> = listOf(
    "Not set (Winlator default)" to "system",
    "English" to "en_US.UTF-8",
    "Portuguese (Brazil)" to "pt_BR.UTF-8",
    "Russian" to "ru_RU.UTF-8",
    "Japanese" to "ja_JP.UTF-8",
    "Chinese (Simplified)" to "zh_CN.UTF-8",
    "Chinese (Traditional)" to "zh_TW.UTF-8",
    "Korean" to "ko_KR.UTF-8",
)

private data class WinlatorManagementData(
    val capabilities: WinlatorApi.Capabilities,
    val game: WinlatorApi.ManagedGame,
    val schema: WinlatorConfigSchema?,
    val settings: WinlatorGameSettings?,
    val settingsSchema: WinlatorSettingsSchema?,
    val reports: List<WinlatorDiagnosticReport>,
    val history: List<WinlatorConfigChange>,
    val lastGood: Pair<String, String>?,
    val executables: List<WinlatorExecutableOption> = emptyList(),
)

/** A selectable Windows executable discovered inside a game's folder. [label] is the path relative
 *  to the game folder; [dosPath] is the Windows path AGM sends to Winlator via configureGame. */
private data class WinlatorExecutableOption(
    val label: String,
    val dosPath: String,
)

private sealed interface WinlatorManagementLoad {
    data object Loading : WinlatorManagementLoad
    data class Ready(val data: WinlatorManagementData) : WinlatorManagementLoad
    data class Failed(val message: String) : WinlatorManagementLoad
}

private data class PendingConfigConfirmation(
    val submissions: List<WinlatorConfigSubmission>,
    val changes: List<String>,
)

private enum class WinlatorSettingsTab(val title: String) {
    General("General"),
    Translator("Translator"),
    Runtime("Runtime"),
    Diagnostics("Diagnostics"),
}

@Composable
fun WinlatorManagementDialog(
    app: InstalledApp,
    onDismiss: () -> Unit,
    onSubmit: (List<WinlatorConfigSubmission>) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize().padding(12.dp),
        ) {
            WinlatorSettingsPane(
                app = app,
                onClose = onDismiss,
                onSubmit = {
                    onSubmit(it)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Embeddable Winlator settings surface (async load + [WinlatorManagementContent] with its
 * General/Translator/Runtime/Diagnostics tabs). Hosted directly by [WinlatorManagementDialog]
 * and, as the "Winlator" tab, by the general [GameSettingsDialog].
 */
@Composable
internal fun WinlatorSettingsPane(
    app: InstalledApp,
    onClose: () -> Unit,
    onSubmit: (List<WinlatorConfigSubmission>) -> Unit,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    var refreshKey by remember(app.packageName) { mutableStateOf(0) }
    var load by remember(app.packageName, refreshKey) { mutableStateOf<WinlatorManagementLoad>(WinlatorManagementLoad.Loading) }

    LaunchedEffect(app.packageName, refreshKey) {
        load = loadWinlatorManagement(context.applicationContext, app)
    }

    when (val state = load) {
        WinlatorManagementLoad.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is WinlatorManagementLoad.Failed -> Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Winlator settings", style = MaterialTheme.typography.headlineSmall)
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { refreshKey++ }) { Text("Retry") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        is WinlatorManagementLoad.Ready -> WinlatorManagementContent(
            app = app,
            data = state.data,
            onClose = onClose,
            onRefresh = { refreshKey++ },
            onSubmit = onSubmit,
            embedded = embedded,
        )
    }
}

@Composable
private fun WinlatorManagementContent(
    app: InstalledApp,
    data: WinlatorManagementData,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSubmit: (List<WinlatorConfigSubmission>) -> Unit,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val game = data.game
    val originalConfig = game.configJson ?: "{}"
    val currentLocale = data.settings?.runtimeLocale ?: WinlatorGameSettings.DEFAULT_LOCALE
    val values = remember(game.id, game.configSha256) {
        mutableStateMapOf<String, String>().apply {
            data.schema?.fields?.forEach { field ->
                put(field.key, WinlatorConfigJson.inputValue(JSONObject(originalConfig).opt(field.key)))
            }
        }
    }
    val dirty = remember(game.id, game.configSha256) { mutableStateMapOf<String, Boolean>() }
    // Auto-translator (settings-surface `ocr` namespace) editable state.
    val ocrFields = data.settingsSchema?.namespace("ocr")?.fields.orEmpty()
    val currentOcr = remember(game.id, data.settings?.settingsSha256) {
        data.settings?.settingsJson
            ?.let { runCatching { JSONObject(it).optJSONObject("ocr") }.getOrNull() }
    }
    val ocrValues = remember(game.id, data.settings?.settingsSha256) {
        mutableStateMapOf<String, String>().apply {
            ocrFields.forEach { field ->
                put(field.key, WinlatorConfigJson.inputValue(currentOcr?.opt(field.key) ?: field.defaultValue))
            }
        }
    }
    val ocrDirty = remember(game.id, data.settings?.settingsSha256) { mutableStateMapOf<String, Boolean>() }
    // Per-game diagnostics (settings-surface `diagnostics` namespace, e.g. stallTroubleshooter).
    val diagFields = data.settingsSchema?.namespace("diagnostics")?.fields.orEmpty()
    val currentDiag = remember(game.id, data.settings?.settingsSha256) {
        data.settings?.settingsJson
            ?.let { runCatching { JSONObject(it).optJSONObject("diagnostics") }.getOrNull() }
    }
    val diagValues = remember(game.id, data.settings?.settingsSha256) {
        mutableStateMapOf<String, String>().apply {
            diagFields.forEach { field ->
                put(field.key, WinlatorConfigJson.inputValue(currentDiag?.opt(field.key) ?: field.defaultValue))
            }
        }
    }
    val diagDirty = remember(game.id, data.settings?.settingsSha256) { mutableStateMapOf<String, Boolean>() }
    // Per-game on-screen controller overlay (settings-surface `input` namespace; controlsProfileId
    // integer enum, 0 = None). Rendered on the General tab via the generic field editor.
    val inputFields = data.settingsSchema?.namespace("input")?.fields.orEmpty()
    val currentInput = remember(game.id, data.settings?.settingsSha256) {
        data.settings?.settingsJson
            ?.let { runCatching { JSONObject(it).optJSONObject("input") }.getOrNull() }
    }
    val inputValues = remember(game.id, data.settings?.settingsSha256) {
        mutableStateMapOf<String, String>().apply {
            inputFields.forEach { field ->
                put(field.key, WinlatorConfigJson.inputValue(currentInput?.opt(field.key) ?: field.defaultValue))
            }
        }
    }
    val inputDirty = remember(game.id, data.settings?.settingsSha256) { mutableStateMapOf<String, Boolean>() }
    var dosPath by remember(game.id, game.updatedAt) { mutableStateOf(game.executableDosPath.orEmpty()) }
    var dosDirty by remember(game.id, game.updatedAt) { mutableStateOf(false) }
    // Launch locale (settings surface: localization.runtimeLocale -> Wine LANG/LC_ALL). Staged
    // like the other settings so the shared Review & apply footer persists it too.
    var localeTag by remember(game.id, data.settings?.settingsSha256) { mutableStateOf(currentLocale) }
    var localeDirty by remember(game.id, data.settings?.settingsSha256) { mutableStateOf(false) }
    var warning by remember(game.id, game.configSha256) { mutableStateOf<String?>(null) }
    var pending by remember(game.id, game.configSha256) { mutableStateOf<PendingConfigConfirmation?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Winlator-computed suggested configuration (capability `configSuggestions`), fetched on demand.
    var suggested by remember(game.id) { mutableStateOf<WinlatorSuggestedConfig?>(null) }
    var suggestedError by remember(game.id) { mutableStateOf<String?>(null) }
    var suggestedLoading by remember { mutableStateOf(false) }
    // Winlator's advisory runner hint (capability `runnerRecommendation`), auto-fetched separately
    // from `suggested` so it can render inline without opening the Recommended-settings dialog.
    val joiPlayInstalled = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("cyou.joiplay.joiplay", 0); true
        }.getOrDefault(false)
    }
    var runnerRec by remember(game.id) { mutableStateOf<RunnerRecommendation?>(null) }
    LaunchedEffect(game.id, data.capabilities.runnerRecommendation) {
        if (!data.capabilities.runnerRecommendation) return@LaunchedEffect
        when (val read = WinlatorClient.getSuggestedConfig(context.applicationContext, game.id)) {
            is WinlatorClient.Read.Ok ->
                runnerRec = runCatching { WinlatorSuggestedConfig.parse(read.payload).runnerRecommendation }
                    .getOrNull()
            else -> {}
        }
    }

    // Confirm-and-apply a pre-built config-surface set (diagnostic suggestions and last-known-good /
    // history restores). Free-form config editing and the executable path go through prepareAll().
    fun prepareRequested(
        source: String,
        requestedSet: JSONObject,
        requestedBaseHash: String?,
        reportId: String? = null,
        suggestionId: String? = null,
        retry: Boolean = false,
    ) {
        scope.launch {
            warning = null
            busy = true
            val latest = fetchManagedGame(context.applicationContext, game.id)
            if (latest == null || latest.configJson == null || latest.configSha256 == null) {
                warning = "Could not refresh the current Winlator configuration."
                busy = false
                return@launch
            }
            if (requestedBaseHash != null && !latest.configSha256.equals(requestedBaseHash, ignoreCase = true)) {
                warning = "The configuration changed in Winlator since this was prepared. Review the updated diff before applying."
            }
            if (requestedSet.length() == 0) {
                warning = "No settings changed."
                busy = false
                return@launch
            }
            val after = WinlatorConfigJson.applySet(latest.configJson, requestedSet.toString())
            val changes = WinlatorConfigJson.describeSet(latest.configJson, requestedSet.toString())
            pending = PendingConfigConfirmation(
                submissions = listOf(WinlatorConfigSubmission(
                    gameId = game.id,
                    title = app.label,
                    executableDosPath = null,
                    baseConfigSha256 = latest.configSha256,
                    setJson = requestedSet.toString(),
                    beforeJson = latest.configJson,
                    afterJson = after,
                    source = source,
                    reportId = reportId,
                    suggestionId = suggestionId,
                    retryAfterApply = retry,
                )),
                changes = changes,
            )
            busy = false
        }
    }

    fun ocrDisplay(field: WinlatorConfigField, value: Any?): String {
        val raw = WinlatorConfigJson.inputValue(value)
        if (field.wireType == "boolean") return if (raw.toBooleanStrictOrNull() == true) "Enabled" else "Disabled"
        return field.options.firstOrNull { it.value.toString() == raw }?.label ?: raw
    }

    // Collect the changed fields of one settings namespace (e.g. ocr, diagnostics) into a partial
    // set, appending human-readable diffs. Returns null and sets `warning` on a parse failure.
    fun collectNamespaceSet(
        fields: List<WinlatorConfigField>,
        dirtyMap: Map<String, Boolean>,
        valuesMap: Map<String, String>,
        latestNamespace: JSONObject?,
        changes: MutableList<String>,
    ): JSONObject? {
        val out = JSONObject()
        for (field in fields) {
            if (dirtyMap[field.key] != true) continue
            val parsed = runCatching { WinlatorConfigJson.parseInput(field, valuesMap[field.key].orEmpty()) }
                .getOrElse { warning = "${fieldLabel(field.key)}: ${it.message}"; return null }
            val before = latestNamespace?.opt(field.key)
            if (WinlatorConfigJson.inputValue(before) != WinlatorConfigJson.inputValue(parsed)) {
                out.put(field.key, parsed)
                changes += "${fieldLabel(field.key)}: ${ocrDisplay(field, before)} -> ${ocrDisplay(field, parsed)}"
            }
        }
        return out
    }

    fun prepareAll() {
        scope.launch {
            warning = null
            busy = true
            val submissions = mutableListOf<WinlatorConfigSubmission>()
            val allChanges = mutableListOf<String>()
            var advisory: String? = null

            // ---- Config surface: executable path + runtime schema fields (one round-trip). ----
            val configDirty = dosDirty || dirty.values.any { it }
            if (configDirty) {
                val latest = fetchManagedGame(context.applicationContext, game.id)
                if (latest == null || latest.configJson == null || latest.configSha256 == null) {
                    warning = "Could not refresh the current Winlator configuration."
                    busy = false
                    return@launch
                }
                if (!latest.configSha256.equals(game.configSha256, ignoreCase = true)) {
                    advisory = "The configuration changed in Winlator. Review the updated diff before applying."
                }
                val parsed = buildMap<String, Any> {
                    data.schema?.fields?.forEach { field ->
                        if (dirty[field.key] == true) {
                            runCatching { put(field.key, WinlatorConfigJson.parseInput(field, values[field.key].orEmpty())) }
                                .onFailure {
                                    warning = "${field.key}: ${it.message}"
                                    busy = false
                                    return@launch
                                }
                        }
                    }
                }
                val set = WinlatorConfigJson.changedSet(latest.configJson, parsed)
                val executable = dosPath.trim().takeIf {
                    dosDirty && it.matches(Regex("""(?i)^[a-z]:[\\/].+"""))
                }
                if (dosDirty && executable == null) {
                    warning = "The Windows executable path must start with a drive letter, for example C:\\Game\\game.exe."
                    busy = false
                    return@launch
                }
                if (set.length() > 0 || executable != null) {
                    val after = WinlatorConfigJson.applySet(latest.configJson, set.toString())
                    allChanges += WinlatorConfigJson.describeSet(latest.configJson, set.toString())
                    executable?.let { allChanges += "executableDosPath: ${latest.executableDosPath.orEmpty()} -> $it" }
                    submissions += WinlatorConfigSubmission(
                        gameId = game.id,
                        title = app.label,
                        executableDosPath = executable,
                        baseConfigSha256 = latest.configSha256,
                        setJson = set.toString(),
                        beforeJson = latest.configJson,
                        afterJson = after,
                        source = "manual",
                    )
                }
            }

            // ---- Settings surface: localization + ocr + diagnostics, deep-merged (one round-trip). ----
            val settingsDirty = localeDirty || ocrDirty.values.any { it } || diagDirty.values.any { it } ||
                inputDirty.values.any { it }
            if (settingsDirty) {
                val latest = fetchGameSettings(context.applicationContext, game.id)
                if (latest == null) {
                    warning = "Could not refresh the current Winlator settings."
                    busy = false
                    return@launch
                }
                val set = JSONObject()
                // Force-send the launch locale whenever the user explicitly picked one and hit Apply
                // (localeDirty), even if it equals the last-read value. The dirty flag is only set by
                // the user's dropdown selection, so this is the explicit-apply path. Sending it under
                // baseSettingsSha256 is harmless if redundant, and removes the silent failure where
                // the UI shows a locale selected but the game's saved runtimeLocale stayed "system".
                if (localeDirty) {
                    set.put("localization", JSONObject().put("runtimeLocale", localeTag))
                    val fromLabel = WINLATOR_LOCALES.firstOrNull { it.second == latest.runtimeLocale }?.first
                        ?: latest.runtimeLocale
                    val toLabel = WINLATOR_LOCALES.firstOrNull { it.second == localeTag }?.first ?: localeTag
                    if (localeTag != latest.runtimeLocale) {
                        allChanges += "Launch locale: $fromLabel -> $toLabel"
                    } else {
                        allChanges += "Launch locale: $toLabel (re-applied)"
                    }
                }
                val latestOcr = runCatching { JSONObject(latest.settingsJson).optJSONObject("ocr") }.getOrNull()
                val ocrSet = collectNamespaceSet(ocrFields, ocrDirty, ocrValues, latestOcr, allChanges)
                    ?: run { busy = false; return@launch }
                if (ocrSet.length() > 0) set.put("ocr", ocrSet)
                val latestDiag = runCatching { JSONObject(latest.settingsJson).optJSONObject("diagnostics") }.getOrNull()
                val diagSet = collectNamespaceSet(diagFields, diagDirty, diagValues, latestDiag, allChanges)
                    ?: run { busy = false; return@launch }
                if (diagSet.length() > 0) set.put("diagnostics", diagSet)
                val latestInput = runCatching { JSONObject(latest.settingsJson).optJSONObject("input") }.getOrNull()
                val inputSet = collectNamespaceSet(inputFields, inputDirty, inputValues, latestInput, allChanges)
                    ?: run { busy = false; return@launch }
                if (inputSet.length() > 0) set.put("input", inputSet)
                if (set.length() > 0) {
                    val after = WinlatorConfigJson.applySet(latest.settingsJson, set.toString())
                    submissions += WinlatorConfigSubmission(
                        gameId = game.id,
                        title = app.label,
                        executableDosPath = null,
                        baseConfigSha256 = latest.settingsSha256,
                        setJson = set.toString(),
                        beforeJson = latest.settingsJson,
                        afterJson = after,
                        source = "settings",
                        surface = WinlatorConfigSurface.Settings,
                    )
                }
            }

            if (submissions.isEmpty()) {
                warning = "No settings changed."
                busy = false
                return@launch
            }
            warning = advisory
            pending = PendingConfigConfirmation(submissions, allChanges)
            busy = false
        }
    }

    fun fetchSuggested() {
        scope.launch {
            suggestedError = null
            suggestedLoading = true
            when (val read = WinlatorClient.getSuggestedConfig(context.applicationContext, game.id)) {
                is WinlatorClient.Read.Ok ->
                    suggested = runCatching { WinlatorSuggestedConfig.parse(read.payload) }
                        .getOrElse { suggestedError = "Could not read Winlator's suggestion: ${it.message}"; null }
                is WinlatorClient.Read.Err -> suggestedError = "${read.code}: ${read.message}"
                is WinlatorClient.Read.Unavailable -> suggestedError = read.reason
            }
            suggestedLoading = false
        }
    }

    // Apply the suggested runtime config through the CONFIG surface (config_update_json): keep only
    // schema-known keys that differ from the current config, then route through the standard
    // confirm-and-apply path (fresh base hashes, user review). Splits across surfaces: runtime config
    // -> config surface; localization.runtimeLocale -> settings surface.
    fun applySuggested(s: WinlatorSuggestedConfig) {
        suggested = null
        scope.launch {
            warning = null
            busy = true
            val submissions = mutableListOf<WinlatorConfigSubmission>()
            val allChanges = mutableListOf<String>()

            // ---- Config surface. ----
            if (s.suggestedConfig.length() > 0) {
                val latest = fetchManagedGame(context.applicationContext, game.id)
                if (latest?.configJson == null || latest.configSha256 == null) {
                    warning = "Could not refresh the current Winlator configuration."
                    busy = false
                    return@launch
                }
                val schemaKeys = data.schema?.fields?.map { it.key }?.toSet()
                    ?: s.suggestedConfig.keys().asSequence().toSet()
                val set = WinlatorRecommendedConfig.recommendedSet(JSONObject(latest.configJson), schemaKeys, s.suggestedConfig)
                if (set.length() > 0) {
                    allChanges += WinlatorConfigJson.describeSet(latest.configJson, set.toString())
                    submissions += WinlatorConfigSubmission(
                        gameId = game.id,
                        title = app.label,
                        executableDosPath = null,
                        baseConfigSha256 = latest.configSha256,
                        setJson = set.toString(),
                        beforeJson = latest.configJson,
                        afterJson = WinlatorConfigJson.applySet(latest.configJson, set.toString()),
                        source = "recommended",
                        surface = WinlatorConfigSurface.Config,
                    )
                }
            }

            // ---- Settings surface (localization.runtimeLocale). ----
            val locale = s.suggestedRuntimeLocale
            if (locale != null && WinlatorRecommendedConfig.isAcceptedRuntimeLocale(locale) && data.settings != null) {
                val latest = fetchGameSettings(context.applicationContext, game.id)
                if (latest != null && locale != latest.runtimeLocale) {
                    val set = JSONObject().put("localization", JSONObject().put("runtimeLocale", locale))
                    val fromLabel = WINLATOR_LOCALES.firstOrNull { it.second == latest.runtimeLocale }?.first ?: latest.runtimeLocale
                    val toLabel = WINLATOR_LOCALES.firstOrNull { it.second == locale }?.first ?: locale
                    allChanges += "Launch locale: $fromLabel -> $toLabel"
                    submissions += WinlatorConfigSubmission(
                        gameId = game.id,
                        title = app.label,
                        executableDosPath = null,
                        baseConfigSha256 = latest.settingsSha256,
                        setJson = set.toString(),
                        beforeJson = latest.settingsJson,
                        afterJson = WinlatorConfigJson.applySet(latest.settingsJson, set.toString()),
                        source = "recommended",
                        surface = WinlatorConfigSurface.Settings,
                    )
                }
            }

            if (submissions.isEmpty()) {
                warning = "Recommended settings already match this game's configuration."
                busy = false
                return@launch
            }
            pending = PendingConfigConfirmation(submissions, allChanges)
            busy = false
        }
    }

    pending?.let { confirmation ->
        val retryAfterApply = confirmation.submissions.singleOrNull()?.retryAfterApply == true
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (retryAfterApply) "Apply settings and retry?" else "Apply Winlator settings?") },
            text = {
                DialogLazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    warning?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(confirmation.changes) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    item { Text("AGM never changes or retries Winlator settings without this confirmation.") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onSubmit(confirmation.submissions)
                }) { Text(if (retryAfterApply) "Apply & retry" else "Apply") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    suggested?.let { s ->
        val entries = remember(s) {
            s.suggestedConfig.keys().asSequence()
                .map { it to WinlatorConfigJson.inputValue(s.suggestedConfig.opt(it)) }
                .toList()
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { suggested = null },
            title = { Text("Recommended settings") },
            text = {
                DialogLazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text(
                            "${s.engineLabel.ifBlank { "Unknown engine" }} • ${s.confidence} confidence" +
                                (s.architecture.takeIf { it.isNotBlank() && it != "unknown" }?.let { " • $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!s.hasSuggestions) {
                        item { Text("Winlator has no confident configuration suggestions for this game.") }
                    } else {
                        if (entries.isNotEmpty()) {
                            item { Text("Suggested configuration", style = MaterialTheme.typography.labelLarge) }
                            items(entries) { (key, value) ->
                                Text("${fieldLabel(key)}: $value", style = MaterialTheme.typography.bodySmall)
                                s.metadataFor(key)?.let { metadata ->
                                    val details = buildList {
                                        metadata.currentValue?.let { add("Current: $it") }
                                        metadata.targetValue?.let { add("Target: $it") }
                                        metadata.source?.let { add("Source: ${it.replace('_', ' ')}") }
                                        metadata.confidence?.let { add("$it confidence") }
                                        if (metadata.automaticApplySafe == false) add("Review required")
                                    }.joinToString(" • ")
                                    if (details.isNotBlank()) {
                                        Text(
                                            details,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    metadata.rationale?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        s.suggestedRuntimeLocale?.let { locale ->
                            item { Text("Suggested launch locale", style = MaterialTheme.typography.labelLarge) }
                            item {
                                val label = WINLATOR_LOCALES.firstOrNull { it.second == locale }?.first ?: locale
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (s.rationale.isNotEmpty()) {
                        item { Text("Why", style = MaterialTheme.typography.labelLarge) }
                        items(s.rationale) { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (s.evidence.isNotEmpty()) {
                        item { Text("Detected", style = MaterialTheme.typography.labelLarge) }
                        items(s.evidence) { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    item { Text("AGM applies these to this game's runtime configuration only after you confirm the diff.") }
                }
            },
            confirmButton = {
                TextButton(enabled = s.hasSuggestions, onClick = { applySuggested(s) }) { Text("Review & apply") }
            },
            dismissButton = { TextButton(onClick = { suggested = null }) { Text("Close") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!embedded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${app.label} — Winlator", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Settings are per game, including games sharing a container.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            HorizontalDivider()
        }
        val settingsAvailable = data.settings != null
        val tabs = buildList {
            add(WinlatorSettingsTab.General)
            if (settingsAvailable) add(WinlatorSettingsTab.Translator)
            add(WinlatorSettingsTab.Runtime)
            add(WinlatorSettingsTab.Diagnostics)
        }
        var tab by remember(game.id) { mutableStateOf(WinlatorSettingsTab.General) }
        val selectedIndex = tabs.indexOf(tab).coerceAtLeast(0)
        androidx.compose.material3.ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 12.dp,
        ) {
            tabs.forEachIndexed { i, t ->
                androidx.compose.material3.Tab(
                    selected = i == selectedIndex,
                    onClick = { tab = t },
                    text = { Text(t.title) },
                )
            }
        }
        DialogLazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == WinlatorSettingsTab.General) {
            runnerRec?.takeIf { it.prefersJoiPlay && joiPlayInstalled }?.let { rec ->
                item { RunnerRecommendationCard(rec) }
            }
            item {
                Text("General", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = dosPath,
                    onValueChange = { dosPath = it; dosDirty = true },
                    label = { Text("Windows executable path") },
                    placeholder = { Text("C:\\Program Files\\Game\\game.exe") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (data.executables.isNotEmpty()) {
                    var exeMenu by remember(game.id) { mutableStateOf(false) }
                    Text(
                        "Pick a different executable found in this game's folder. Review the path " +
                            "before applying.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        androidx.compose.material3.OutlinedButton(onClick = { exeMenu = true }) {
                            Text("Choose executable (${data.executables.size})")
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = exeMenu, onDismissRequest = { exeMenu = false }) {
                            data.executables.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.label)
                                            Text(
                                                option.dosPath,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        exeMenu = false
                                        dosPath = option.dosPath
                                        dosDirty = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (data.settings != null) {
                item {
                    var localeMenu by remember(game.id, data.settings.settingsSha256) { mutableStateOf(false) }
                    val currentLabel = WINLATOR_LOCALES.firstOrNull { it.second == localeTag }?.first ?: localeTag
                    Text("Launch locale", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs this game with the selected Wine locale (applied as LANG/LC_ALL to " +
                            "this game only). Persists per game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        Button(onClick = { localeMenu = true }, enabled = !busy) {
                            Text(currentLabel)
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = localeMenu, onDismissRequest = { localeMenu = false }) {
                            WINLATOR_LOCALES.forEach { (label, tag) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        localeMenu = false
                                        localeTag = tag
                                        localeDirty = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (data.settings != null && inputFields.isNotEmpty()) {
                item {
                    Text("Controller overlay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        data.settingsSchema?.namespace("input")?.description
                            ?.takeIf { it.isNotBlank() }
                            ?: "On-screen controller layout Winlator shows while this game runs. " +
                                "Persists per game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(inputFields, key = { "input-" + it.key }) { field ->
                    WinlatorFieldEditor(
                        field = field,
                        value = inputValues[field.key].orEmpty(),
                        onValue = { inputValues[field.key] = it; inputDirty[field.key] = true },
                        onReset = {
                            inputValues[field.key] = WinlatorConfigJson.inputValue(field.defaultValue)
                            inputDirty[field.key] = true
                        },
                    )
                }
            }
            }
            if (tab == WinlatorSettingsTab.Translator && data.settings != null && ocrFields.isNotEmpty()) {
                item {
                    Text("Auto-translator", style = MaterialTheme.typography.titleMedium)
                    Text(
                        data.settingsSchema?.namespace("ocr")?.description
                            ?.takeIf { it.isNotBlank() }
                            ?: "On-screen OCR translation for this game. Persists per game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val visibleOcr = ocrFields.filter { field ->
                    field.dependsOn == null || (ocrValues[field.dependsOn]?.toBooleanStrictOrNull() == true)
                }
                items(visibleOcr, key = { "ocr-" + it.key }) { field ->
                    WinlatorFieldEditor(
                        field = field,
                        value = ocrValues[field.key].orEmpty(),
                        onValue = { ocrValues[field.key] = it; ocrDirty[field.key] = true },
                        onReset = {
                            ocrValues[field.key] = WinlatorConfigJson.inputValue(field.defaultValue)
                            ocrDirty[field.key] = true
                        },
                    )
                }
            }
            if (tab == WinlatorSettingsTab.Runtime) {
            if (data.capabilities.configSuggestions && data.schema != null) {
                item {
                    Text("Recommended settings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Winlator inspects this game's files and suggests a starting runtime " +
                            "configuration. Nothing changes until you review and confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestedError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = !suggestedLoading, onClick = { fetchSuggested() }) {
                        if (suggestedLoading) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Get recommended settings")
                        }
                    }
                }
            }
            if (data.schema != null && game.configJson != null && game.configSha256 != null) {
                item {
                    val memory = remember {
                        ActivityManager.MemoryInfo().also {
                            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
                        }.totalMem
                    }
                    Text("Runtime configuration", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android physical RAM: ${formatMemory(memory)} (read-only). Wine video-memory, resolution, and graphics cache settings do not change physical RAM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Graphics-driver version policy (config schemaVersion 2+): a prominent primary
                // control. The raw driver override configs are only relevant for "specific", so we
                // reveal them just for that policy. Absent field (older Winlator) -> flat fallback.
                val driverPolicyField = data.schema.fields.firstOrNull { it.key == "driverPolicy" }
                val overrideKeys = listOf("graphicsDriverConfig", "dxwrapperConfig")
                if (driverPolicyField != null) {
                    item {
                        Text("Graphics drivers", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Default keeps this game's saved driver versions. Latest makes the " +
                                "container use the newest installed drivers each launch. Specific pins " +
                                "explicit driver versions for tough cases.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = "cfg-driverPolicy") {
                        WinlatorFieldEditor(
                            field = driverPolicyField,
                            value = values["driverPolicy"].orEmpty(),
                            onValue = { values["driverPolicy"] = it; dirty["driverPolicy"] = true },
                            onReset = {
                                values["driverPolicy"] = WinlatorConfigJson.inputValue(driverPolicyField.defaultValue)
                                dirty["driverPolicy"] = true
                            },
                        )
                    }
                    if (values["driverPolicy"].orEmpty() == "specific") {
                        val overrideFields = data.schema.fields.filter { it.key in overrideKeys }
                        if (overrideFields.isNotEmpty()) {
                            item {
                                Text(
                                    "Specific driver override — set an explicit version in these driver " +
                                        "configs (used only while the policy is Specific).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(overrideFields, key = { "cfg-drv-" + it.key }) { field ->
                                WinlatorFieldEditor(
                                    field = field,
                                    value = values[field.key].orEmpty(),
                                    onValue = { values[field.key] = it; dirty[field.key] = true },
                                    onReset = {
                                        values[field.key] = WinlatorConfigJson.inputValue(field.defaultValue)
                                        dirty[field.key] = true
                                    },
                                )
                            }
                        }
                    }
                    val restFields = data.schema.fields.filter {
                        it.key != "driverPolicy" && it.key !in overrideKeys
                    }
                    items(restFields, key = { "cfg-" + it.key }) { field ->
                        WinlatorFieldEditor(
                            field = field,
                            value = values[field.key].orEmpty(),
                            onValue = { values[field.key] = it; dirty[field.key] = true },
                            onReset = {
                                values[field.key] = WinlatorConfigJson.inputValue(field.defaultValue)
                                dirty[field.key] = true
                            },
                        )
                    }
                } else {
                    items(data.schema.fields, key = { it.key }) { field ->
                        WinlatorFieldEditor(
                            field = field,
                            value = values[field.key].orEmpty(),
                            onValue = { values[field.key] = it; dirty[field.key] = true },
                            onReset = {
                                values[field.key] = WinlatorConfigJson.inputValue(field.defaultValue)
                                dirty[field.key] = true
                            },
                        )
                    }
                }
            } else {
                item {
                    Text(
                        "This Winlator build does not advertise API v4 managed configuration. AGM will only update the executable path.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            }
            if (tab == WinlatorSettingsTab.Diagnostics) {
            item {
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reports are deterministic classifications generated locally by Winlator. No AI or full raw log is used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (data.settings != null && diagFields.isNotEmpty()) {
                items(diagFields, key = { "diag-" + it.key }) { field ->
                    WinlatorFieldEditor(
                        field = field,
                        value = diagValues[field.key].orEmpty(),
                        onValue = { diagValues[field.key] = it; diagDirty[field.key] = true },
                        onReset = {
                            diagValues[field.key] = WinlatorConfigJson.inputValue(field.defaultValue)
                            diagDirty[field.key] = true
                        },
                    )
                }
            }
            if (!data.capabilities.managedDiagnostics) {
                item { Text("This Winlator build does not advertise managed diagnostics.") }
            } else if (data.reports.isEmpty()) {
                item { Text("No managed-session reports are available for this game.") }
            } else {
                items(data.reports, key = { it.reportId }) { report ->
                    WinlatorReportCard(
                        report = report,
                        onApplySuggestion = { suggestion, retry ->
                            prepareRequested(
                                source = "suggestion",
                                reportId = report.reportId,
                                suggestionId = suggestion.id,
                                requestedSet = JSONObject(suggestion.setJson),
                                requestedBaseHash = suggestion.baseConfigSha256,
                                retry = retry,
                            )
                        },
                    )
                }
            }
            if (data.lastGood != null) {
                item {
                    val (hash, config) = data.lastGood
                    TextButton(
                        onClick = {
                            val current = JSONObject(game.configJson ?: "{}")
                            val good = JSONObject(config)
                            val set = JSONObject()
                            data.schema?.fields?.forEach { field ->
                                if (good.has(field.key) && current.opt(field.key) != good.opt(field.key)) {
                                    set.put(field.key, good.get(field.key))
                                }
                            }
                            prepareRequested(
                                source = "restore",
                                requestedSet = set,
                                requestedBaseHash = game.configSha256,
                            )
                        }
                    ) { Text("Review restore of last known-good settings ($hash)") }
                }
            }
            item {
                Text("Configuration history", style = MaterialTheme.typography.titleMedium)
            }
            if (data.history.isEmpty()) {
                item { Text("No AGM configuration changes recorded.") }
            } else {
                items(data.history, key = { it.id }) { change ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${change.source} — ${change.status} — ${change.afterSha256.take(12)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (change.status == "failed") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (change.status == "applied" && data.schema != null) {
                            TextButton(onClick = {
                                val current = JSONObject(game.configJson ?: "{}")
                                val historical = JSONObject(change.afterJson)
                                val set = JSONObject()
                                data.schema.fields.forEach { field ->
                                    if (historical.has(field.key) && current.opt(field.key) != historical.opt(field.key)) {
                                        set.put(field.key, historical.get(field.key))
                                    }
                                }
                                prepareRequested(
                                    source = "restore",
                                    requestedSet = set,
                                    requestedBaseHash = game.configSha256,
                                )
                            }) { Text("Restore") }
                        }
                    }
                }
            }
            }
            item { Spacer(Modifier.heightIn(min = 12.dp)) }
        }
        // Persistent review & apply footer (always visible, independent of the active tab/scroll).
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            warning?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val configPending = (if (dosDirty) 1 else 0) + dirty.values.count { it }
            val settingsPending = (if (localeDirty) 1 else 0) + ocrDirty.values.count { it } +
                diagDirty.values.count { it } + inputDirty.values.count { it }
            val pendingCount = configPending + settingsPending
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (pendingCount == 0) "No pending changes"
                    else "$pendingCount pending change" + (if (pendingCount == 1) "" else "s"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = !busy && pendingCount > 0,
                    onClick = { prepareAll() },
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    else Text("Review & apply")
                }
            }
        }
    }
}

/** Appends a preset argument token-string to a single-line command line, de-duplicating tokens. */
private fun appendArgument(current: String, addition: String): String {
    val add = addition.trim()
    if (add.isEmpty()) return current
    val existing = current.trim()
    if (existing.isEmpty()) return add
    val existingTokens = existing.split(Regex("\\s+"))
    val addTokens = add.split(Regex("\\s+"))
    if (addTokens.all { it in existingTokens }) return existing
    return "$existing $add"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WinlatorFieldEditor(
    field: WinlatorConfigField,
    value: String,
    onValue: (String) -> Unit,
    onReset: () -> Unit,
) {
    var menuOpen by remember(field.key) { mutableStateOf(false) }
    var revealSensitive by remember(field.key) { mutableStateOf(false) }
    val currentOption = field.options.firstOrNull { it.value.toString() == value }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fieldLabel(field.key), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("Default") }
        }
        when {
            field.wireType == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = value.toBooleanStrictOrNull() ?: false, onCheckedChange = { onValue(it.toString()) })
                Spacer(Modifier.width(8.dp))
                Text(if (value.toBooleanStrictOrNull() == true) "Enabled" else "Disabled")
            }
            field.options.isNotEmpty() -> Box {
                Button(onClick = { menuOpen = true }) {
                    Text(currentOption?.label ?: "Current (not advertised): $value")
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (currentOption == null && value.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Keep current: $value") },
                            onClick = { menuOpen = false },
                        )
                    }
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { onValue(option.value.toString()); menuOpen = false },
                        )
                    }
                }
            }
            else -> {
                val sensitive = field.key == "envVars" &&
                    Regex("""(?i)(token|password|secret|api[_-]?key)\s*=""").containsMatchIn(value)
                OutlinedTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = field.editor != "env_vars",
                    visualTransformation = if (sensitive && !revealSensitive) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = if (sensitive) {
                        { TextButton(onClick = { revealSensitive = !revealSensitive }) { Text(if (revealSensitive) "Hide" else "Show") } }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                val presets = field.format?.optJSONArray("presets")
                if (presets != null && presets.length() > 0) {
                    Text("Suggestions", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0 until presets.length()) {
                            val preset = presets.optJSONObject(i) ?: continue
                            val presetValue = preset.optString("value")
                            if (presetValue.isBlank()) continue
                            val presetLabel = preset.optString("label").ifBlank { presetValue }
                            AssistChip(
                                onClick = { onValue(appendArgument(value, presetValue)) },
                                label = { Text(presetLabel) },
                            )
                        }
                    }
                }
            }
        }
        Text(field.description, style = MaterialTheme.typography.bodySmall)
        field.dependsOn?.let {
            Text("Used with $it.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun WinlatorReportCard(
    report: WinlatorDiagnosticReport,
    onApplySuggestion: (WinlatorSuggestion, Boolean) -> Unit,
) {
    var expanded by remember(report.reportId) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${humanize(report.outcome)} — ${humanize(report.category)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${report.confidence} confidence • ${formatDuration(report.durationMillis)} • config ${report.configHealth}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Phase ${humanize(report.phase)}" +
                    (report.exitCode?.let { " • exit $it" } ?: "") +
                    (report.signal?.let { " • signal $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            report.evidence.forEach {
                Text("[${it.source}] ${it.message}", style = MaterialTheme.typography.bodySmall)
            }
            report.suggestions.forEach { suggestion ->
                Text(suggestion.title, style = MaterialTheme.typography.labelLarge)
                Text(suggestion.rationale, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { onApplySuggestion(suggestion, false) }) { Text("Apply only") }
                    TextButton(onClick = { onApplySuggestion(suggestion, true) }) { Text("Apply & retry") }
                }
            }
            if (report.appliedConfigJson != null) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide applied configuration" else "Show applied configuration")
                }
                if (expanded) Text(report.appliedConfigJson, style = MaterialTheme.typography.bodySmall)
            } else if (report.appliedConfigOmitted) {
                Text("Applied configuration was omitted to keep the report bounded.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Derives the Windows drive/prefix that Winlator maps the game folder to, by comparing the current
 *  executable's real path (relative to the game folder) with its Windows path. Null when the mapping
 *  can't be inferred (missing paths, or the executable is not under the game folder). */
private fun deriveWinlatorDosRoot(game: WinlatorApi.ManagedGame): String? {
    val realExe = game.executablePath ?: return null
    val dosExe = game.executableDosPath ?: return null
    val realRoot = game.gamePath?.trimEnd('/', '\\') ?: return null
    if (!realExe.startsWith(realRoot)) return null
    val relBackslash = realExe.removePrefix(realRoot).trimStart('/', '\\').replace('/', '\\')
    if (relBackslash.isEmpty()) return null
    if (!dosExe.endsWith(relBackslash, ignoreCase = true)) return null
    return dosExe.dropLast(relBackslash.length).trimEnd('\\')
}

/** Scans the game folder for Windows executables the user could switch to, deriving each one's
 *  Windows path from the current executable's real->DOS mapping. Empty when the mapping is unknown. */
private fun discoverWinlatorExecutables(game: WinlatorApi.ManagedGame): List<WinlatorExecutableOption> {
    val rootPath = game.gamePath ?: return emptyList()
    val root = java.io.File(rootPath)
    if (!root.isDirectory) return emptyList()
    val dosRoot = deriveWinlatorDosRoot(game) ?: return emptyList()
    val rootPrefix = root.absolutePath.trimEnd('/', '\\')
    return runCatching {
        root.walkTopDown()
            .maxDepth(5)
            .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            .take(200)
            .map { file ->
                val rel = file.absolutePath.removePrefix(rootPrefix).trimStart('/', '\\')
                WinlatorExecutableOption(
                    label = rel.replace('\\', '/'),
                    dosPath = "$dosRoot\\${rel.replace('/', '\\')}",
                )
            }
            .toList()
            .sortedBy { it.label.lowercase(java.util.Locale.US) }
    }.getOrDefault(emptyList())
}

private suspend fun loadWinlatorManagement(
    context: Context,
    app: InstalledApp,
): WinlatorManagementLoad {
    val gameId = app.winlatorGameId
        ?: return WinlatorManagementLoad.Failed("This library item has no Winlator game ID.")
    return runCatching {
        val capabilities = WinlatorClient.capabilities(context).getOrThrow()
        val game = fetchManagedGame(context, gameId)
            ?: error("Winlator did not return the managed game.")
        val schema = if (
            capabilities.apiVersion >= 4 &&
            capabilities.managedGameConfiguration &&
            capabilities.gameConfigSchemaVersion >= 1 &&
            capabilities.configSchemaPath == "config-schema" &&
            capabilities.configConflictDetection &&
            capabilities.configUpdateExtra == "config_update_json"
        ) {
            when (val read = WinlatorClient.getConfigSchema(context)) {
                is WinlatorClient.Read.Ok -> WinlatorConfigSchema.parse(read.payload)
                is WinlatorClient.Read.Err -> error("${read.code}: ${read.message}")
                is WinlatorClient.Read.Unavailable -> error(read.reason)
            }
        } else null
        val settingsSupported = capabilities.managedGameSettings &&
            capabilities.gameSettingsSchemaVersion >= 1 &&
            capabilities.settingsSchemaPath == "settings-schema" &&
            capabilities.settingsConflictDetection &&
            capabilities.settingsUpdateExtra == "settings_update_json"
        val settings = if (settingsSupported) fetchGameSettings(context, gameId) else null
        val settingsSchema = if (settingsSupported) {
            when (val read = WinlatorClient.getSettingsSchema(context)) {
                is WinlatorClient.Read.Ok ->
                    runCatching { WinlatorSettingsSchema.parse(read.payload) }
                        .onFailure { AppLog.w("Winlator", "Settings schema parse failed", it) }
                        .getOrNull()
                is WinlatorClient.Read.Err -> {
                    AppLog.w("Winlator", "Settings schema failed ${read.code}: ${read.message}")
                    null
                }
                is WinlatorClient.Read.Unavailable -> {
                    AppLog.w("Winlator", "Settings schema unavailable: ${read.reason}")
                    null
                }
            }
        } else null
        var reports = WinlatorManagementStore.reports(context, gameId)
        if (
            capabilities.managedDiagnostics &&
            capabilities.diagnosticHistory &&
            capabilities.diagnosticsPath == "diagnostics"
        ) {
            when (val read = WinlatorClient.listDiagnostics(context, gameId, limit = 20)) {
                is WinlatorClient.Read.Ok -> {
                    reports = WinlatorDiagnosticReport.parseList(read.payload)
                    WinlatorManagementStore.cacheReports(context, reports)
                }
                is WinlatorClient.Read.Err ->
                    AppLog.w("Winlator", "Diagnostic history failed ${read.code}: ${read.message}")
                is WinlatorClient.Read.Unavailable ->
                    AppLog.w("Winlator", "Diagnostic history unavailable: ${read.reason}")
            }
        }
        WinlatorManagementLoad.Ready(
            WinlatorManagementData(
                capabilities = capabilities,
                game = game,
                schema = schema,
                settings = settings,
                settingsSchema = settingsSchema,
                reports = reports,
                history = WinlatorManagementStore.history(context, gameId),
                lastGood = WinlatorManagementStore.lastGood(context, gameId),
                executables = withContext(Dispatchers.IO) { discoverWinlatorExecutables(game) },
            )
        )
    }.getOrElse { WinlatorManagementLoad.Failed(it.message ?: it.toString()) }
}

private suspend fun fetchManagedGame(context: Context, gameId: String): WinlatorApi.ManagedGame? =
    when (val read = WinlatorClient.getGame(context, gameId)) {
        is WinlatorClient.Read.Ok -> WinlatorApi.ManagedGame.parse(JSONObject(read.payload))
        is WinlatorClient.Read.Err -> {
            AppLog.w("Winlator", "Game refresh failed ${read.code}: ${read.message}")
            null
        }
        is WinlatorClient.Read.Unavailable -> {
            AppLog.w("Winlator", "Game refresh unavailable: ${read.reason}")
            null
        }
    }

private suspend fun fetchGameSettings(context: Context, gameId: String): WinlatorGameSettings? =
    when (val read = WinlatorClient.getGameSettings(context, gameId)) {
        is WinlatorClient.Read.Ok -> runCatching { WinlatorGameSettings.parse(read.payload) }
            .onFailure { AppLog.w("Winlator", "Game settings parse failed", it) }
            .getOrNull()
        is WinlatorClient.Read.Err -> {
            AppLog.w("Winlator", "Game settings failed ${read.code}: ${read.message}")
            null
        }
        is WinlatorClient.Read.Unavailable -> {
            AppLog.w("Winlator", "Game settings unavailable: ${read.reason}")
            null
        }
    }

private fun fieldLabel(key: String): String = key
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replaceFirstChar { it.uppercase() }

private fun humanize(value: String): String =
    value.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun formatDuration(milliseconds: Long): String =
    if (milliseconds < 60_000) "${milliseconds / 1000}s"
    else "${milliseconds / 60_000}m ${(milliseconds % 60_000) / 1000}s"

private fun formatMemory(bytes: Long): String = "%.1f GiB".format(bytes / 1_073_741_824.0)


/**
 * Advisory-only card shown on the Winlator General tab when Winlator's `runnerRecommendation`
 * hints that a game (e.g. RPG Maker RGSS) runs better under JoiPlay's native interpreter. AGM
 * never moves the game automatically — this only informs the user.
 */
@Composable
private fun RunnerRecommendationCard(rec: RunnerRecommendation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Runs better in JoiPlay",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Winlator suitability: ${humanize(rec.winlatorSuitability)} • ${rec.confidence} confidence. " +
                    "This engine has a native interpreter in JoiPlay, which usually avoids Winlator's issues.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            rec.reasons.take(4).forEach { reason ->
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (rec.knownIssues.isNotEmpty()) {
                Text(
                    "Known Winlator issues: ${rec.knownIssues.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                "AGM won't move it for you — import the game in JoiPlay if you'd like to try it there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
