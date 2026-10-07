package com.example.f95updater

import org.json.JSONObject

/**
 * Schema gate for AGM's Unity texture-memory UI. AGM never writes game files: this produces the
 * same hashed Winlator config-surface submission used by the rest of the management UI.
 */
object UnityTextureLimitConfig {
    const val KEY = "unityTextureLimit"
    val values = listOf("off", "1", "2", "3")

    fun advertisedValues(schema: WinlatorConfigSchema?): Set<String> {
        val field = schema?.fields?.singleOrNull { it.key == KEY } ?: return emptySet()
        if (field.wireType != "string" || field.editor != "enum") return emptySet()
        return field.options.map { it.value.toString() }.filterTo(linkedSetOf()) { it in values }
    }

    /** The feature is visible as actionable only when the live schema fully advertises its contract. */
    fun isAdvertised(schema: WinlatorConfigSchema?): Boolean =
        advertisedValues(schema).containsAll(values)

    fun currentValue(game: WinlatorApi.ManagedGame): String =
        game.configJson
            ?.let { runCatching { JSONObject(it).optString(KEY, "off") }.getOrNull() }
            ?.takeIf { it in values }
            ?: "off"

    fun buildSubmission(
        game: WinlatorApi.ManagedGame,
        schema: WinlatorConfigSchema?,
        value: String,
    ): WinlatorConfigSubmission? {
        require(value in values) { "Unsupported Unity texture limit value: $value" }
        if (!isAdvertised(schema) || value !in advertisedValues(schema)) return null
        val before = game.configJson ?: return null
        val hash = game.configSha256 ?: return null
        val current = runCatching { JSONObject(before) }.getOrNull() ?: return null
        if (current.has(KEY) && current.opt(KEY)?.toString() == value) return null
        val set = JSONObject().put(KEY, value).toString()
        return WinlatorConfigSubmission(
            gameId = game.id,
            title = game.title,
            executableDosPath = game.executableDosPath,
            baseConfigSha256 = hash,
            setJson = set,
            beforeJson = before,
            afterJson = WinlatorConfigJson.applySet(before, set),
            source = "unity-texture-memory",
            surface = WinlatorConfigSurface.Config,
        )
    }
}
