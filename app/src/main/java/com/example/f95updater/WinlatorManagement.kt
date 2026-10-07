package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class WinlatorConfigOption(
    val value: Any,
    val label: String,
)

data class WinlatorConfigField(
    val key: String,
    val wireType: String,
    val editor: String,
    val defaultValue: Any,
    val description: String,
    val options: List<WinlatorConfigOption>,
    val dependsOn: String?,
    val format: JSONObject?,
) {
    companion object {
        fun parse(field: JSONObject): WinlatorConfigField {
            val options = field.optJSONArray("options")
            return WinlatorConfigField(
                key = field.getString("key"),
                wireType = field.getString("wireType"),
                editor = field.getString("editor"),
                defaultValue = field.get("default"),
                description = field.optString("description"),
                options = buildList {
                    if (options != null) {
                        for (optionIndex in 0 until options.length()) {
                            val option = options.getJSONObject(optionIndex)
                            add(
                                WinlatorConfigOption(
                                    value = option.get("value"),
                                    label = option.optString("label", option.get("value").toString()),
                                )
                            )
                        }
                    }
                },
                dependsOn = field.optString("dependsOn").takeIf { it.isNotBlank() },
                format = field.optJSONObject("format"),
            )
        }
    }
}

data class WinlatorConfigSchema(
    val schemaVersion: Int,
    val fields: List<WinlatorConfigField>,
) {    companion object {
        fun parse(raw: String): WinlatorConfigSchema {
            val root = JSONObject(raw)
            val fields = root.getJSONArray("fields")
            return WinlatorConfigSchema(
                schemaVersion = root.getInt("schemaVersion"),
                fields = buildList {
                    for (index in 0 until fields.length()) {
                        add(WinlatorConfigField.parse(fields.getJSONObject(index)))
                    }
                },
            )
        }
    }
}

data class WinlatorSettingsNamespace(
    val key: String,
    val description: String,
    val fields: List<WinlatorConfigField>,
)

/**
 * The per-game *settings* schema (namespaced fields, e.g. `ocr`, `localization`). Distinct from the
 * flat [WinlatorConfigSchema]; fields use the identical descriptor shape so the same field editor
 * renders both. Changes apply on the settings surface (settings_update_json), keyed by namespace.
 */
data class WinlatorSettingsSchema(
    val schemaVersion: Int,
    val namespaces: List<WinlatorSettingsNamespace>,
) {
    fun namespace(key: String): WinlatorSettingsNamespace? = namespaces.firstOrNull { it.key == key }

    companion object {
        fun parse(raw: String): WinlatorSettingsSchema {
            val root = JSONObject(raw)
            val namespaces = root.optJSONArray("namespaces")
            return WinlatorSettingsSchema(
                schemaVersion = root.optInt("schemaVersion", 1),
                namespaces = buildList {
                    if (namespaces != null) {
                        for (i in 0 until namespaces.length()) {
                            val ns = namespaces.getJSONObject(i)
                            val fields = ns.optJSONArray("fields")
                            add(
                                WinlatorSettingsNamespace(
                                    key = ns.getString("key"),
                                    description = ns.optString("description"),
                                    fields = buildList {
                                        if (fields != null) {
                                            for (j in 0 until fields.length()) {
                                                add(WinlatorConfigField.parse(fields.getJSONObject(j)))
                                            }
                                        }
                                    },
                                )
                            )
                        }
                    }
                },
            )
        }
    }
}

data class WinlatorEvidence(
    val source: String,
    val message: String,
)

data class WinlatorSuggestion(
    val id: String,
    val title: String,
    val rationale: String,
    val baseConfigSha256: String,
    val setJson: String,
)

/**
 * Winlator's file-fingerprinted suggested configuration for a game (capability `configSuggestions`,
 * read at `games/{id}/suggested-config`). [suggestedConfig] is a subset of *runtime config* fields
 * (including runtime fields such as dxwrapper, graphicsDriver, and winVersion) applied through the config
 * surface; [suggestedSettings] is a subset of *settings* fields (currently only
 * `localization.runtimeLocale`) applied through the settings surface. Both are always present in the
 * payload but either may be empty. Unknown engine -> `confidence` "low" and empty suggestions.
 */
