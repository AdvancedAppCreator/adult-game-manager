package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinlatorSettingsSchemaTest {
    private val sample = """
        {
          "schemaVersion": 2,
          "updateExtra": "settings_update_json",
          "namespaces": [
            {
              "key": "ocr",
              "scope": "game_and_global_default",
              "description": "On-screen translation",
              "fields": [
                { "key": "enabled", "wireType": "boolean", "editor": "boolean", "default": false,
                  "description": "Master gate" },
                { "key": "sourceLanguage", "wireType": "string", "editor": "enum", "default": "en",
                  "dependsOn": "enabled",
                  "options": [ {"value":"auto","label":"Auto-detect"}, {"value":"en","label":"English"},
                               {"value":"ja","label":"Japanese"} ] },
                { "key": "targetLanguage", "wireType": "string", "editor": "enum", "default": "en",
                  "dependsOn": "enabled",
                  "options": [ {"value":"original","label":"Original"}, {"value":"en","label":"English"} ] }
              ]
            },
            { "key": "localization", "description": "", "fields": [
                { "key": "runtimeLocale", "wireType": "string", "editor": "enum", "default": "C" } ] }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesNamespacesAndOcrFields() {
        val schema = WinlatorSettingsSchema.parse(sample)
        assertEquals(2, schema.schemaVersion)
        assertEquals(listOf("ocr", "localization"), schema.namespaces.map { it.key })

        val ocr = schema.namespace("ocr")!!
        assertEquals(listOf("enabled", "sourceLanguage", "targetLanguage"), ocr.fields.map { it.key })

        val enabled = ocr.fields.first { it.key == "enabled" }
        assertEquals("boolean", enabled.wireType)
        assertEquals(false, enabled.defaultValue)
        assertNull(enabled.dependsOn)

        val source = ocr.fields.first { it.key == "sourceLanguage" }
        assertEquals("enabled", source.dependsOn)
        assertEquals("en", source.defaultValue)
        assertTrue(source.options.any { it.value == "auto" && it.label == "Auto-detect" })
        assertTrue(source.options.any { it.value == "ja" && it.label == "Japanese" })
    }

    @Test
    fun missingNamespaceReturnsNull() {
        assertNull(WinlatorSettingsSchema.parse(sample).namespace("nope"))
    }
}
