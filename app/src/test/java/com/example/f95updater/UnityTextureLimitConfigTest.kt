package com.example.f95updater

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextureLimitConfigTest {
    @Test
    fun onlyBuildsConflictSafePatchWhenLiveSchemaAdvertisesFullContract() {
        val schema = WinlatorConfigSchema.parse(
            """
            {"schemaVersion":1,"fields":[
              {"key":"unityTextureLimit","wireType":"string","editor":"enum","default":"off",
               "options":[{"value":"off"},{"value":"1"},{"value":"2"},{"value":"3"}]}
            ]}
            """.trimIndent(),
        )
        val game = game("""{"unknownFuture":"keep","unityTextureLimit":"off"}""")

        assertTrue(UnityTextureLimitConfig.isAdvertised(schema))
        assertEquals("off", UnityTextureLimitConfig.currentValue(game))
        val submission = UnityTextureLimitConfig.buildSubmission(game, schema, "2")!!

        assertEquals(game.configSha256, submission.baseConfigSha256)
        assertEquals("2", JSONObject(submission.setJson).getString("unityTextureLimit"))
        assertEquals("keep", JSONObject(submission.afterJson).getString("unknownFuture"))
        assertEquals("2", JSONObject(submission.afterJson).getString("unityTextureLimit"))
        assertEquals("unity-texture-memory", submission.source)
    }

    @Test
    fun rejectsAbsentOrIncompleteSchemaAndAlreadyAppliedValue() {
        val incomplete = WinlatorConfigSchema.parse(
            """
            {"schemaVersion":1,"fields":[
              {"key":"unityTextureLimit","wireType":"string","editor":"enum","default":"off",
               "options":[{"value":"off"},{"value":"1"}]}
            ]}
            """.trimIndent(),
        )
        val game = game("""{"unityTextureLimit":"off"}""")

        assertFalse(UnityTextureLimitConfig.isAdvertised(incomplete))
        assertNull(UnityTextureLimitConfig.buildSubmission(game, incomplete, "1"))
        assertNull(UnityTextureLimitConfig.buildSubmission(game, null, "1"))

        val complete = WinlatorConfigSchema.parse(
            """
            {"schemaVersion":1,"fields":[
              {"key":"unityTextureLimit","wireType":"string","editor":"enum","default":"off",
               "options":[{"value":"off"},{"value":"1"},{"value":"2"},{"value":"3"}]}
            ]}
            """.trimIndent(),
        )
        assertNull(UnityTextureLimitConfig.buildSubmission(game, complete, "off"))
    }

    private fun game(config: String): WinlatorApi.ManagedGame = WinlatorApi.ManagedGame.parse(
        JSONObject(
            """
            {
              "id":"unity-fixture",
              "title":"Unity fixture",
              "state":"ready",
              "configJson":$config,
              "configSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """.trimIndent(),
        ),
    )
}