data class WinlatorSuggestedConfig(
    val gameId: String,
    val engine: String,
    val engineLabel: String,
    val confidence: String,
    val architecture: String,
    val evidence: List<String>,
    val suggestedConfig: JSONObject,
    val suggestedSettings: JSONObject,
    val rationale: List<String>,
    val suggestionMetadata: Map<String, WinlatorSuggestionMetadata> = emptyMap(),
    val runnerRecommendation: RunnerRecommendation? = null,
) {
    val hasSuggestions: Boolean get() = suggestedConfig.length() > 0 || suggestedSettings.length() > 0

    /** The suggested Wine runtime locale, if the suggester emitted one (e.g. "ja_JP.UTF-8"). */
    val suggestedRuntimeLocale: String?
        get() = suggestedSettings.optJSONObject("localization")
            ?.optString("runtimeLocale")
            ?.takeIf { it.isNotBlank() }

    fun metadataFor(key: String): WinlatorSuggestionMetadata? = suggestionMetadata[key]

    companion object {
        fun parse(raw: String): WinlatorSuggestedConfig {
            val root = JSONObject(raw)
            return WinlatorSuggestedConfig(
                gameId = root.optString("gameId"),
                engine = root.optString("engine"),
                engineLabel = root.optString("engineLabel").ifBlank { root.optString("engine") },
                confidence = root.optString("confidence"),
                architecture = root.optString("architecture", "unknown"),
                evidence = stringList(root.optJSONArray("evidence")),
                suggestedConfig = root.optJSONObject("suggestedConfig") ?: JSONObject(),
                suggestedSettings = root.optJSONObject("suggestedSettings") ?: JSONObject(),
                rationale = stringList(root.optJSONArray("rationale")),
                suggestionMetadata = parseSuggestionMetadata(root.optJSONObject("suggestionMetadata")),
                // Independent of hasSuggestions: a runner recommendation may be present even when
                // both suggestedConfig and suggestedSettings are empty.
                runnerRecommendation = root.optJSONObject("runnerRecommendation")
                    ?.let { RunnerRecommendation.parse(it) },
            )
        }

        private fun parseSuggestionMetadata(root: JSONObject?): Map<String, WinlatorSuggestionMetadata> {
            if (root == null) return emptyMap()
            return buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    root.optJSONObject(key)?.let { put(key, WinlatorSuggestionMetadata.parse(it)) }
                }
            }
        }

        private fun stringList(array: JSONArray?): List<String> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                when (val item = array.opt(i)) {
                    null, JSONObject.NULL -> {}
                    is JSONObject -> {
                        val source = item.optString("source").takeIf { it.isNotBlank() }
                        val message = item.optString("message").takeIf { it.isNotBlank() }
                            ?: item.optString("text").takeIf { it.isNotBlank() }
                            ?: item.toString()
                        add(if (source != null) "[$source] $message" else message)
                    }
                    else -> add(item.toString())
                }
            }
        }
    }
}

data class WinlatorSuggestionMetadata(
    val source: String?,
    val confidence: String?,
    val automaticApplySafe: Boolean?,
    val currentValue: String?,
    val targetValue: String?,
    val rationale: String?,
) {
    companion object {
        fun parse(value: JSONObject): WinlatorSuggestionMetadata = WinlatorSuggestionMetadata(
            source = value.optString("source").takeIf { it.isNotBlank() },
            confidence = value.optString("confidence").takeIf { it.isNotBlank() },
            automaticApplySafe = value.opt("automaticApplySafe")
                ?.takeUnless { it == JSONObject.NULL }
                ?.let { value.optBoolean("automaticApplySafe") },
            currentValue = value.optString("currentEffectiveValue")
                .ifBlank { value.optString("currentValue") }
                .takeIf { it.isNotBlank() },
            targetValue = value.optString("targetValue").takeIf { it.isNotBlank() },
            rationale = value.optString("rationale").takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Winlator's advisory hint (capability `runnerRecommendation`, delivered inside
 * `games/{id}/suggested-config`) about whether a game is better run under Winlator or a native
 * engine interpreter (JoiPlay). Present even when suggestedConfig/suggestedSettings are empty.
 * AGM owns the final routing decision — this is surfaced as advice, never an auto-move.
 */
data class RunnerRecommendation(
    val winlatorSuitability: String,   // ideal | good | fair | poor
    val nativeEngineInterpreterPreferred: Boolean,
    val preferredRunner: String,       // winlator | joiplay | either
    val confidence: String,            // low | medium | high
    val reasons: List<String>,
    val knownIssues: List<String>,     // stable kebab-case tags, e.g. wine-mmdevapi-audio-deadlock
) {
    /** True when Winlator advises a native interpreter (JoiPlay) for this game. */
    val prefersJoiPlay: Boolean
        get() = preferredRunner.equals("joiplay", ignoreCase = true) ||
            (nativeEngineInterpreterPreferred && winlatorSuitability.lowercase() in setOf("poor", "fair"))

    companion object {
        fun parse(obj: JSONObject): RunnerRecommendation = RunnerRecommendation(
            winlatorSuitability = obj.optString("winlatorSuitability").ifBlank { "good" },
            nativeEngineInterpreterPreferred = obj.optBoolean("nativeEngineInterpreterPreferred"),
            preferredRunner = obj.optString("preferredRunner").ifBlank { "either" },
            confidence = obj.optString("confidence").ifBlank { "low" },
            reasons = plainStringList(obj.optJSONArray("reasons")),
            knownIssues = plainStringList(obj.optJSONArray("knownIssues")),
        )

        private fun plainStringList(array: JSONArray?): List<String> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                val s = array.optString(i).takeIf { it.isNotBlank() } ?: continue
                add(s)
            }
        }
    }
}

