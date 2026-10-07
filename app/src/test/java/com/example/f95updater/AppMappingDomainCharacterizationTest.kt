package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT behavior of the pure domain helpers in
 * AppMappingDomain.kt: statusOrder, installedVersionFingerprint, effectiveInstalledVersion/Date,
 * hasActiveManualInstalledVersion/Date, catalogInstalledDateCandidate, localIdentityTokens, and the
 * AppMapping extension functions that build/read catalog association snapshots. These tests
 * intentionally document existing behavior, including any quirks, rather than an ideal behavior -
 * this is a behavior-preserving refactor.
 */
class AppMappingDomainCharacterizationTest {

    // ---------------------------------------------------------------------
    // Fixture factories
    // ---------------------------------------------------------------------

    private fun app(
        packageName: String = "pkg.test",
        label: String = "Label",
        launcherLabel: String? = null,
        readmeTitle: String? = null,
        versionName: String = "1.0",
        versionCode: Long = 1L,
        firstInstallTime: Long = 0L,
        lastUpdateTime: Long = 0L,
        source: AppSource = AppSource.Android,
        storagePath: String? = null,
        storageFolderName: String? = null,
        joiPlayGameId: String? = null,
        joiPlayExecFile: String? = null,
    ) = InstalledApp(
        packageName = packageName,
        label = label,
        launcherLabel = launcherLabel,
        readmeTitle = readmeTitle,
        versionName = versionName,
        versionCode = versionCode,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        source = source,
        storagePath = storagePath,
        storageFolderName = storageFolderName,
        joiPlayGameId = joiPlayGameId,
        joiPlayExecFile = joiPlayExecFile,
    )

    private fun mapping(packageName: String = "pkg.test") = AppMapping(packageName = packageName)

    private fun catalogGame(threadId: Int = 1) = CatalogGame(thread_id = threadId)

    private fun externalResult(
        title: String = "Some Game",
        mirrorUrl: String = "https://example.test/mirror",
        officialUrl: String? = null,
        threadId: Int? = null,
        version: String? = null,
        sourceHost: String = "",
    ) = ExternalMirrorResult(
        title = title,
        mirrorUrl = mirrorUrl,
        officialUrl = officialUrl,
        threadId = threadId,
        version = version,
        sourceHost = sourceHost,
    )

    // ---------------------------------------------------------------------
    // statusOrder
    // ---------------------------------------------------------------------

    @Test
    fun statusOrder_pinsIntegerForEachValue() {
        assertEquals(0, statusOrder(UpdateStatus.UpdateAvailable))
        assertEquals(1, statusOrder(UpdateStatus.Unknown))
        assertEquals(2, statusOrder(UpdateStatus.CheckFailed))
        assertEquals(3, statusOrder(UpdateStatus.UpToDate))
        assertEquals(4, statusOrder(UpdateStatus.NotMapped))
    }

    // ---------------------------------------------------------------------
    // installedVersionFingerprint
    // ---------------------------------------------------------------------

    @Test
    fun installedVersionFingerprint_joinsAllFieldsInOrder() {
        val a = app(
            versionName = "2.1.0",
            versionCode = 7L,
            lastUpdateTime = 123456L,
            storagePath = "/root/Game",
            storageFolderName = "GameFolder",
            joiPlayGameId = "gid-9",
            source = AppSource.JoiPlay,
        )

        assertEquals(
            "JoiPlay|2.1.0|7|123456|/root/Game|GameFolder|gid-9",
            installedVersionFingerprint(a),
        )
    }

    @Test
    fun installedVersionFingerprint_orEmptyForNullOptionalFields() {
        val a = app(versionName = "1.0", versionCode = 1L, lastUpdateTime = 0L, source = AppSource.Android)

        assertEquals("Android|1.0|1|0|||", installedVersionFingerprint(a))
    }

    // ---------------------------------------------------------------------
    // effectiveInstalledVersion
    // ---------------------------------------------------------------------

    @Test
    fun effectiveInstalledVersion_nullMapping_returnsAppVersion() {
        val a = app(versionName = "3.0")

        assertEquals("3.0", effectiveInstalledVersion(a, null))
    }

    @Test
    fun effectiveInstalledVersion_blankManualVersion_returnsAppVersion() {
        val a = app(versionName = "3.0")
        val m = mapping().copy(manualInstalledVersion = "   ")

        assertEquals("3.0", effectiveInstalledVersion(a, m))
    }

    @Test
    fun effectiveInstalledVersion_manualWithBlankFingerprint_returnsTrimmedManual() {
        val a = app(versionName = "3.0")
        val m = mapping().copy(manualInstalledVersion = "  4.0  ", manualInstalledVersionFingerprint = "")

        // The manual value is trimmed before being returned/compared.
        assertEquals("4.0", effectiveInstalledVersion(a, m))
    }

