package com.example.f95updater

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinlatorManagementTest {
    @Test
    fun recommendedSetKeepsOnlySchemaKnownChangedKeys() {
        val current = JSONObject("""{"dxwrapper":"dxvk","audioDriver":"alsa","launchArguments":""}""")
        val schemaKeys = setOf("dxwrapper", "audioDriver", "launchArguments", "box64Preset")
        val suggested = JSONObject(
            """
            {
              "audioDriver": "silent",
              "launchArguments": "--disable-gpu --in-process-gpu",
              "dxwrapper": "dxvk",
              "unknownField": "ignored",
              "box64Preset": "COMPATIBILITY"
            }
            """.trimIndent()
        )

        val set = WinlatorRecommendedConfig.recommendedSet(current, schemaKeys, suggested)

        // dxwrapper unchanged -> dropped; unknownField not in schema -> dropped.
        assertFalse(set.has("dxwrapper"))
        assertFalse(set.has("unknownField"))
        assertEquals("silent", set.getString("audioDriver"))
        assertEquals("--disable-gpu --in-process-gpu", set.getString("launchArguments"))
        assertEquals("COMPATIBILITY", set.getString("box64Preset"))
        assertEquals(3, set.length())
    }

    @Test
    fun recommendedSetEmptyWhenAllMatchOrUnknown() {
        val current = JSONObject("""{"dxwrapper":"dxvk"}""")
        val set = WinlatorRecommendedConfig.recommendedSet(
            current,
            setOf("dxwrapper"),
            JSONObject("""{"dxwrapper":"dxvk","audioDriver":"silent"}"""),
        )
        // dxwrapper matches, audioDriver not in schema -> empty.
        assertEquals(0, set.length())
    }

    @Test
    fun freshGameWinVersionIsSchemaGatedAndCanBeForcedExplicit() {
        val current = JSONObject("""{"winVersion":"win10"}""")
        val unsupported = WinlatorRecommendedConfig.recommendedSet(
            current,
            emptySet(),
            JSONObject().put("winVersion", "win10"),
            setOf("winVersion"),
        )
        val supported = WinlatorRecommendedConfig.recommendedSet(
            current,
            setOf("winVersion"),
            JSONObject().put("winVersion", "win10"),
            setOf("winVersion"),
        )

        assertFalse(unsupported.has("winVersion"))
        assertEquals("win10", supported.getString("winVersion"))
    }

    @Test
    fun detectsWinVersionOnlyFromDiscoveredSchema() {
        val oldSchema = WinlatorConfigSchema.parse("""{"schemaVersion":4,"fields":[]}""")
        val newSchema = WinlatorConfigSchema.parse(
            """{"schemaVersion":5,"fields":[{"key":"winVersion","wireType":"string","editor":"enum","default":"win10","description":"Windows version","options":[]}]}"""
        )

        assertFalse(WinlatorRecommendedConfig.supportsWinVersion(oldSchema))
        assertTrue(WinlatorRecommendedConfig.supportsWinVersion(newSchema))
    }

    @Test
    fun freshConfigPreservesExplicitWinVersionAndDoesNotLeakToOlderSchemas() {
        val oldSchema = WinlatorConfigSchema.parse("""{"schemaVersion":4,"fields":[]}""")
        val newSchema = WinlatorConfigSchema.parse(
            """{"schemaVersion":5,"fields":[{"key":"winVersion","wireType":"string","editor":"enum","default":"win10","description":"Windows version","options":[]}]}"""
        )
        val suggestion = JSONObject("""{"winVersion":"win10","audioDriver":"alsa"}""")

        val explicit = WinlatorRecommendedConfig.freshConfigProposal(newSchema, suggestion, "explicit")
        val legacyWinlator = WinlatorRecommendedConfig.freshConfigProposal(oldSchema, suggestion, null)
        val defaulted = WinlatorRecommendedConfig.freshConfigProposal(newSchema, JSONObject(), "default")

        assertFalse(explicit.suggestedConfig.has("winVersion"))
        assertEquals("alsa", explicit.suggestedConfig.getString("audioDriver"))
        assertTrue(explicit.forceKeys.isEmpty())
        assertFalse(legacyWinlator.suggestedConfig.has("winVersion"))
        assertEquals("win10", defaulted.suggestedConfig.getString("winVersion"))
        assertEquals(setOf("winVersion"), defaulted.forceKeys)
    }

    @Test
    fun freshConfigForcesAdvertisedPerformanceBox64Preset() {
        val schema = WinlatorConfigSchema.parse(
            """
            {"schemaVersion":5,"fields":[
              {"key":"box64Preset","wireType":"string","editor":"enum","default":"INTERMEDIATE",
               "description":"","options":[
                 {"value":"PERFORMANCE"},{"value":"INTERMEDIATE"},{"value":"COMPATIBILITY"}
               ]}
            ]}
            """.trimIndent(),
        )

        val proposal = WinlatorRecommendedConfig.freshConfigProposal(
            schema,
            JSONObject("""{"box64Preset":"COMPATIBILITY"}"""),
            null,
        )

        assertEquals("PERFORMANCE", proposal.suggestedConfig.getString("box64Preset"))
        assertEquals(setOf("box64Preset"), proposal.forceKeys)
    }

    @Test
    fun parsesPerFieldSuggestionMetadataAndOlderPayloads() {
        val suggested = WinlatorSuggestedConfig.parse(
            """
            {
              "gameId":"legacy",
              "suggestedConfig":{"winVersion":"win10"},
              "suggestionMetadata":{
                "winVersion":{
                  "source":"legacy_default_migration",
                  "confidence":"high",
                  "automaticApplySafe":false,
                  "currentEffectiveValue":"win7",
                  "targetValue":"win10",
                  "rationale":"Use the current default for this legacy container."
                }
              }
            }
            """.trimIndent()
        )
        val metadata = suggested.metadataFor("winVersion")!!

        assertEquals("legacy_default_migration", metadata.source)
        assertEquals("high", metadata.confidence)
        assertEquals(false, metadata.automaticApplySafe)
        assertEquals("win7", metadata.currentValue)
        assertEquals("win10", metadata.targetValue)
        assertTrue(WinlatorSuggestedConfig.parse("""{"gameId":"old","suggestedConfig":{}}""").suggestionMetadata.isEmpty())
    }

    @Test
    fun parsesDiscoverableConfigurationSchema() {
        val schema = WinlatorConfigSchema.parse(
            """
            {
              "schemaVersion": 1,
              "fields": [
                {
                  "key": "graphicsDriver",
                  "wireType": "string",
                  "editor": "enum",
                  "default": "turnip,zink",
                  "description": "Renderer pair.",
                  "options": [
                    {"value": "turnip,zink", "label": "Turnip / Zink"},
                    {"value": "vortek,gladio", "label": "Vortek / Gladio"}
                  ]
                },
                {
                  "key": "forceFullscreen",
                  "wireType": "boolean",
                  "editor": "boolean",
                  "default": false,
                  "description": "Fullscreen."
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, schema.schemaVersion)
        assertEquals("graphicsDriver", schema.fields.first().key)
        assertEquals("vortek,gladio", schema.fields.first().options.last().value)
        assertEquals(false, schema.fields.last().defaultValue)
    }

    @Test
    fun buildsConflictSafePatchAndPreservesUnchangedValues() {
        val before = """{"unknownFuture":"keep","screenSize":"1920x1080","hudMode":1}"""
        val set = JSONObject().put("screenSize", "1280x720")
        val after = WinlatorConfigJson.applySet(before, set.toString())
        val update = JSONObject(WinlatorConfigJson.updateJson(WinlatorConfigJson.hash(before), set.toString()))

        assertEquals("keep", JSONObject(after).getString("unknownFuture"))
        assertEquals(1, JSONObject(after).getInt("hudMode"))
        assertEquals("1280x720", JSONObject(after).getString("screenSize"))
        assertEquals(64, update.getString("baseConfigSha256").length)
        assertEquals("1280x720", update.getJSONObject("set").getString("screenSize"))
    }

    @Test
    fun parsesGameSettingsEnvelopeAndRuntimeLocale() {
        val settings = WinlatorGameSettings.parse(
            """
            {
              "settingsJson": {
                "runtime": {},
                "localization": {"gameLanguage": "und", "runtimeLocale": "ja_JP.UTF-8"}
              },
              "settingsSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """.trimIndent()
        )

        assertEquals("ja_JP.UTF-8", settings.runtimeLocale)
        assertEquals(64, settings.settingsSha256.length)
        assertEquals("und", JSONObject(settings.settingsJson).getJSONObject("localization").getString("gameLanguage"))
    }

    @Test
    fun defaultsRuntimeLocaleToSystemWhenAbsent() {
        val settings = WinlatorGameSettings.parse(
            """{"settingsJson": {"runtime": {}}, "settingsSha256": "0000000000000000000000000000000000000000000000000000000000000000"}"""
        )

        assertEquals("system", settings.runtimeLocale)
    }

    @Test
    fun buildsSettingsUpdateWithBaseSettingsHashUnchanged() {
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val set = JSONObject().put("localization", JSONObject().put("runtimeLocale", "ja_JP.UTF-8"))
        val update = JSONObject(WinlatorConfigJson.settingsUpdateJson(sha, set.toString()))

        assertEquals(sha, update.getString("baseSettingsSha256"))
        assertEquals(
            "ja_JP.UTF-8",
            update.getJSONObject("set").getJSONObject("localization").getString("runtimeLocale"),
        )
    }

    @Test
    fun hashIsCanonicalAndUppercase() {
        val first = WinlatorConfigJson.hash("""{"screenSize":"1280x720","hudMode":1,"forceFullscreen":true}""")
        val second = WinlatorConfigJson.hash("""{"forceFullscreen":true,"hudMode":1,"screenSize":"1280x720"}""")
        val changed = WinlatorConfigJson.hash("""{"forceFullscreen":true,"hudMode":2,"screenSize":"1280x720"}""")

        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertTrue(first.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun buildsCombinedSettingsSetBundlingLocalizationAndOcr() {
        // "Review & apply" bundles a staged locale change and OCR changes into ONE settings-surface
        // set/round-trip. The partial set is what Winlator deep-merges server-side.
        val before = """{"localization":{"gameLanguage":"und","runtimeLocale":"system"},"ocr":{"mode":"OFF"}}"""
        val set = JSONObject()
            .put("localization", JSONObject().put("runtimeLocale", "ja_JP.UTF-8"))
            .put("ocr", JSONObject().put("mode", "SUBTITLE"))
        val after = WinlatorConfigJson.applySet(before, set.toString())
        val update = JSONObject(WinlatorConfigJson.settingsUpdateJson("ABC123", set.toString()))

        val afterJson = JSONObject(after)
        assertEquals("ja_JP.UTF-8", afterJson.getJSONObject("localization").getString("runtimeLocale"))
        assertEquals("SUBTITLE", afterJson.getJSONObject("ocr").getString("mode"))
        assertEquals("ABC123", update.getString("baseSettingsSha256"))
        val sentSet = update.getJSONObject("set")
        assertEquals("ja_JP.UTF-8", sentSet.getJSONObject("localization").getString("runtimeLocale"))
        assertEquals("SUBTITLE", sentSet.getJSONObject("ocr").getString("mode"))
    }

    @Test
    fun detectedLanguageSetsGameLocaleAndTranslatorSourceFromSchema() {
        val schema = WinlatorSettingsSchema.parse(
            """
            {
              "schemaVersion": 2,
              "namespaces": [
                {"key":"localization","description":"","fields":[
                  {"key":"gameLanguage","wireType":"string","editor":"enum","default":"und",
                   "options":[{"value":"und"},{"value":"en"},{"value":"ja"}]},
                  {"key":"runtimeLocale","wireType":"string","editor":"enum","default":"system",
                   "options":[{"value":"system"},{"value":"en_US.UTF-8"},{"value":"ja_JP.UTF-8"}]}
                ]},
                {"key":"ocr","description":"","fields":[
                  {"key":"sourceLanguage","wireType":"string","editor":"enum","default":"auto",
                   "options":[{"value":"auto"},{"value":"en"},{"value":"ja"}]}
                ]}
              ]
            }
            """.trimIndent(),
        )

        val set = buildAutomaticLanguageSettingsSet(
            schema = schema,
            currentSettingsJson =
                """{"localization":{"gameLanguage":"und","runtimeLocale":"system"},"ocr":{"sourceLanguage":"auto"}}""",
            detectedLanguage = "ja",
            suggestedRuntimeLocale = null,
        )

        assertEquals("ja", set.getJSONObject("localization").getString("gameLanguage"))
        assertEquals("ja_JP.UTF-8", set.getJSONObject("localization").getString("runtimeLocale"))
        assertEquals("ja", set.getJSONObject("ocr").getString("sourceLanguage"))
    }

    @Test
    fun ambiguousRuntimeLocaleIsNotGuessed() {
        assertNull(
            runtimeLocaleForLanguage(
                "zh",
                listOf("zh_CN.UTF-8", "zh_TW.UTF-8"),
            ),
        )
        assertEquals(
            "zh_TW.UTF-8",
            runtimeLocaleForLanguage(
                "zh-Hant",
                listOf("zh_CN.UTF-8", "zh_TW.UTF-8"),
            ),
        )
    }

    @Test
    fun unsupportedDetectedLanguageDoesNotSendUnknownSettingsValues() {
        val schema = WinlatorSettingsSchema.parse(
            """
            {
              "schemaVersion": 2,
              "namespaces": [
                {"key":"localization","description":"","fields":[
                  {"key":"runtimeLocale","wireType":"string","editor":"enum","default":"system",
                   "options":[{"value":"system"},{"value":"en_US.UTF-8"}]}
                ]},
                {"key":"ocr","description":"","fields":[
                  {"key":"sourceLanguage","wireType":"string","editor":"enum","default":"auto",
                   "options":[{"value":"auto"},{"value":"en"}]}
                ]}
              ]
            }
            """.trimIndent(),
        )

        val set = buildAutomaticLanguageSettingsSet(
            schema = schema,
            currentSettingsJson =
                """{"localization":{"runtimeLocale":"system"},"ocr":{"sourceLanguage":"auto"}}""",
            detectedLanguage = "ja",
            suggestedRuntimeLocale = null,
        )

        assertEquals(0, set.length())
    }

    @Test
    fun advertisedSchemaWithoutRuntimeLocaleDoesNotReceiveSuggestedLocale() {
        val schema = WinlatorSettingsSchema.parse(
            """
            {
              "schemaVersion": 2,
              "namespaces": [
                {"key":"localization","description":"","fields":[
                  {"key":"gameLanguage","wireType":"string","editor":"language_tag","default":"und"}
                ]}
              ]
            }
            """.trimIndent(),
        )

        val set = buildAutomaticLanguageSettingsSet(
            schema = schema,
            currentSettingsJson = """{"localization":{"gameLanguage":"und"}}""",
            detectedLanguage = null,
            suggestedRuntimeLocale = "ja_JP.UTF-8",
        )

        assertEquals(0, set.length())
    }

    @Test
    fun parsesAuthoritativeDiagnosticReportAndSuggestion() {
        val report = WinlatorDiagnosticReport.parse(
            """
            {
              "classificationVersion": 1,
              "reportId": "report-1",
              "sessionId": "session-1",
              "gameId": "game-1",
              "containerId": 4,
              "startedAt": 1000,
              "endedAt": 63000,
              "durationMillis": 62000,
              "outcome": "user_exit",
              "phase": "shutdown",
              "category": "user_exit",
              "confidence": "high",
              "runtimeReached": true,
              "configHealth": "good",
              "appliedConfigSha256": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "appliedConfig": {"box64Preset": "INTERMEDIATE"},
              "evidence": [{"source": "guest", "message": "clean shutdown"}],
              "suggestions": [{
                "id": "stability",
                "title": "Use stability",
                "rationale": "Compatibility",
                "baseConfigSha256": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "set": {"box64Preset": "STABILITY"}
              }]
            }
            """.trimIndent()
        )

        assertEquals("good", report.configHealth)
        assertTrue(report.runtimeReached)
        assertEquals("clean shutdown", report.evidence.single().message)
        assertEquals("STABILITY", JSONObject(report.suggestions.single().setJson).getString("box64Preset"))
        assertFalse(report.appliedConfigOmitted)
    }
}