data class WinlatorDiagnosticReport(
    val reportId: String,
    val sessionId: String?,
    val gameId: String,
    val containerId: Int?,
    val classificationVersion: Int,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
    val outcome: String,
    val phase: String,
    val category: String,
    val confidence: String,
    val exitCode: Int?,
    val signal: Int?,
    val runtimeReached: Boolean,
    val configHealth: String,
    val appliedConfigSha256: String?,
    val appliedConfigJson: String?,
    val appliedConfigOmitted: Boolean,
    val evidence: List<WinlatorEvidence>,
    val suggestions: List<WinlatorSuggestion>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("reportId", reportId)
        sessionId?.let { put("sessionId", it) }
        put("gameId", gameId)
        containerId?.let { put("containerId", it) }
        put("classificationVersion", classificationVersion)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("durationMillis", durationMillis)
        put("outcome", outcome)
        put("phase", phase)
        put("category", category)
        put("confidence", confidence)
        exitCode?.let { put("exitCode", it) }
        signal?.let { put("signal", it) }
        put("runtimeReached", runtimeReached)
        put("configHealth", configHealth)
        appliedConfigSha256?.let { put("appliedConfigSha256", it) }
        appliedConfigJson?.let { put("appliedConfig", JSONObject(it)) }
        put("appliedConfigOmitted", appliedConfigOmitted)
        put("evidence", JSONArray().apply {
            evidence.forEach { put(JSONObject().put("source", it.source).put("message", it.message)) }
        })
        put("suggestions", JSONArray().apply {
            suggestions.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("rationale", it.rationale)
                        .put("baseConfigSha256", it.baseConfigSha256)
                        .put("set", JSONObject(it.setJson))
                )
            }
        })
    }

    companion object {
        fun parse(raw: String): WinlatorDiagnosticReport = parse(JSONObject(raw))

        fun parseList(raw: String): List<WinlatorDiagnosticReport> {
            val array = JSONArray(raw)
            return buildList {
                for (index in 0 until array.length()) add(parse(array.getJSONObject(index)))
            }
        }

        fun parse(value: JSONObject): WinlatorDiagnosticReport {
            val evidence = value.optJSONArray("evidence")
            val suggestions = value.optJSONArray("suggestions")
            return WinlatorDiagnosticReport(
                reportId = value.getString("reportId"),
                sessionId = value.optString("sessionId").takeIf { it.isNotBlank() },
                gameId = value.getString("gameId"),
                containerId = value.optInt("containerId", -1).takeIf { it >= 0 },
                classificationVersion = value.optInt("classificationVersion", 0),
                startedAt = value.optLong("startedAt"),
                endedAt = value.optLong("endedAt"),
                durationMillis = value.optLong("durationMillis"),
                outcome = value.optString("outcome", "unknown"),
                phase = value.optString("phase", "unknown"),
                category = value.optString("category", "unknown_failure"),
                confidence = value.optString("confidence", "low"),
                exitCode = value.optInt("exitCode", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                signal = value.optInt("signal", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                runtimeReached = value.optBoolean("runtimeReached"),
                configHealth = value.optString("configHealth", "unknown"),
                appliedConfigSha256 = value.optString("appliedConfigSha256").takeIf { it.isNotBlank() },
                appliedConfigJson = value.optJSONObject("appliedConfig")?.toString(),
                appliedConfigOmitted = value.optBoolean("appliedConfigOmitted"),
                evidence = buildList {
                    if (evidence != null) {
                        for (index in 0 until evidence.length()) {
                            val item = evidence.getJSONObject(index)
                            add(
                                WinlatorEvidence(
                                    source = item.optString("source", "unknown"),
                                    message = item.optString("message"),
                                )
                            )
                        }
                    }
                },
                suggestions = buildList {
                    if (suggestions != null) {
                        for (index in 0 until suggestions.length()) {
                            val item = suggestions.getJSONObject(index)
                            add(
                                WinlatorSuggestion(
                                    id = item.getString("id"),
                                    title = item.optString("title", item.getString("id")),
                                    rationale = item.optString("rationale"),
                                    baseConfigSha256 = item.getString("baseConfigSha256"),
                                    setJson = item.getJSONObject("set").toString(),
                                )
                            )
                        }
                    }
                },
            )
        }
    }
}

data class WinlatorConfigChange(
    val id: String,
    val gameId: String,
    val createdAt: Long,
    val source: String,
    val reportId: String?,
    val suggestionId: String?,
    val beforeJson: String,
    val afterJson: String,
    val beforeSha256: String,
    val afterSha256: String,
    val status: String,
    val errorCode: String?,
    val errorMessage: String?,
)

enum class WinlatorConfigSurface { Config, Settings }

/**
 * The per-game *settings* envelope returned by `games/{id}/settings`. Distinct from runtime/
 * container config: localization (Wine LANG/LC_ALL) lives here, not on the config surface.
 */
data class WinlatorGameSettings(
    val settingsJson: String,
    val settingsSha256: String,
    val runtimeLocale: String,
) {
    companion object {
        const val DEFAULT_LOCALE = "system"

        fun parse(payload: String): WinlatorGameSettings {
            val envelope = JSONObject(payload)
            val inner = envelope.getJSONObject("settingsJson")
            val sha = envelope.getString("settingsSha256")
            val locale = inner.optJSONObject("localization")
                ?.optString("runtimeLocale")
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_LOCALE
            return WinlatorGameSettings(inner.toString(), sha, locale)
        }
    }
}

data class WinlatorConfigSubmission(
    val gameId: String,
    val title: String,
    val executableDosPath: String?,
    val baseConfigSha256: String,
    val setJson: String,
    val beforeJson: String,
    val afterJson: String,
    val source: String,
    val reportId: String? = null,
    val suggestionId: String? = null,
    val retryAfterApply: Boolean = false,
    val surface: WinlatorConfigSurface = WinlatorConfigSurface.Config,
)

/**
 * Builds a config-surface [WinlatorConfigSubmission] from Winlator's file-fingerprinted suggested
 * configuration, used to seed sensible defaults automatically right after a game is created. Reads
 * the game, its config schema and the suggestion, then keeps only schema-known keys whose value
 * differs from the game's current config. Fresh games also explicitly receive winVersion=win10 when
 * that field is advertised by the discovered schema. Returns no submissions when everything already
 * matches, the relevant surfaces are unsupported, or the game config can't be read.
 */
object WinlatorRecommendedConfig {
    const val WIN_VERSION_KEY = "winVersion"
    const val DEFAULT_WIN_VERSION = "win10"
    const val BOX64_PRESET_KEY = "box64Preset"
    const val DEFAULT_BOX64_PRESET = "PERFORMANCE"
    // AGM's accepted Wine runtime-locale enum (mirrors WINLATOR_LOCALES tags). A suggested locale
    // outside this set is rejected rather than forwarded to Winlator (which would reject it anyway).
    private val ACCEPTED_RUNTIME_LOCALES = setOf(
        "system", "en_US.UTF-8", "pt_BR.UTF-8", "ru_RU.UTF-8",
        "ja_JP.UTF-8", "zh_CN.UTF-8", "zh_TW.UTF-8", "ko_KR.UTF-8",
    )

    /**
     * Builds the submissions that seed a freshly created game with Winlator's recommendations. May
     * return up to two: a CONFIG-surface submission (runtime config incl. graphicsDriver) and a
     * SETTINGS-surface submission (detected game language, localization/runtime locale, and OCR
     * source language). Empty list when there is nothing to apply, values are ambiguous or
     * unsupported by Winlator's advertised schema, or the game/config can't be read.
     */
    suspend fun buildAutoSubmissions(
        context: Context,
        gameId: String,
        title: String,
        detectedLanguage: String? = null,
    ): List<WinlatorConfigSubmission> {
        val capabilities = WinlatorClient.capabilities(context).getOrThrow()
        val suggested = if (capabilities.configSuggestions) {
            when (val read = WinlatorClient.getSuggestedConfig(context, gameId)) {
                is WinlatorClient.Read.Ok -> runCatching { WinlatorSuggestedConfig.parse(read.payload) }.getOrNull()
                else -> null
            }
        } else null

        val submissions = mutableListOf<WinlatorConfigSubmission>()

        // ---- Config surface (runtime config incl. graphicsDriver). ----
        val game = when (val read = WinlatorClient.getGame(context, gameId)) {
            is WinlatorClient.Read.Ok -> runCatching { WinlatorApi.ManagedGame.parse(JSONObject(read.payload)) }.getOrNull()
            else -> null
        } ?: error("Winlator did not return the newly created game.")
        val currentConfig = game.configJson ?: error("Winlator did not return the game configuration.")
        val configHash = game.configSha256 ?: error("Winlator did not return the configuration hash.")
        val schema = when (val read = WinlatorClient.getConfigSchema(context)) {
            is WinlatorClient.Read.Ok -> runCatching { WinlatorConfigSchema.parse(read.payload) }.getOrNull()
            else -> null
        } ?: error("Winlator did not return its configuration schema.")
        val box64Field = schema.fields.firstOrNull { it.key == BOX64_PRESET_KEY }
        require(box64Field?.supportsValue(DEFAULT_BOX64_PRESET) == true) {
            "Winlator does not advertise the Performance Box64 preset."
        }
        run {
            val schemaKeys = schema.fields.map { it.key }.toSet()
            val proposal = freshConfigProposal(schema, suggested?.suggestedConfig, game.winVersionSource)
            val set = recommendedSet(
                JSONObject(currentConfig),
                schemaKeys,
                proposal.suggestedConfig,
                proposal.forceKeys,
            )
            if (set.length() > 0) {
                submissions += WinlatorConfigSubmission(
                    gameId = gameId,
                    title = title,
                    executableDosPath = null,
                    baseConfigSha256 = configHash,
                    setJson = set.toString(),
                    beforeJson = currentConfig,
                    afterJson = WinlatorConfigJson.applySet(currentConfig, set.toString()),
                    source = "recommended-auto",
                    surface = WinlatorConfigSurface.Config,
                )
            }
        }

        val normalizedLanguage = detectedLanguage?.let(::normalizeLanguageTag)
        val requiresLanguageSettings = normalizedLanguage == "ja"
        if (requiresLanguageSettings) {
            require(capabilities.managedGameSettings) {
                "Winlator does not advertise managed language settings."
            }
        }
        if (capabilities.managedGameSettings) {
            val settings = when (val read = WinlatorClient.getGameSettings(context, gameId)) {
                is WinlatorClient.Read.Ok -> runCatching { WinlatorGameSettings.parse(read.payload) }.getOrNull()
                else -> null
            }
            val settingsSchema = when (val read = WinlatorClient.getSettingsSchema(context)) {
                is WinlatorClient.Read.Ok -> runCatching { WinlatorSettingsSchema.parse(read.payload) }.getOrNull()
                else -> null
            }
            if (requiresLanguageSettings && (settings == null || settingsSchema == null)) {
                error("Winlator did not return its managed language settings schema.")
            }
            if (settings != null) {
                val set = buildAutomaticLanguageSettingsSet(
                    schema = settingsSchema,
                    currentSettingsJson = settings.settingsJson,
                    detectedLanguage = detectedLanguage,
                    suggestedRuntimeLocale = suggested?.suggestedRuntimeLocale,
                )
                if (set.length() > 0) {
                    submissions += WinlatorConfigSubmission(
                        gameId = gameId,
                        title = title,
                        executableDosPath = null,
                        baseConfigSha256 = settings.settingsSha256,
                        setJson = set.toString(),
                        beforeJson = settings.settingsJson,
                        afterJson = WinlatorConfigJson.applySet(settings.settingsJson, set.toString()),
                        source = "recommended-auto",
                        surface = WinlatorConfigSurface.Settings,
                    )
                }
                if (requiresLanguageSettings) {
                    val effective = JSONObject(
                        WinlatorConfigJson.applySet(settings.settingsJson, set.toString()),
                    )
                    require(
                        effective.optJSONObject("localization")?.optString("gameLanguage") == "ja" &&
                            effective.optJSONObject("localization")?.optString("runtimeLocale") ==
                            "ja_JP.UTF-8" &&
                            effective.optJSONObject("ocr")?.optString("sourceLanguage") == "ja"
                    ) {
                        "Winlator's settings schema cannot apply all Japanese defaults."
                    }
                }
            }
        }

        return submissions
    }

    /** True if [locale] is an accepted Wine runtime-locale enum value AGM can forward to Winlator. */
    fun isAcceptedRuntimeLocale(locale: String): Boolean = locale in ACCEPTED_RUNTIME_LOCALES

    fun supportsWinVersion(schema: WinlatorConfigSchema?): Boolean =
        schema?.fields?.any { it.key == WIN_VERSION_KEY } == true

    data class FreshConfigProposal(
        val suggestedConfig: JSONObject,
        val forceKeys: Set<String>,
    )

    fun freshConfigProposal(
        schema: WinlatorConfigSchema?,
        suggestedConfig: JSONObject?,
        winVersionSource: String?,
    ): FreshConfigProposal {
        val proposed = suggestedConfig?.let { JSONObject(it.toString()) } ?: JSONObject()
        val forceKeys = linkedSetOf<String>()
        val box64Field = schema?.fields?.firstOrNull { it.key == BOX64_PRESET_KEY }
        if (box64Field?.supportsValue(DEFAULT_BOX64_PRESET) == true) {
            proposed.put(BOX64_PRESET_KEY, DEFAULT_BOX64_PRESET)
            forceKeys += BOX64_PRESET_KEY
        } else {
            proposed.remove(BOX64_PRESET_KEY)
        }
        if (!supportsWinVersion(schema)) {
            proposed.remove(WIN_VERSION_KEY)
            return FreshConfigProposal(proposed, forceKeys)
        }

        if (winVersionSource == "explicit") {
            proposed.remove(WIN_VERSION_KEY)
            return FreshConfigProposal(proposed, forceKeys)
        }
        proposed.put(WIN_VERSION_KEY, DEFAULT_WIN_VERSION)
        forceKeys += WIN_VERSION_KEY
        return FreshConfigProposal(proposed, forceKeys)
    }

    /** Pure filter: keep only schema-known suggested keys whose value differs from the current config. */
    fun recommendedSet(
        current: JSONObject,
        schemaKeys: Set<String>,
        suggestedConfig: JSONObject,
        forceKeys: Set<String> = emptySet(),
    ): JSONObject {
        val set = JSONObject()
        val keys = suggestedConfig.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in schemaKeys) continue
            val value = suggestedConfig.get(key)
            if (
                key in forceKeys ||
                WinlatorConfigJson.inputValue(current.opt(key)) != WinlatorConfigJson.inputValue(value)
            ) {
                set.put(key, value)
            }
        }
        return set
    }
}