    @Test
    fun effectiveInstalledVersion_manualWithMatchingFingerprint_returnsManual() {
        val a = app(versionName = "3.0")
        val fp = installedVersionFingerprint(a)
        val m = mapping().copy(manualInstalledVersion = "4.0", manualInstalledVersionFingerprint = fp)

        assertEquals("4.0", effectiveInstalledVersion(a, m))
    }

    @Test
    fun effectiveInstalledVersion_manualWithNonMatchingFingerprint_returnsAppVersion() {
        val a = app(versionName = "3.0")
        val m = mapping().copy(manualInstalledVersion = "4.0", manualInstalledVersionFingerprint = "stale-fingerprint")

        assertEquals("3.0", effectiveInstalledVersion(a, m))
    }

    // ---------------------------------------------------------------------
    // hasActiveManualInstalledVersion
    // ---------------------------------------------------------------------

    @Test
    fun hasActiveManualInstalledVersion_falseWhenMappingNull() {
        assertFalse(hasActiveManualInstalledVersion(app(), null))
    }

    @Test
    fun hasActiveManualInstalledVersion_falseWhenManualBlank() {
        val a = app()
        val m = mapping().copy(manualInstalledVersion = "", manualInstalledVersionFingerprint = installedVersionFingerprint(a))

        assertFalse(hasActiveManualInstalledVersion(a, m))
    }

    @Test
    fun hasActiveManualInstalledVersion_trueWhenNonBlankAndFingerprintMatches() {
        val a = app()
        val m = mapping().copy(manualInstalledVersion = "4.0", manualInstalledVersionFingerprint = installedVersionFingerprint(a))

        assertTrue(hasActiveManualInstalledVersion(a, m))
    }

    @Test
    fun hasActiveManualInstalledVersion_falseWhenFingerprintMismatches() {
        val a = app()
        val m = mapping().copy(manualInstalledVersion = "4.0", manualInstalledVersionFingerprint = "stale")

        assertFalse(hasActiveManualInstalledVersion(a, m))
    }

    @Test
    fun hasActiveManualInstalledVersion_falseWhenFingerprintBlank_unlikeEffectiveInstalledVersion() {
        // Surprising divergence from effectiveInstalledVersion: a blank fingerprint makes
        // effectiveInstalledVersion treat the manual value as always-active, but
        // hasActiveManualInstalledVersion requires an EXACT match against the real fingerprint
        // (which is never blank, since it always contains "|" separators), so this is false here.
        val a = app()
        val m = mapping().copy(manualInstalledVersion = "4.0", manualInstalledVersionFingerprint = "")

        assertFalse(hasActiveManualInstalledVersion(a, m))
        assertEquals("4.0", effectiveInstalledVersion(a, m))
    }

    // ---------------------------------------------------------------------
    // effectiveInstalledDate
    // ---------------------------------------------------------------------

    @Test
    fun effectiveInstalledDate_nullMapping_returnsFirstInstallTime() {
        val a = app(firstInstallTime = 1000L)

        assertEquals(1000L, effectiveInstalledDate(a, null))
    }

    @Test
    fun effectiveInstalledDate_manualDateZeroOrNegative_returnsFirstInstallTime() {
        val a = app(firstInstallTime = 1000L)
        val m = mapping().copy(manualInstalledDate = 0L)

        assertEquals(1000L, effectiveInstalledDate(a, m))
    }

    @Test
    fun effectiveInstalledDate_manualWithBlankFingerprint_returnsManual() {
        val a = app(firstInstallTime = 1000L)
        val m = mapping().copy(manualInstalledDate = 2000L, manualInstalledDateFingerprint = "")

        assertEquals(2000L, effectiveInstalledDate(a, m))
    }

    @Test
    fun effectiveInstalledDate_manualWithMatchingFingerprint_returnsManual() {
        val a = app(firstInstallTime = 1000L)
        val fp = installedVersionFingerprint(a)
        val m = mapping().copy(manualInstalledDate = 2000L, manualInstalledDateFingerprint = fp)

        assertEquals(2000L, effectiveInstalledDate(a, m))
    }

    @Test
    fun effectiveInstalledDate_manualWithNonMatchingFingerprint_returnsFirstInstallTime() {
        val a = app(firstInstallTime = 1000L)
        val m = mapping().copy(manualInstalledDate = 2000L, manualInstalledDateFingerprint = "stale")

        assertEquals(1000L, effectiveInstalledDate(a, m))
    }

    // ---------------------------------------------------------------------
    // hasActiveManualInstalledDate
    // ---------------------------------------------------------------------

    @Test
    fun hasActiveManualInstalledDate_falseWhenMappingNull() {
        assertFalse(hasActiveManualInstalledDate(app(), null))
    }

    @Test
    fun hasActiveManualInstalledDate_falseWhenDateZero() {
        val a = app()
        val m = mapping().copy(manualInstalledDate = 0L, manualInstalledDateFingerprint = installedVersionFingerprint(a))

        assertFalse(hasActiveManualInstalledDate(a, m))
    }

    @Test
    fun hasActiveManualInstalledDate_trueWhenPositiveAndFingerprintMatches() {
        val a = app()
        val m = mapping().copy(manualInstalledDate = 2000L, manualInstalledDateFingerprint = installedVersionFingerprint(a))

        assertTrue(hasActiveManualInstalledDate(a, m))
    }

