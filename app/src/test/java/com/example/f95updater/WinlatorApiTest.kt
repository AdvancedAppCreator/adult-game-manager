package com.example.f95updater

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinlatorApiTest {
    @Test
    fun portableRecoveryCreateUsesCallerGuidAndNeverAutoLaunches() {
        val extras = WinlatorApi.portableCreateExtras(
            gameId = "same-guid",
            title = "Test",
            gamePath = "/games/Test",
            executablePath = "/games/Test/Game.exe",
            metadata = "{}",
            containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault,
        )

        assertEquals("same-guid", extras["game_id"])
        assertEquals(false, extras["launch_after_create"])
        assertEquals("shared_default", extras["container_policy"])
    }

    @Test
    fun parsesOptionalCreateReconciledWithoutRequiringIt() {
        val legacy = WinlatorApi.operationResult(
            resultCode = android.app.Activity.RESULT_OK,
            success = true,
            gameId = "g1",
        ) as WinlatorApi.OperationResult.Success
        val reconciled = WinlatorApi.operationResult(
            resultCode = android.app.Activity.RESULT_OK,
            success = true,
            gameId = "g1",
            createReconciled = true,
        ) as WinlatorApi.OperationResult.Success

        assertFalse(legacy.createReconciled)
        assertTrue(reconciled.createReconciled)
    }

    @Test
    fun parsesApiV3ContainerCapabilities() {
        val capabilities = WinlatorApi.Capabilities.parse(
            """
            {
              "apiVersion": 3,
              "supportsPortableGames": true,
              "supportsWindowsInstallers": true,
              "asyncInstallers": true,
              "installerProgress": true,
              "sameProfilePaths": true,
              "safUri": false,
              "sharedContainers": true,
              "sharedDefaultContainer": true,
              "defaultContainerPolicy": "isolated",
              "defaultSharedContainerKey": "agm.default",
              "moveGameToIsolated": true,
              "referenceSafeContainerDeletion": true,
              "managedGameConfiguration": true,
              "gameConfigSchemaVersion": 1,
              "configSchemaPath": "config-schema",
              "configConflictDetection": true,
              "configUpdateExtra": "config_update_json",
              "managedGameSettings": true,
              "gameSettingsSchemaVersion": 1,
              "settingsSchemaPath": "settings-schema",
              "settingsConflictDetection": true,
              "settingsUpdateExtra": "settings_update_json",
              "managedDiagnostics": true,
              "diagnosticsSchemaVersion": 1,
              "diagnosticHistory": true,
              "diagnosticHistoryPerGame": 20,
              "gameEventDiagnosticRefs": true,
              "diagnosticsPath": "diagnostics"
            }
            """.trimIndent()
        )

        assertEquals(3, capabilities.apiVersion)
        assertTrue(capabilities.supportsPortableGames)
        assertTrue(capabilities.supportsWindowsInstallers)
        assertTrue(capabilities.asyncInstallers)
        assertTrue(capabilities.installerProgress)
        assertTrue(capabilities.sameProfilePaths)
        assertFalse(capabilities.safUri)
        assertTrue(capabilities.sharedContainers)
        assertTrue(capabilities.sharedDefaultContainer)
        assertEquals("isolated", capabilities.defaultContainerPolicy)
        assertEquals("agm.default", capabilities.defaultSharedContainerKey)
        assertTrue(capabilities.moveGameToIsolated)
        assertTrue(capabilities.referenceSafeContainerDeletion)
        assertTrue(capabilities.managedGameConfiguration)
        assertEquals(1, capabilities.gameConfigSchemaVersion)
        assertEquals("config-schema", capabilities.configSchemaPath)
        assertTrue(capabilities.configConflictDetection)
        assertEquals("config_update_json", capabilities.configUpdateExtra)
        assertTrue(capabilities.managedGameSettings)
        assertEquals(1, capabilities.gameSettingsSchemaVersion)
        assertEquals("settings-schema", capabilities.settingsSchemaPath)
        assertTrue(capabilities.settingsConflictDetection)
        assertEquals("settings_update_json", capabilities.settingsUpdateExtra)
        assertTrue(capabilities.managedDiagnostics)
        assertEquals(1, capabilities.diagnosticsSchemaVersion)
        assertTrue(capabilities.diagnosticHistory)
        assertEquals(20, capabilities.diagnosticHistoryPerGame)
        assertTrue(capabilities.gameEventDiagnosticRefs)
        assertEquals("diagnostics", capabilities.diagnosticsPath)
    }

    @Test
    fun parsesSecure46Capabilities() {
        val caps = WinlatorApi.Capabilities.parse(
            """
            {
              "apiVersion": 4,
              "perGameControlsProfile": true,
              "configSuggestions": true,
              "configSuggestionsPath": "suggested-config"
            }
            """.trimIndent()
        )
        assertTrue(caps.perGameControlsProfile)
        assertTrue(caps.configSuggestions)
        assertEquals("suggested-config", caps.configSuggestionsPath)
    }

    @Test
    fun secure46CapabilitiesDefaultOffWhenAbsent() {
        val caps = WinlatorApi.Capabilities.parse("""{ "apiVersion": 4 }""")
        assertFalse(caps.perGameControlsProfile)
        assertFalse(caps.configSuggestions)
        assertNull(caps.configSuggestionsPath)
    }

    @Test
    fun parsesSuggestedConfig() {
        val suggested = WinlatorSuggestedConfig.parse(
            """
            {
              "gameId": "g1",
              "engine": "rpgmaker_mvmz",
              "engineLabel": "RPG Maker MV/MZ",
              "confidence": "high",
              "architecture": "x86",
              "evidence": ["nw.js bundle", {"source": "pe", "message": "32-bit"}],
              "suggestedConfig": {"launchArguments": "--disable-gpu --in-process-gpu", "audioDriver": "silent"},
              "rationale": ["Chromium swapchain race"]
            }
            """.trimIndent()
        )
        assertEquals("RPG Maker MV/MZ", suggested.engineLabel)
        assertEquals("high", suggested.confidence)
        assertEquals("x86", suggested.architecture)
        assertTrue(suggested.hasSuggestions)
        assertEquals("--disable-gpu --in-process-gpu", suggested.suggestedConfig.getString("launchArguments"))
        assertEquals(2, suggested.evidence.size)
        assertEquals("[pe] 32-bit", suggested.evidence[1])
        assertEquals(1, suggested.rationale.size)
    }

    @Test
    fun parsesSuggestedSettingsSiblingAndLocale() {
        val suggested = WinlatorSuggestedConfig.parse(
            """
            {
              "gameId": "g3",
              "engine": "kirikiri",
              "engineLabel": "KiriKiri",
              "confidence": "high",
              "architecture": "x86",
              "suggestedConfig": {"graphicsDriver": "turnip,virgl"},
              "suggestedSettings": {"localization": {"runtimeLocale": "ja_JP.UTF-8"}}
            }
            """.trimIndent()
        )
        assertTrue(suggested.hasSuggestions)
        assertEquals("turnip,virgl", suggested.suggestedConfig.getString("graphicsDriver"))
        assertEquals("ja_JP.UTF-8", suggested.suggestedRuntimeLocale)
    }

    @Test
    fun suggestedSettingsOnlyStillHasSuggestions() {
        val suggested = WinlatorSuggestedConfig.parse(
            """{"gameId":"g4","confidence":"high","suggestedConfig":{},"suggestedSettings":{"localization":{"runtimeLocale":"ko_KR.UTF-8"}}}"""
        )
        assertTrue(suggested.hasSuggestions)
        assertEquals("ko_KR.UTF-8", suggested.suggestedRuntimeLocale)
    }

    @Test
    fun suggestedConfigUnknownEngineHasNoSuggestions() {
        val suggested = WinlatorSuggestedConfig.parse(
            """{"gameId":"g2","engine":"unknown","confidence":"low","architecture":"unknown","suggestedConfig":{}}"""
        )
        assertFalse(suggested.hasSuggestions)
        assertEquals("unknown", suggested.engineLabel)
        assertNull(suggested.suggestedRuntimeLocale)
    }

    @Test
    fun managedGameBecomesStableWinlatorLibraryEntry() {
        val games = WinlatorApi.ManagedGame.parseList(
            """
            [{
              "id": "agm-game-id",
              "title": "Test Game",
              "containerId": 4,
              "containerPolicy": "shared_default",
              "containerKey": "agm.default",
              "containerShared": true,
              "containerReferenceCount": 3,
              "containerAllocatedSizeBytes": 343932928,
              "configJson": {"screenSize":"1280x720","forceFullscreen":false},
              "configSha256": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "effectiveWinVersion": "win10",
              "winVersionSource": "default",
              "gamePath": "/storage/emulated/0/Games/Test",
              "executablePath": "/storage/emulated/0/Games/Test/Game.exe",
              "agm_metadata": "{\"version\":\"0.9.4\"}",
              "state": "ready",
              "createdAt": 100,
              "updatedAt": 200
            }]
            """.trimIndent()
        )

        val app = games.single().toInstalledApp()
        assertEquals("winlator:agm-game-id", app.packageName)
        assertEquals("Test Game", app.label)
        assertEquals("0.9.4", app.versionName)
        assertEquals(AppSource.Winlator, app.source)
        assertEquals("agm-game-id", app.winlatorGameId)
        assertEquals(4, app.winlatorContainerId)
        assertEquals("ready", app.winlatorState)
        assertEquals("shared_default", app.winlatorContainerPolicy)
        assertTrue(app.winlatorContainerShared)
        assertEquals("agm.default", app.winlatorContainerKey)
        assertEquals(3, app.winlatorContainerReferenceCount)
        assertEquals(343932928L, app.winlatorContainerAllocatedSizeBytes)
        assertEquals("1280x720", JSONObject(app.winlatorConfigJson!!).getString("screenSize"))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", app.winlatorConfigSha256)
        assertEquals("win10", app.winlatorEffectiveWinVersion)
        assertEquals("default", app.winlatorWinVersionSource)
    }

    @Test
    fun managedGameWinVersionProvenanceIsOptionalForOlderPayloads() {
        val game = WinlatorApi.ManagedGame.parse(
            JSONObject("""{"id":"old","state":"ready","createdAt":1,"updatedAt":2}""")
        )

        assertNull(game.effectiveWinVersion)
        assertNull(game.winVersionSource)
    }

    @Test
    fun installerMetadataRetainsSourceFolderForLibraryDeduplication() {
        val game = WinlatorApi.ManagedGame.parseList(
            """
            [{
              "id": "installer-game",
              "title": "Installer Game",
              "containerId": 8,
              "agm_metadata": "{\"sourcePath\":\"/storage/emulated/0/Games/Installer Game/setup.exe\"}",
              "state": "installing",
              "createdAt": 100,
              "updatedAt": 200
            }]
            """.trimIndent()
        ).single()

        val app = game.toInstalledApp()

        assertEquals("/storage/emulated/0/Games/Installer Game", app.storagePath)
        assertEquals("Installer Game", app.storageFolderName)
    }
}