internal fun buildAutomaticLanguageSettingsSet(
    schema: WinlatorSettingsSchema?,
    currentSettingsJson: String,
    detectedLanguage: String?,
    suggestedRuntimeLocale: String?,
): JSONObject {
    val current = JSONObject(currentSettingsJson)
    val set = JSONObject()
    val normalizedLanguage = detectedLanguage
        ?.let(::normalizeLanguageTag)
        ?.takeUnless { it == "und" }

    val localizationSchema = schema?.namespace("localization")
    val localizationCurrent = current.optJSONObject("localization") ?: JSONObject()
    val localizationSet = JSONObject()
    if (
        normalizedLanguage != null &&
        localizationSchema.supportsValue("gameLanguage", normalizedLanguage) &&
        localizationCurrent.optString("gameLanguage") != normalizedLanguage
    ) {
        localizationSet.put("gameLanguage", normalizedLanguage)
    }

    val runtimeField = localizationSchema?.fields?.firstOrNull { it.key == "runtimeLocale" }
    val detectedLocale = normalizedLanguage?.let {
        runtimeLocaleForLanguage(it, runtimeField?.options.orEmpty().map { option -> option.value.toString() })
    }
    val suggestedLocale = suggestedRuntimeLocale?.takeIf { locale ->
        WinlatorRecommendedConfig.isAcceptedRuntimeLocale(locale) &&
            if (schema == null) true else runtimeField?.supportsValue(locale) == true
    }
    val runtimeLocale = detectedLocale ?: suggestedLocale
    if (runtimeLocale != null && localizationCurrent.optString("runtimeLocale") != runtimeLocale) {
        localizationSet.put("runtimeLocale", runtimeLocale)
    }
    if (localizationSet.length() > 0) set.put("localization", localizationSet)

    val ocrSchema = schema?.namespace("ocr")
    val sourceLanguage = normalizedLanguage?.let { language ->
        ocrSchema?.fields
            ?.firstOrNull { it.key == "sourceLanguage" }
            ?.supportedLanguageValue(language)
    }
    val ocrCurrent = current.optJSONObject("ocr") ?: JSONObject()
    if (sourceLanguage != null && ocrCurrent.optString("sourceLanguage") != sourceLanguage) {
        set.put("ocr", JSONObject().put("sourceLanguage", sourceLanguage))
    }
    return set
}