    @Test
    fun hasActiveManualInstalledDate_falseWhenFingerprintMismatches() {
        val a = app()
        val m = mapping().copy(manualInstalledDate = 2000L, manualInstalledDateFingerprint = "stale")

        assertFalse(hasActiveManualInstalledDate(a, m))
    }

    @Test
    fun hasActiveManualInstalledDate_falseWhenFingerprintBlank_unlikeEffectiveInstalledDate() {
        // Same divergence as the version case: blank fingerprint means "always active" for
        // effectiveInstalledDate but never matches the real (never-blank) fingerprint here.
        val a = app()
        val m = mapping().copy(manualInstalledDate = 2000L, manualInstalledDateFingerprint = "")

        assertFalse(hasActiveManualInstalledDate(a, m))
        assertEquals(2000L, effectiveInstalledDate(a, m))
    }

    // ---------------------------------------------------------------------
    // catalogInstalledDateCandidate
    // ---------------------------------------------------------------------

    @Test
    fun catalogInstalledDateCandidate_nullGame_returnsNull() {
        assertNull(catalogInstalledDateCandidate(null))
    }

    @Test
    fun catalogInstalledDateCandidate_publishedAtTakesPriority() {
        val g = catalogGame().copy(publishedAt = 5L, modifiedAt = 6L, ts = 7L)

        assertEquals(5000L to "catalog published date", catalogInstalledDateCandidate(g))
    }

    @Test
    fun catalogInstalledDateCandidate_modifiedAtUsedWhenNoPublishedAt() {
        val g = catalogGame().copy(publishedAt = 0L, modifiedAt = 6L, ts = 7L)

        assertEquals(6000L to "catalog modified date", catalogInstalledDateCandidate(g))
    }

    @Test
    fun catalogInstalledDateCandidate_tsUsedWhenNoPublishedOrModifiedAt() {
        val g = catalogGame().copy(publishedAt = 0L, modifiedAt = 0L, ts = 7L)

        assertEquals(7000L to "catalog update date", catalogInstalledDateCandidate(g))
    }

    @Test
    fun catalogInstalledDateCandidate_allZero_returnsNull() {
        val g = catalogGame().copy(publishedAt = 0L, modifiedAt = 0L, ts = 0L)

        assertNull(catalogInstalledDateCandidate(g))
    }

    @Test
    fun catalogInstalledDateCandidate_negativeValuesTreatedAsAbsent() {
        val g = catalogGame().copy(publishedAt = -1L, modifiedAt = -1L, ts = 5L)

        assertEquals(5000L to "catalog update date", catalogInstalledDateCandidate(g))
    }

    // ---------------------------------------------------------------------
    // localIdentityTokens
    // ---------------------------------------------------------------------

    @Test
    fun localIdentityTokens_assemblesNormalizesFiltersIgnoredAndDedupes() {
        val a = app(
            packageName = "com.example.mygame",
            label = "My Game Title",
            storagePath = "/root/ParentFolder/MyGameFolder",
            joiPlayGameId = "gid123",
            joiPlayExecFile = "game.exe",
        )

        // raw tokens (pre-filter): packageName, catalogMatchLabels ["My Game Title",
        // "MyGameFolder"], joiPlayGameId, pathBase("MyGameFolder"), pathParent("ParentFolder"),
        // execBase("game"). normalizeTitle lowercases + strips non-alphanumerics; "game"
        // (from execBase) is in the ignore set and dropped; the duplicate "mygamefolder"
        // (basename appears both via catalogMatchLabels and pathBase) is deduped via distinct().
        assertEquals(
            listOf("comexamplemygame", "mygametitle", "mygamefolder", "gid123", "parentfolder"),
            localIdentityTokens(a),
        )
    }

    @Test
    fun localIdentityTokens_ignoresGameWwwAppSrcResourcesWords() {
        val a = app(
            packageName = "uniqueAnchor",
            label = "Game",
            launcherLabel = "WWW",
            readmeTitle = "APP",
            storageFolderName = "SRC",
            joiPlayGameId = "Resources",
        )

        assertEquals(listOf("uniqueanchor"), localIdentityTokens(a))
    }

    @Test
    fun localIdentityTokens_ignoresJoiplayAndAndroidWords() {
        val a = app(
            packageName = "anchorPkg",
            label = "L",
            storagePath = "/root/JoiPlay",
            joiPlayExecFile = "Android.exe",
        )

        // pathBase "JoiPlay" -> ignored; execBase "Android" -> ignored; pathParent "root" ->
        // kept (not ignored, length >= 3); label "L" -> filtered by length (< 3).
        assertEquals(listOf("anchorpkg", "root"), localIdentityTokens(a))
    }

    @Test
    fun localIdentityTokens_dropsTokensShorterThanThreeChars() {
        val a = app(packageName = "anchorX", label = "L1", joiPlayGameId = "ab")

        assertEquals(listOf("anchorx"), localIdentityTokens(a))
    }

