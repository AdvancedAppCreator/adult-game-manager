package com.example.f95updater

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerRecommendationTest {
    @Test
    fun parsesRgssJoiPlayRecommendation() {
        val rec = RunnerRecommendation.parse(
            JSONObject(
                """
                {
                  "winlatorSuitability": "poor",
                  "nativeEngineInterpreterPreferred": true,
                  "preferredRunner": "joiplay",
                  "confidence": "high",
                  "reasons": ["RPG Maker VX Ace runs natively in JoiPlay"],
                  "knownIssues": ["wine-mmdevapi-audio-deadlock"]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("poor", rec.winlatorSuitability)
        assertTrue(rec.nativeEngineInterpreterPreferred)
        assertEquals("joiplay", rec.preferredRunner)
        assertEquals("high", rec.confidence)
        assertEquals(listOf("RPG Maker VX Ace runs natively in JoiPlay"), rec.reasons)
        assertEquals(listOf("wine-mmdevapi-audio-deadlock"), rec.knownIssues)
        assertTrue(rec.prefersJoiPlay)
    }

    @Test
    fun prefersJoiPlayWhenNativePreferredAndFairEvenIfRunnerEither() {
        val rec = RunnerRecommendation.parse(
            JSONObject("""{"winlatorSuitability":"fair","nativeEngineInterpreterPreferred":true,"preferredRunner":"either"}"""),
        )
        assertTrue(rec.prefersJoiPlay)
    }

    @Test
    fun doesNotPreferJoiPlayForNativeWindowsGame() {
        val rec = RunnerRecommendation.parse(
            JSONObject("""{"winlatorSuitability":"ideal","nativeEngineInterpreterPreferred":false,"preferredRunner":"winlator"}"""),
        )
        assertFalse(rec.prefersJoiPlay)
        assertEquals("either", RunnerRecommendation.parse(JSONObject("{}")).preferredRunner)
    }

    @Test
    fun suggestedConfigParsesRunnerRecommendationIndependentOfSuggestions() {
        val s = WinlatorSuggestedConfig.parse(
            """
            {
              "gameId": "g1",
              "engine": "rpgvxace",
              "engineLabel": "RPG Maker VX Ace",
              "confidence": "high",
              "suggestedConfig": {},
              "suggestedSettings": {},
              "runnerRecommendation": {
                "winlatorSuitability": "poor",
                "nativeEngineInterpreterPreferred": true,
                "preferredRunner": "joiplay",
                "confidence": "high",
                "reasons": ["native RGSS interpreter"],
                "knownIssues": ["wine-mmdevapi-audio-deadlock"]
              }
            }
            """.trimIndent(),
        )
        // Empty suggestions, but the runner recommendation is still present.
        assertFalse(s.hasSuggestions)
        assertNotNull(s.runnerRecommendation)
        assertTrue(s.runnerRecommendation!!.prefersJoiPlay)
    }

    @Test
    fun suggestedConfigMissingRunnerRecommendationIsNull() {
        val s = WinlatorSuggestedConfig.parse(
            """{"gameId":"g1","engine":"unity","suggestedConfig":{},"suggestedSettings":{}}""",
        )
        assertNull(s.runnerRecommendation)
    }

    private fun assertNotNull(value: Any?) = assertTrue("expected non-null", value != null)
}