private fun WinlatorSettingsNamespace?.supportsValue(key: String, value: String): Boolean =
    this?.fields?.firstOrNull { it.key == key }?.supportsValue(value) == true

private fun WinlatorConfigField.supportsValue(value: String): Boolean =
    options.isEmpty() || options.any { it.value.toString() == value }

private fun WinlatorConfigField.supportedLanguageValue(languageTag: String): String? {
    val normalized = normalizeLanguageTag(languageTag)
    val exact = options.firstOrNull {
        normalizeLanguageTag(it.value.toString()) == normalized
    }?.value?.toString()
    if (exact != null) return exact
    val base = normalized.substringBefore('-')
    return options
        .map { it.value.toString() }
        .filter { normalizeLanguageTag(it).substringBefore('-') == base }
        .singleOrNull()
}

internal fun runtimeLocaleForLanguage(
    languageTag: String,
    supportedLocales: List<String>,
): String? {
    val normalized = normalizeLanguageTag(languageTag)
    val base = normalized.substringBefore('-')
    val languageLocales = supportedLocales.filter {
        normalizeLanguageTag(it.substringBefore('.')).substringBefore('-') == base
    }
    if (languageLocales.size == 1) return languageLocales.single()

    val region = normalized.split('-').firstOrNull { it.length == 2 && it != base }
    if (region != null) {
        languageLocales.singleOrNull {
            normalizeLanguageTag(it.substringBefore('.')).split('-').contains(region)
        }?.let { return it }
    }
    val script = normalized.split('-').firstOrNull { it.length == 4 }
    return when (script) {
        "Hans" -> languageLocales.singleOrNull { "_CN" in it || "_SG" in it }
        "Hant" -> languageLocales.singleOrNull { "_TW" in it || "_HK" in it }
        else -> null
    }
}