    @Test
    fun localIdentityTokens_keepsTokensAtLengthThreeBoundary() {
        val a = app(packageName = "anchorX", label = "L1", joiPlayGameId = "abc")

        assertEquals(listOf("anchorx", "abc"), localIdentityTokens(a))
    }

    // ---------------------------------------------------------------------
    // AppMapping.withPersonalFieldsFrom
    // ---------------------------------------------------------------------

    @Test
    fun withPersonalFieldsFrom_nullExisting_returnsSameInstance() {
        val receiver = mapping().copy(displayNameOverride = "Recv Display")

        val result = receiver.withPersonalFieldsFrom(null)

        assertSame(receiver, result)
    }

    @Test
    fun withPersonalFieldsFrom_copiesOnlyPersonalFields_keepsReceiverAssociation() {
        val receiver = AppMapping(
            packageName = "recv.pkg",
            f95Url = "https://recv.example/",
            lastSeenVersion = "9.9.9recv",
            lastChecked = 111L,
            acknowledgedVersion = "9.9.9ack-recv",
            threadId = 42,
            notOnF95 = true,
            matchSource = "recvSource",
            userStatus = UserGameStatus.Playing,
            personalRating = 3,
            personalNotes = "recvNotes",
            manualCorrectionNote = "recvCorrection",
            manualInstalledVersion = "recvManualVer",
            manualInstalledVersionFingerprint = "recvVerFp",
            manualInstalledDate = 222L,
            manualInstalledDateFingerprint = "recvDateFp",
            manualInstalledDateSource = "recvDateSrc",
            manualLocalIdentity = listOf("recvTok"),
            mappedCatalogId = 5,
            mappedCatalogSource = "recvSrc",
            mappedCatalogSourceId = "recvSrcId",
            mappedAgmGroupId = "recvGroup",
            mappedCatalogTitle = "recvTitle",
            mappedCatalogVersion = "recvVer",
            mappedCatalogUrl = "recvUrl",
            mappedCatalogUpdatedAt = 333L,
            mappedCatalogPublishedAt = 444L,
            mappedCatalogModifiedAt = 555L,
            mappedCatalogCoverUrl = "recvCover",
            mappedCatalogThumbnailUrl = "recvThumb",
            displayNameOverride = "recvDisplay",
        )
        val existing = AppMapping(
            packageName = "exist.pkg",
            f95Url = "https://exist.example/",
            lastSeenVersion = "1.1.1exist",
            lastChecked = 999L,
            acknowledgedVersion = "1.1.1ack-exist",
            threadId = 99,
            notOnF95 = false,
            matchSource = "existSource",
            userStatus = UserGameStatus.Completed,
            personalRating = 5,
            personalNotes = "existNotes",
            manualCorrectionNote = "existCorrection",
            manualInstalledVersion = "existManualVer",
            manualInstalledVersionFingerprint = "existVerFp",
            manualInstalledDate = 777L,
            manualInstalledDateFingerprint = "existDateFp",
            manualInstalledDateSource = "existDateSrc",
            manualLocalIdentity = listOf("existTok"),
            mappedCatalogId = 100,
            mappedCatalogSource = "existSrc",
            mappedCatalogSourceId = "existSrcId",
            mappedAgmGroupId = "existGroup",
            mappedCatalogTitle = "existTitle",
            mappedCatalogVersion = "existVer",
            mappedCatalogUrl = "existUrl",
            mappedCatalogUpdatedAt = 888L,
            mappedCatalogPublishedAt = 889L,
            mappedCatalogModifiedAt = 890L,
            mappedCatalogCoverUrl = "existCover",
            mappedCatalogThumbnailUrl = "existThumb",
            displayNameOverride = "existDisplay",
        )

        val result = receiver.withPersonalFieldsFrom(existing)

        // Kept from the receiver ("this"): identity, refresh-owned fields, and catalog association.
        assertEquals("recv.pkg", result.packageName)
        assertEquals("https://recv.example/", result.f95Url)
        assertEquals("9.9.9recv", result.lastSeenVersion)
        assertEquals(111L, result.lastChecked)
        assertEquals("9.9.9ack-recv", result.acknowledgedVersion)
        assertEquals(42, result.threadId)
        assertTrue(result.notOnF95)
        assertEquals("recvSource", result.matchSource)
        assertEquals(5, result.mappedCatalogId)
        assertEquals("recvSrc", result.mappedCatalogSource)
        assertEquals("recvSrcId", result.mappedCatalogSourceId)
        assertEquals("recvGroup", result.mappedAgmGroupId)
        assertEquals("recvTitle", result.mappedCatalogTitle)
        assertEquals("recvVer", result.mappedCatalogVersion)
        assertEquals("recvUrl", result.mappedCatalogUrl)
        assertEquals(333L, result.mappedCatalogUpdatedAt)
        assertEquals(444L, result.mappedCatalogPublishedAt)
        assertEquals(555L, result.mappedCatalogModifiedAt)
        assertEquals("recvCover", result.mappedCatalogCoverUrl)
        assertEquals("recvThumb", result.mappedCatalogThumbnailUrl)

        // Copied from existing: personal fields plus manualLocalIdentity.
        assertEquals("existDisplay", result.displayNameOverride)
        assertEquals(UserGameStatus.Completed, result.userStatus)
        assertEquals(5, result.personalRating)
        assertEquals("existNotes", result.personalNotes)
        assertEquals("existCorrection", result.manualCorrectionNote)
        assertEquals("existManualVer", result.manualInstalledVersion)
        assertEquals("existVerFp", result.manualInstalledVersionFingerprint)
        assertEquals(777L, result.manualInstalledDate)
        assertEquals("existDateFp", result.manualInstalledDateFingerprint)
        assertEquals("existDateSrc", result.manualInstalledDateSource)
        assertEquals(listOf("existTok"), result.manualLocalIdentity)
    }

