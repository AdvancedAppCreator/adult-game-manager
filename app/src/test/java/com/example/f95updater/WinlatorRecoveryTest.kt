package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

class WinlatorRecoveryTest {
    @Test
    fun preflightQueriesExactGuidAndAcceptsMatchingRegistration() = runBlocking {
        var requested: String? = null
        val result = preflightWinlatorRegistration(
            registration(),
            WinlatorGameReader { gameId: String ->
                requested = gameId
                ok(actual())
            },
        )

        assertEquals("same-guid", requested)
        assertTrue(result is WinlatorRegistrationPreflight.Registered)
    }

    @Test
    fun preflightPreservesProviderAndTransportErrors() = runBlocking {
        val unauthorized = preflightWinlatorRegistration(
            registration(),
            WinlatorGameReader { _: String ->
                WinlatorClient.Read.Err("UNAUTHORIZED", "wrong signature")
            },
        )
        val unavailable = preflightWinlatorRegistration(
            registration(),
            WinlatorGameReader { _: String ->
                WinlatorClient.Read.Unavailable("provider missing")
            },
        )

        assertTrue(unauthorized is WinlatorRegistrationPreflight.ProviderError)
        assertTrue(unavailable is WinlatorRegistrationPreflight.Unavailable)
    }

    @Test
    fun secure76AlreadyExistsReconcilesThroughAuthoritativeGet() = runBlocking {
        val result = reconcileWinlatorPortableCreate(
            expected = registration(),
            operation = WinlatorApi.OperationResult.Failure(
                "GAME_ALREADY_EXISTS",
                "already exists",
            ),
            reader = WinlatorGameReader { _: String -> ok(actual()) },
        )

        assertTrue(result is WinlatorCreateReconciliation.Recovered)
        assertFalse((result as WinlatorCreateReconciliation.Recovered).createReconciled)
    }

    @Test
    fun secure77ReconciledSuccessRemainsOptional() = runBlocking {
        val result = reconcileWinlatorPortableCreate(
            expected = registration(),
            operation = success(createReconciled = true),
            reader = WinlatorGameReader { _: String -> ok(actual()) },
        )

        assertTrue((result as WinlatorCreateReconciliation.Recovered).createReconciled)
    }

    @Test
    fun timeoutCancelAndMissingResultRemainMissingWhenGetIsNotFound() = runBlocking {
        listOf("CANCELLED", "NO_RESULT").forEach { code ->
            val result = reconcileWinlatorPortableCreate(
                expected = registration(),
                operation = WinlatorApi.OperationResult.Failure(code, "ambiguous"),
                reader = WinlatorGameReader { _: String ->
                    WinlatorClient.Read.Err("GAME_NOT_FOUND", "missing")
                },
            )
            assertEquals(code, (result as WinlatorCreateReconciliation.Missing).operationCode)
        }
    }

    @Test
    fun prerequisiteFailureIsTypedAndDoesNotBecomeMissingRecovery() = runBlocking {
        val result = reconcileWinlatorPortableCreate(
            expected = registration(),
            operation = WinlatorApi.OperationResult.Failure(
                "ROOTFS_NOT_READY",
                "Complete setup first",
            ),
            reader = WinlatorGameReader { _: String ->
                WinlatorClient.Read.Err("GAME_NOT_FOUND", "missing")
            },
        )

        assertEquals(
            "ROOTFS_NOT_READY",
            (result as WinlatorCreateReconciliation.OperationRejected).code,
        )
    }

    @Test
    fun authoritativeIdentityMismatchIsNonDestructiveConflict() = runBlocking {
        val result = reconcileWinlatorPortableCreate(
            expected = registration(),
            operation = WinlatorApi.OperationResult.Failure(
                "GAME_ALREADY_EXISTS",
                "conflict",
                gameJson = gameJson(actual(executablePath = "/games/Other/Game.exe")),
            ),
            reader = WinlatorGameReader { _: String ->
                ok(actual(executablePath = "/games/Other/Game.exe"))
            },
        )

        assertTrue(result is WinlatorCreateReconciliation.Conflict)
    }