object WinlatorConfigJson {
    fun updateJson(baseSha256: String, setJson: String): String =
        JSONObject()
            .put("baseConfigSha256", baseSha256.uppercase(Locale.US))
            .put("set", JSONObject(setJson))
            .toString()

    /** Settings-surface counterpart of [updateJson]; the base hash is sent as Winlator returned it. */
    fun settingsUpdateJson(baseSettingsSha256: String, setJson: String): String =
        JSONObject()
            .put("baseSettingsSha256", baseSettingsSha256)
            .put("set", JSONObject(setJson))
            .toString()

    fun applySet(configJson: String, setJson: String): String {
        val result = JSONObject(configJson)
        val set = JSONObject(setJson)
        for (key in set.keys()) result.put(key, set.get(key))
        return result.toString()
    }

    fun changedSet(beforeJson: String, afterValues: Map<String, Any>): JSONObject {
        val before = JSONObject(beforeJson)
        return JSONObject().apply {
            afterValues.forEach { (key, value) ->
                if (!jsonEquals(before.opt(key), value)) put(key, value)
            }
        }
    }

    fun describeSet(beforeJson: String, setJson: String): List<String> {
        val before = JSONObject(beforeJson)
        val set = JSONObject(setJson)
        return set.keys().asSequence().map { key ->
            "$key: ${displayValue(key, before.opt(key))} -> ${displayValue(key, set.opt(key))}"
        }.toList()
    }