    // ---------------------------------------------------------------------
    // AppMapping.hasPersonalFields
    // ---------------------------------------------------------------------

    @Test
    fun hasPersonalFields_falseWhenAllEmpty() {
        assertFalse(mapping().hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForDisplayNameOverride() {
        assertTrue(mapping().copy(displayNameOverride = "Custom").hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForNonNoneUserStatus() {
        assertTrue(mapping().copy(userStatus = UserGameStatus.Playing).hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForPersonalRating() {
        assertTrue(mapping().copy(personalRating = 1).hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForPersonalNotes() {
        assertTrue(mapping().copy(personalNotes = "note").hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForManualCorrectionNote() {
        assertTrue(mapping().copy(manualCorrectionNote = "corrected").hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForManualInstalledVersion() {
        assertTrue(mapping().copy(manualInstalledVersion = "1.0").hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_trueForManualInstalledDate() {
        assertTrue(mapping().copy(manualInstalledDate = 1L).hasPersonalFields())
    }

    @Test
    fun hasPersonalFields_falseForBlankOrZeroTriggerValues() {
        val m = mapping().copy(
            displayNameOverride = "  ",
            personalNotes = " ",
            manualCorrectionNote = "",
            manualInstalledVersion = "  ",
            manualInstalledDate = 0L,
        )

        assertFalse(m.hasPersonalFields())
    }

    // ---------------------------------------------------------------------
    // AppMapping.withoutCatalogAssociation
    // ---------------------------------------------------------------------

    @Test
    fun withoutCatalogAssociation_clearsCatalogFields_defaultArgs() {
        val m = AppMapping(
            packageName = "pkg",
            f95Url = "https://f95zone.to/threads/1/",
            lastSeenVersion = "1.0",
            lastChecked = 500L,
            acknowledgedVersion = "1.0",
            threadId = 1,
            notOnF95 = true,
            matchSource = "old",
            mappedCatalogId = 2,
            mappedCatalogSource = SOURCE_F95ZONE,
            mappedCatalogSourceId = "2",
            mappedCatalogTitle = "Title",
            mappedCatalogVersion = "1.0",
            mappedCatalogUrl = "https://example.test",
            mappedCatalogUpdatedAt = 1L,
            mappedCatalogPublishedAt = 2L,
            mappedCatalogModifiedAt = 3L,
            mappedCatalogCoverUrl = "cover",
            mappedCatalogThumbnailUrl = "thumb",
            displayNameOverride = "Keep Me",
            personalNotes = "Keep notes",
        )

        val result = m.withoutCatalogAssociation()

        assertNull(result.f95Url)
        assertNull(result.lastSeenVersion)
        assertEquals(0L, result.lastChecked)
        assertNull(result.acknowledgedVersion)
        assertNull(result.threadId)
        assertFalse(result.notOnF95)
        assertNull(result.matchSource)
        assertNull(result.mappedCatalogId)
        assertNull(result.mappedCatalogSource)
        assertNull(result.mappedCatalogSourceId)
        assertEquals("", result.mappedCatalogTitle)
        assertNull(result.mappedCatalogVersion)
        assertNull(result.mappedCatalogUrl)
        assertEquals(0L, result.mappedCatalogUpdatedAt)
        assertEquals(0L, result.mappedCatalogPublishedAt)
        assertEquals(0L, result.mappedCatalogModifiedAt)
        assertNull(result.mappedCatalogCoverUrl)
        assertNull(result.mappedCatalogThumbnailUrl)

        // Personal fields are untouched by this function.
        assertEquals("Keep Me", result.displayNameOverride)
        assertEquals("Keep notes", result.personalNotes)
    }

    @Test
    fun withoutCatalogAssociation_setsNotOnF95AndMatchSourceFromArgs() {
        val result = mapping().withoutCatalogAssociation(notOnF95 = true, matchSource = "manual")

        assertTrue(result.notOnF95)
        assertEquals("manual", result.matchSource)
    }

    // ---------------------------------------------------------------------
    // AppMapping.withLocalIdentityFrom
    // ---------------------------------------------------------------------

    @Test
    fun withLocalIdentityFrom_setsManualLocalIdentityOnly() {
        val a = app(packageName = "com.example.mygame", label = "My Game Title")
        val m = mapping().copy(displayNameOverride = "Keep Me")

        val result = m.withLocalIdentityFrom(a)

        assertEquals(localIdentityTokens(a), result.manualLocalIdentity)
        assertEquals("Keep Me", result.displayNameOverride)
    }

    // ---------------------------------------------------------------------
    // AppMapping.withCatalogSnapshot
    // ---------------------------------------------------------------------

    @Test
    fun withCatalogSnapshot_mapsEachFieldFromCatalogGame() {
        val game = CatalogGame(
            thread_id = 555,
            title = "Some Title",
            version = "1.2.3",
            source = "kimochi",
            sourceId = "abc123",
            sourceUrl = "https://example.test/canonical",
            ts = 1000L,
            publishedAt = 2000L,
            modifiedAt = 3000L,
            cover = "https://example.test/cover.jpg",
            thumbnailUrl = "https://example.test/thumb.jpg",
        )

        val result = mapping().withCatalogSnapshot(game)

        assertEquals(555, result.mappedCatalogId)
        assertEquals("kimochi", result.mappedCatalogSource)
        assertEquals("abc123", result.mappedCatalogSourceId)
        assertEquals("Some Title", result.mappedCatalogTitle)
        assertEquals("1.2.3", result.mappedCatalogVersion)
        assertEquals("https://example.test/canonical", result.mappedCatalogUrl)
        assertEquals(1000L, result.mappedCatalogUpdatedAt)
        assertEquals(2000L, result.mappedCatalogPublishedAt)
        assertEquals(3000L, result.mappedCatalogModifiedAt)
        assertEquals("https://example.test/cover.jpg", result.mappedCatalogCoverUrl)
        assertEquals("https://example.test/thumb.jpg", result.mappedCatalogThumbnailUrl)
    }

    // ---------------------------------------------------------------------
    // AppMapping.withExternalSnapshot
    // ---------------------------------------------------------------------

    @Test
    fun withExternalSnapshot_threadIdPresent_f95zoneSource() {
        val result = externalResult(
            title = "Ext Title",
            mirrorUrl = "https://f95zone.to/threads/321/",
            threadId = 321,
            version = "2.0",
            sourceHost = "f95zone.to",
        )
        val m = mapping().withExternalSnapshot(result)

        assertEquals(321, m.mappedCatalogId)
        assertEquals(SOURCE_F95ZONE, m.mappedCatalogSource)
        assertEquals("321", m.mappedCatalogSourceId)
        assertEquals("Ext Title", m.mappedCatalogTitle)
        assertEquals("2.0", m.mappedCatalogVersion)
        assertEquals("https://f95zone.to/threads/321/", m.mappedCatalogUrl)
        assertEquals(0L, m.mappedCatalogUpdatedAt)
        assertEquals(0L, m.mappedCatalogPublishedAt)
        assertEquals(0L, m.mappedCatalogModifiedAt)
    }

    @Test
    fun withExternalSnapshot_adultGameWorldHost_isCaseInsensitive() {
        val result = externalResult(sourceHost = "AdultGameWorld.COM", threadId = null, mirrorUrl = "https://adultgameworld.com/g/1")
        val m = mapping().withExternalSnapshot(result)

        assertEquals(SOURCE_ADULTGAMEWORLD, m.mappedCatalogSource)
    }

    @Test
    fun withExternalSnapshot_noThreadId_usesMirrorUrlHashCode() {
        val result = externalResult(threadId = null, mirrorUrl = "https://example.test/no-thread")
        val m = mapping().withExternalSnapshot(result)

        assertEquals("https://example.test/no-thread".hashCode(), m.mappedCatalogId)
        assertNull(m.mappedCatalogSourceId)
    }

    // ---------------------------------------------------------------------
    // AppMapping.toCatalogSnapshot
    // ---------------------------------------------------------------------

    @Test
    fun toCatalogSnapshot_nullWhenNoCatalogIdAndNoF95Url() {
        assertNull(mapping().toCatalogSnapshot())
    }

    @Test
    fun toCatalogSnapshot_nullWhenF95UrlIsBlank() {
        val m = mapping().copy(f95Url = "   ")

        assertNull(m.toCatalogSnapshot())
    }

    @Test
    fun toCatalogSnapshot_usesMappedCatalogIdWhenPresent() {
        val m = mapping().copy(mappedCatalogId = 42, f95Url = "https://f95zone.to/threads/999/")

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(42, snapshot.thread_id)
    }

    @Test
    fun toCatalogSnapshot_fallsBackToF95UrlHashCodeWhenNoCatalogId() {
        val url = "https://f95zone.to/threads/999/"
        val m = mapping().copy(f95Url = url)

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(url.hashCode(), snapshot.thread_id)
    }

    @Test
    fun toCatalogSnapshot_sourceInferredAdultGameWorldFromUrl() {
        val m = mapping().copy(f95Url = "https://AdultGameWorld.example/thread/1")

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(SOURCE_ADULTGAMEWORLD, snapshot.source)
    }

    @Test
    fun toCatalogSnapshot_sourceDefaultsToF95ZoneWhenUrlDoesNotMentionAdultGameWorld() {
        val m = mapping().copy(f95Url = "https://f95zone.to/threads/1/")

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(SOURCE_F95ZONE, snapshot.source)
    }

    @Test
    fun toCatalogSnapshot_explicitMappedCatalogSourceOverridesUrlInference() {
        val m = mapping().copy(
            f95Url = "https://adultgameworld.example/thread/1",
            mappedCatalogSource = SOURCE_F95ZONE,
        )

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(SOURCE_F95ZONE, snapshot.source)
    }

    @Test
    fun toCatalogSnapshot_titleFallsBackToUrlWhenMappedTitleBlank() {
        val url = "https://f95zone.to/threads/1/"
        val m = mapping().copy(f95Url = url, mappedCatalogTitle = "")

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(url, snapshot.title)
    }

    @Test
    fun toCatalogSnapshot_titleFallsBackToEmptyWhenNoUrlAndNoTitle() {
        val m = mapping().copy(mappedCatalogId = 7, mappedCatalogTitle = "")

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals("", snapshot.title)
    }

    @Test
    fun toCatalogSnapshot_versionFallsBackToLastSeenVersion() {
        val m = mapping().copy(
            f95Url = "https://f95zone.to/threads/1/",
            mappedCatalogVersion = null,
            lastSeenVersion = "0.5-fallback",
        )

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals("0.5-fallback", snapshot.version)
    }

    @Test
    fun toCatalogSnapshot_versionPrefersMappedCatalogVersion() {
        val m = mapping().copy(
            f95Url = "https://f95zone.to/threads/1/",
            mappedCatalogVersion = "9.9",
            lastSeenVersion = "0.5-fallback",
        )

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals("9.9", snapshot.version)
    }

    @Test
    fun toCatalogSnapshot_sourceUrlPrefersMappedCatalogUrlOverF95Url() {
        val m = mapping().copy(
            f95Url = "https://f95zone.to/threads/1/",
            mappedCatalogUrl = "https://mapped.example/1",
        )

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals("https://mapped.example/1", snapshot.sourceUrl)
    }

    @Test
    fun toCatalogSnapshot_modifiedAtFallsBackToUpdatedAtWhenNotPositive() {
        val m = mapping().copy(
            f95Url = "https://f95zone.to/threads/1/",
            mappedCatalogUpdatedAt = 555L,
            mappedCatalogModifiedAt = 0L,
        )

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(555L, snapshot.modifiedAt)
    }

    @Test
    fun toCatalogSnapshot_modifiedAtKeptWhenPositive() {
        val m = mapping().copy(
            f95Url = "https://f95zone.to/threads/1/",
            mappedCatalogUpdatedAt = 555L,
            mappedCatalogModifiedAt = 777L,
        )

        val snapshot = checkNotNull(m.toCatalogSnapshot())

        assertEquals(777L, snapshot.modifiedAt)
    }

    // ---------------------------------------------------------------------
    // mappedCatalogGame
    // ---------------------------------------------------------------------

    @Test
    fun mappedCatalogGame_nullMapping_returnsNull() {
        assertNull(mappedCatalogGame(null, emptyMap()))
    }

    @Test
    fun mappedCatalogGame_prefersMappedCatalogIdOverThreadIdAndUrl() {
        val wanted = catalogGame(10).copy(title = "Wanted")
        val decoy = catalogGame(20).copy(title = "Decoy")
        val m = mapping().copy(
            mappedCatalogId = 10,
            threadId = 20,
            f95Url = "https://f95zone.to/threads/30/",
        )

        val result = mappedCatalogGame(m, mapOf(10 to wanted, 20 to decoy))

        assertEquals(wanted, result)
    }

    @Test
    fun mappedCatalogGame_fallsBackToThreadIdWhenNoMappedCatalogId() {
        val wanted = catalogGame(20).copy(title = "Wanted")
        val m = mapping().copy(mappedCatalogId = null, threadId = 20)

        val result = mappedCatalogGame(m, mapOf(20 to wanted))

        assertEquals(wanted, result)
    }

    @Test
    fun mappedCatalogGame_fallsBackToExtractedF95ThreadIdWhenNoIdOrThreadId() {
        val wanted = catalogGame(30).copy(title = "Wanted")
        val m = mapping().copy(mappedCatalogId = null, threadId = null, f95Url = "https://f95zone.to/threads/some-slug.30/")

        val result = mappedCatalogGame(m, mapOf(30 to wanted))

        assertEquals(wanted, result)
    }

    @Test
    fun mappedCatalogGame_tidNotInMap_fallsBackToSnapshot() {
        val m = mapping().copy(mappedCatalogId = 55, mappedCatalogTitle = "Snapshot Title")

        val result = checkNotNull(mappedCatalogGame(m, mapOf(999 to catalogGame(999))))

        assertEquals(55, result.thread_id)
        assertEquals("Snapshot Title", result.title)
    }

    @Test
    fun mappedCatalogGame_noResolvableTid_fallsBackToSnapshotWhichCanBeNull() {
        val m = mapping().copy(mappedCatalogId = null, threadId = null, f95Url = null)

        assertNull(mappedCatalogGame(m, mapOf(1 to catalogGame(1))))
    }

    // ---------------------------------------------------------------------
    // AppMapping.f95CatalogThreadId
    // ---------------------------------------------------------------------

    @Test
    fun f95CatalogThreadId_usesSourceIdWhenSourceIsF95ZoneAndParsable() {
        val m = mapping().copy(
            mappedCatalogSource = SOURCE_F95ZONE,
            mappedCatalogSourceId = "123",
            threadId = 999,
            f95Url = "https://f95zone.to/threads/888/",
        )

        assertEquals(123, m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_fallsBackToThreadIdWhenSourceIdNotParsable() {
        val m = mapping().copy(
            mappedCatalogSource = SOURCE_F95ZONE,
            mappedCatalogSourceId = "not-a-number",
            threadId = 999,
        )

        assertEquals(999, m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_skipsSourceIdCheckWhenSourceIsNotF95Zone() {
        val m = mapping().copy(
            mappedCatalogSource = SOURCE_ADULTGAMEWORLD,
            mappedCatalogSourceId = "123",
            threadId = 999,
        )

        assertEquals(999, m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_fallsBackToExtractedF95UrlWhenNoThreadId() {
        val m = mapping().copy(threadId = null, f95Url = "https://f95zone.to/threads/slug.777/")

        assertEquals(777, m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_fallsBackToMappedCatalogIdWhenPositiveAndSourceNull() {
        val m = mapping().copy(threadId = null, f95Url = null, mappedCatalogId = 50, mappedCatalogSource = null)

        assertEquals(50, m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_fallsBackToMappedCatalogIdWhenPositiveAndSourceIsF95Zone() {
        val m = mapping().copy(threadId = null, f95Url = null, mappedCatalogId = 50, mappedCatalogSource = SOURCE_F95ZONE)

        assertEquals(50, m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_excludesMappedCatalogIdWhenSourceIsAdultGameWorld() {
        val m = mapping().copy(threadId = null, f95Url = null, mappedCatalogId = 50, mappedCatalogSource = SOURCE_ADULTGAMEWORLD)

        assertNull(m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_excludesNonPositiveMappedCatalogId() {
        val m = mapping().copy(threadId = null, f95Url = null, mappedCatalogId = -5, mappedCatalogSource = null)

        assertNull(m.f95CatalogThreadId())
    }

    @Test
    fun f95CatalogThreadId_nullWhenNothingResolves() {
        val m = mapping().copy(threadId = null, f95Url = null, mappedCatalogId = null)

        assertNull(m.f95CatalogThreadId())
    }

    // ---------------------------------------------------------------------
    // threadUpdatedAfterInstall
    // ---------------------------------------------------------------------

    @Test
    fun threadUpdatedAfterInstall_trueWhenUpdatedAfterInstall() {
        val game = catalogGame(10).copy(ts = 5000L)
        val installed = app(firstInstallTime = 4_999_999L)
        val m = mapping().copy(mappedCatalogId = 10)
        val row = AppRow(installed = installed, mapping = m, status = UpdateStatus.Unknown)

        assertTrue(threadUpdatedAfterInstall(row, mapOf(10 to game)))
    }

    @Test
    fun threadUpdatedAfterInstall_falseWhenExactlyEqual_boundary() {
        val game = catalogGame(10).copy(ts = 5000L)
        val installed = app(firstInstallTime = 5_000_000L)
        val m = mapping().copy(mappedCatalogId = 10)
        val row = AppRow(installed = installed, mapping = m, status = UpdateStatus.Unknown)

        assertFalse(threadUpdatedAfterInstall(row, mapOf(10 to game)))
    }

    @Test
    fun threadUpdatedAfterInstall_falseWhenInstalledAtIsZero() {
        val game = catalogGame(10).copy(ts = 5000L)
        val installed = app(firstInstallTime = 0L)
        val m = mapping().copy(mappedCatalogId = 10)
        val row = AppRow(installed = installed, mapping = m, status = UpdateStatus.Unknown)

        assertFalse(threadUpdatedAfterInstall(row, mapOf(10 to game)))
    }

    @Test
    fun threadUpdatedAfterInstall_falseWhenUpdatedAtIsZero() {
        val game = catalogGame(10).copy(ts = 0L)
        val installed = app(firstInstallTime = 1L)
        val m = mapping().copy(mappedCatalogId = 10)
        val row = AppRow(installed = installed, mapping = m, status = UpdateStatus.Unknown)

        assertFalse(threadUpdatedAfterInstall(row, mapOf(10 to game)))
    }
}