    @Test
    fun portableCandidateRequiresReadableExecutableInsideManagedRoot() {
        val root = createTempDir(prefix = "agm-recovery-")
        try {
            val executable = File(root, "Game.exe").apply { writeText("fixture") }
            val eligible = portableWinlatorRecoveryCandidate(app(root, executable))
            assertTrue(eligible is WinlatorPortableRecoveryEligibility.Eligible)

            executable.delete()
            assertTrue(
                portableWinlatorRecoveryCandidate(app(root, executable)) is
                    WinlatorPortableRecoveryEligibility.Ineligible,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun containerOwnedGameRequiresInstallerOrBackup() {
        val root = createTempDir(prefix = "agm-container-owned-")
        try {
            val result = portableWinlatorRecoveryCandidate(app(root, null))
            assertTrue(result is WinlatorPortableRecoveryEligibility.Ineligible)
            assertTrue(
                (result as WinlatorPortableRecoveryEligibility.Ineligible).message.contains("installer"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun containerOwnedPreflightRejectsDifferentDosExecutable() {
        val root = createTempDir(prefix = "agm-container-identity-")
        try {
            val app = app(root, null).copy(
                winlatorExecutableDosPath = "D:\\Games\\Test\\Game.exe",
            )
            val conflict = matchContainerOwnedWinlatorRegistration(
                app,
                actual().copy(
                    gamePath = null,
                    executablePath = null,
                    executableDosPath = "D:\\Games\\Other\\Game.exe",
                ),
            )

            assertTrue(conflict is WinlatorRegistrationIdentity.Conflict)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryConfigUsesFreshHashAndSupportedChangedFieldsOnly() {
        val root = createTempDir(prefix = "agm-config-recovery-")
        try {
            val executable = File(root, "Game.exe").apply { writeText("fixture") }
            val candidate = (
                portableWinlatorRecoveryCandidate(
                    app(
                        root,
                        executable,
                        configJson =
                            """{"screenSize":"1280x720","forceFullscreen":true,"removed":"x"}""",
                    ),
                ) as WinlatorPortableRecoveryEligibility.Eligible
                ).candidate
            val game = actual().copy(
                gamePath = root.absolutePath,
                executablePath = executable.absolutePath,
                configJson = """{"screenSize":"800x600","forceFullscreen":true}""",
                configSha256 = "FRESH",
            )
            val schema = WinlatorConfigSchema(
                schemaVersion = 1,
                fields = listOf(
                    configField("screenSize", "string"),
                    configField("forceFullscreen", "boolean"),
                ),
            )

            val submission = buildWinlatorRecoveryConfigSubmission(candidate, game, schema)

            assertNotNull(submission)
            assertEquals("FRESH", submission!!.baseConfigSha256)
            assertEquals(1, JSONObject(submission.setJson).length())
            assertTrue(
                isWinlatorRecoveryConfigApplied(
                    submission,
                    game.copy(configJson = submission.afterJson, configSha256 = "AFTER"),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun singleFlightRejectsRepeatedRecoveryTap() {
        val flights = WinlatorRecoverySingleFlight()
        assertTrue(flights.begin("managed-1"))
        assertFalse(flights.begin("managed-1"))
        flights.end("managed-1")
        assertTrue(flights.begin("managed-1"))
    }

    @Test
    fun recoveredBindingKeepsValidatedGuidAndAuthoritativeProviderState() {
        val recovered = recoveredWinlatorBinding(
            binding(),
            actual().copy(containerId = 12, configSha256 = "FRESH"),
        )

        assertEquals("same-guid", recovered.managedId)
        assertEquals(12, recovered.containerId)
        assertEquals("FRESH", recovered.configSha256)
        assertEquals("ready", recovered.state)
    }

    @Test
    fun missingPortableRegistrationPreservesDurableRecoveryData() {
        val original = binding(
            executablePath = "/storage/emulated/0/Games/Test/Game.exe",
            containerPolicy = "shared_default",
            containerKey = "agm.default",
        )

        val missing = markWinlatorRegistrationMissing(original)

        assertEquals("same-guid", missing.managedId)
        assertEquals(original.executablePath, missing.executablePath)
        assertEquals(original.configJson, missing.configJson)
        assertEquals("shared_default", missing.containerPolicy)
        assertEquals("agm.default", missing.containerKey)
        assertEquals("keep", missing.metadata["owner"])
        assertFalse(missing.metadata.containsKey("agmBoundAtMs"))
        assertEquals(WINLATOR_REGISTRATION_MISSING, missing.state)
    }

    @Test
    fun missingRegistrationClearsOnlyEphemeralProviderDataAndStaleHashes() {
        val missing = markWinlatorRegistrationMissing(binding())

        assertNull(missing.executableDosPath)
        assertNull(missing.containerId)
        assertNull(missing.containerReferenceCount)
        assertNull(missing.containerAllocatedSizeBytes)
        assertNull(missing.configSha256)
        assertNull(missing.effectiveWinVersion)
        assertNull(missing.winVersionSource)
    }

    @Test
    fun distinguishesPortableAndContainerOwnedRegistrations() {
        assertEquals(
            WinlatorRegistrationKind.PortableExternal,
            winlatorRegistrationKind(binding(executablePath = "/games/Test/Game.exe")),
        )
        assertEquals(
            WinlatorRegistrationKind.ContainerOwned,
            winlatorRegistrationKind(binding(executablePath = null)),
        )
    }

    @Test
    fun firstTimeUnregisteredBindingRemainsSetupRequired() {
        val unregistered = binding(
            managedId = null,
            state = "setup_required",
            containerId = null,
        )

        assertEquals(unregistered, markWinlatorRegistrationMissing(unregistered))
    }

    private fun binding(
        managedId: String? = "same-guid",
        executablePath: String? = "/storage/emulated/0/Games/Test/Game.exe",
        state: String = "ready",
        containerId: Int? = 4,
        containerPolicy: String? = "isolated",
        containerKey: String? = null,
    ) = ManagedRunnerBinding.Winlator(
        managedId = managedId,
        executablePath = executablePath,
        executableDosPath = "D:\\Test\\Game.exe",
        containerId = containerId,
        state = state,
        containerPolicy = containerPolicy,
        containerShared = containerPolicy == "shared_default",
        containerKey = containerKey,
        containerReferenceCount = 2,
        containerAllocatedSizeBytes = 3,
        configJson = """{"screenSize":"1280x720"}""",
        configSha256 = "OLD",
        effectiveWinVersion = "win10",
        winVersionSource = "default",
        metadata = mapOf("agmBoundAtMs" to "1", "owner" to "keep"),
    )

    private fun registration() = WinlatorPortableRegistration(
        gameId = "same-guid",
        title = "Test",
        gamePath = "/games/Test",
        executablePath = "/games/Test/Game.exe",
        containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault,
        containerKey = "agm.default",
        metadata = "{}",
    )

    private fun actual(
        executablePath: String = "/games/Test/Game.exe",
    ) = WinlatorApi.ManagedGame(
        id = "same-guid",
        title = "Test",
        containerId = 9,
        gamePath = "/games/Test",
        executablePath = executablePath,
        executableDosPath = null,
        state = "ready",
        containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault,
        containerShared = true,
        containerKey = "agm.default",
        containerReferenceCount = 1,
        containerAllocatedSizeBytes = 1,
        configJson = "{}",
        configSha256 = "HASH",
        effectiveWinVersion = "win10",
        winVersionSource = "default",
        createdAt = 1,
        updatedAt = 2,
        metadata = null,
    )

    private fun ok(game: WinlatorApi.ManagedGame) = WinlatorClient.Read.Ok(
        payload = gameJson(game),
        apiVersion = 7,
        hasMore = false,
        nextOffset = 0,
    )

    private fun gameJson(game: WinlatorApi.ManagedGame) =
        """{
          "id":"${game.id}",
          "title":"${game.title}",
          "containerId":${game.containerId},
          "gamePath":"${game.gamePath}",
          "executablePath":"${game.executablePath}",
          "state":"${game.state}",
          "containerPolicy":"${game.containerPolicy?.wireValue}",
          "containerShared":${game.containerShared},
          "containerKey":"${game.containerKey}",
          "configJson":${game.configJson},
          "configSha256":"${game.configSha256}",
          "createdAt":${game.createdAt},
          "updatedAt":${game.updatedAt}
        }""".trimIndent()

    private fun success(createReconciled: Boolean) = WinlatorApi.OperationResult.Success(
        gameId = "same-guid",
        containerId = 9,
        gameJson = null,
        createReconciled = createReconciled,
        launchDeferred = false,
        containerDeleted = false,
        containerPreserved = false,
        containerReferenceCount = 1,
    )

    private fun app(
        root: File,
        executable: File?,
        configJson: String? = """{"screenSize":"1280x720"}""",
    ) = InstalledApp(
        packageName = "managed:managed-1",
        label = "Test",
        versionName = "",
        versionCode = 0,
        source = AppSource.Managed,
        managedGameId = "managed-1",
        managedDefaultRunner = ManagedRunnerKind.Winlator,
        managedRunnerBindings = listOf(
            binding(
                executablePath = executable?.absolutePath,
                containerPolicy = "shared_default",
                containerKey = "agm.default",
            ),
        ),
        storagePath = root.absolutePath,
        winlatorGameId = "same-guid",
        winlatorExecutablePath = executable?.absolutePath,
        winlatorContainerPolicy = "shared_default",
        winlatorContainerShared = true,
        winlatorContainerKey = "agm.default",
        winlatorConfigJson = configJson,
    )

    private fun configField(key: String, wireType: String) = WinlatorConfigField(
        key = key,
        wireType = wireType,
        editor = "text",
        defaultValue = "",
        description = "",
        options = emptyList(),
        dependsOn = null,
        format = null,
    )
}