    fun hash(configJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalize(JSONObject(configJson)).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun inputValue(value: Any?): String = when {
        value == null || value == JSONObject.NULL -> ""
        else -> value.toString()
    }

    fun parseInput(field: WinlatorConfigField, raw: String): Any = when (field.wireType) {
        "boolean" -> raw.toBooleanStrict()
        "integer" -> raw.toInt()
        else -> raw
    }

    private fun displayValue(key: String, value: Any?): String {
        val raw = inputValue(value)
        return if (key == "envVars" && sensitiveEnv(raw)) maskEnv(raw) else raw
    }

    private fun sensitiveEnv(value: String): Boolean =
        Regex("""(?i)(token|password|secret|api[_-]?key)\s*=""").containsMatchIn(value)

    fun maskEnv(value: String): String =
        value.split(' ').joinToString(" ") { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@joinToString entry
            val key = entry.substring(0, separator)
            if (Regex("""(?i).*(token|password|secret|api[_-]?key).*""").matches(key)) {
                "$key=••••"
            } else {
                entry
            }
        }

    private fun jsonEquals(left: Any?, right: Any?): Boolean =
        when {
            left == null || left == JSONObject.NULL -> right == null || right == JSONObject.NULL
            left is Number && right is Number -> left.toString() == right.toString()
            else -> left == right
        }

    private fun canonicalize(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toSortedSet().joinToString(
            prefix = "{",
            postfix = "}",
        ) { key -> "${JSONObject.quote(key)}:${canonicalize(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
        ) { canonicalize(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}

object WinlatorManagementStore {
    private const val PREFS = "winlator_management"
    private const val KEY_STORE = "store_json"
    private const val MAX_REPORTS_TOTAL = 200
    private const val MAX_REPORTS_PER_GAME = 20
    private const val MAX_HISTORY_PER_GAME = 20
    private val lock = Any()

    suspend fun cacheReports(context: Context, reports: List<WinlatorDiagnosticReport>) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val root = load(context)
                val all = root.getJSONObject("reports")
                val order = root.getJSONArray("reportOrder")
                val gameOrder = root.getJSONObject("gameReportOrder")
                reports.forEach { report ->
                    all.put(report.reportId, report.toJson())
                    remove(order, report.reportId)
                    prepend(order, report.reportId)
                    val ids = gameOrder.optJSONArray(report.gameId) ?: JSONArray()
                    remove(ids, report.reportId)
                    prepend(ids, report.reportId)
                    gameOrder.put(report.gameId, ids)
                    while (ids.length() > MAX_REPORTS_PER_GAME) {
                        removeReport(root, ids.optString(ids.length() - 1))
                    }
                    if (report.configHealth == "good" && report.appliedConfigJson != null) {
                        root.getJSONObject("lastGood").put(
                            report.gameId,
                            JSONObject()
                                .put("reportId", report.reportId)
                                .put("configSha256", report.appliedConfigSha256)
                                .put("config", JSONObject(report.appliedConfigJson))
                        )
                    }
                }
                while (order.length() > MAX_REPORTS_TOTAL) {
                    removeReport(root, order.optString(order.length() - 1))
                }
                save(context, root)
            }
        }

    suspend fun reports(context: Context, gameId: String): List<WinlatorDiagnosticReport> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val root = load(context)
                val reports = root.getJSONObject("reports")
                val ids = root.getJSONObject("gameReportOrder").optJSONArray(gameId) ?: JSONArray()
                buildList {
                    for (index in 0 until ids.length()) {
                        reports.optJSONObject(ids.optString(index))?.let {
                            add(WinlatorDiagnosticReport.parse(it))
                        }
                    }
                }
            }
        }

    suspend fun lastGood(context: Context, gameId: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                load(context).getJSONObject("lastGood").optJSONObject(gameId)?.let {
                    it.optString("configSha256") to it.getJSONObject("config").toString()
                }
            }
        }

    suspend fun beginChange(context: Context, submission: WinlatorConfigSubmission): String =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val root = load(context)
                val id = UUID.randomUUID().toString()
                val history = root.getJSONObject("history")
                val entries = history.optJSONArray(submission.gameId) ?: JSONArray()
                prepend(
                    entries,
                    JSONObject()
                        .put("id", id)
                        .put("gameId", submission.gameId)
                        .put("createdAt", System.currentTimeMillis())
                        .put("source", submission.source)
                        .put("reportId", submission.reportId)
                        .put("suggestionId", submission.suggestionId)
                        .put("before", JSONObject(submission.beforeJson))
                        .put("after", JSONObject(submission.afterJson))
                        .put("beforeSha256", submission.baseConfigSha256)
                        .put("afterSha256", WinlatorConfigJson.hash(submission.afterJson))
                        .put("status", "pending")
                )
                while (entries.length() > MAX_HISTORY_PER_GAME) entries.remove(entries.length() - 1)
                history.put(submission.gameId, entries)
                save(context, root)
                id
            }
        }

    suspend fun finishChange(
        context: Context,
        changeId: String,
        success: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val root = load(context)
            val history = root.getJSONObject("history")
            outer@ for (gameId in history.keys()) {
                val entries = history.optJSONArray(gameId) ?: continue
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    if (entry.optString("id") == changeId) {
                        entry.put("status", if (success) "applied" else "failed")
                        entry.put("completedAt", System.currentTimeMillis())
                        errorCode?.let { entry.put("errorCode", it) }
                        errorMessage?.let { entry.put("errorMessage", it) }
                        break@outer
                    }
                }
            }
            save(context, root)
        }
    }

    suspend fun history(context: Context, gameId: String): List<WinlatorConfigChange> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val entries = load(context).getJSONObject("history").optJSONArray(gameId) ?: JSONArray()
                buildList {
                    for (index in 0 until entries.length()) {
                        val value = entries.getJSONObject(index)
                        add(
                            WinlatorConfigChange(
                                id = value.getString("id"),
                                gameId = value.getString("gameId"),
                                createdAt = value.optLong("createdAt"),
                                source = value.optString("source"),
                                reportId = value.optString("reportId").takeIf { it.isNotBlank() },
                                suggestionId = value.optString("suggestionId").takeIf { it.isNotBlank() },
                                beforeJson = value.getJSONObject("before").toString(),
                                afterJson = value.getJSONObject("after").toString(),
                                beforeSha256 = value.optString("beforeSha256"),
                                afterSha256 = value.optString("afterSha256"),
                                status = value.optString("status"),
                                errorCode = value.optString("errorCode").takeIf { it.isNotBlank() },
                                errorMessage = value.optString("errorMessage").takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
            }
        }

    private fun load(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STORE, null)
        return raw?.let(::JSONObject) ?: JSONObject()
            .put("schemaVersion", 1)
            .put("reports", JSONObject())
            .put("reportOrder", JSONArray())
            .put("gameReportOrder", JSONObject())
            .put("lastGood", JSONObject())
            .put("history", JSONObject())
    }

    private fun save(context: Context, root: JSONObject) {
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STORE, root.toString())
                .commit()
        ) { "Could not persist Winlator management state." }
    }

    private fun removeReport(root: JSONObject, reportId: String) {
        if (reportId.isBlank()) return
        val report = root.getJSONObject("reports").optJSONObject(reportId)
        root.getJSONObject("reports").remove(reportId)
        remove(root.getJSONArray("reportOrder"), reportId)
        report?.optString("gameId")?.takeIf { it.isNotBlank() }?.let { gameId ->
            root.getJSONObject("gameReportOrder").optJSONArray(gameId)?.let { remove(it, reportId) }
        }
    }

    private fun prepend(array: JSONArray, value: Any) {
        for (index in array.length() downTo 1) array.put(index, array.get(index - 1))
        array.put(0, value)
    }

    private fun remove(array: JSONArray, value: String) {
        for (index in array.length() - 1 downTo 0) {
            val current = array.opt(index)
            if (current == value || (current is JSONObject && current.optString("id") == value)) {
                array.remove(index)
            }
        }
    }
}
