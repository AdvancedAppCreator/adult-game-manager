package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT behavior of the pure JoiPlay size helpers
 * ([joiPlaySizeKey], [effectiveInstalledSize], [effectiveInstalledTotalSize]) as implemented in
 * MainActivity.kt (package com.example.f95updater). These helpers are `internal`, so this test
 * (same module, same package, unit test source set) can call them without imports. A concurrent
 * behavior-preserving refactor may move them verbatim to a new file in the same package; these
 * tests should keep resolving and passing either way.
 *
 * These tests intentionally document existing behavior (including edge cases) rather than
 * desired behavior, to guard against accidental changes during the refactor.
 *
 * Verified against the real MainActivity.kt source (matches the task summary exactly):
 * - joiPlaySizeKey: non-JoiPlay -> null; normalizes '\\' -> '/' and trims trailing '/'; if the
 *   basename (lowercased) is a wrapper folder (www/game/app/src/resources) AND the parent is
 *   non-blank, returns the parent; otherwise falls back to storageFolderName (if non-blank) or
 *   else the path's last segment (if non-blank).
 * - effectiveInstalledSize: for JoiPlay, uses joiPlaySizeInfo.totalBytes when > 0, else
 *   app.totalSize; for non-JoiPlay, always app.totalSize.
 * - effectiveInstalledTotalSize: sums effectiveInstalledSize per app, looking up each JoiPlay
 *   app's SizeInfo in the map via joiPlaySizeKey(app).
 */
class JoiPlaySizeHelpersCharacterizationTest {

    /** Fills the InstalledApp fields required with no defaults, letting callers override the
     *  fields these three helpers actually read (source, storagePath, storageFolderName, and the
     *  apkSize/dataSize/cacheSize that back the computed totalSize). */
    private fun app(
        source: AppSource = AppSource.Android,
        storagePath: String? = null,
        storageFolderName: String? = null,
        apkSize: Long = 0L,
        dataSize: Long = 0L,
        cacheSize: Long = 0L,
    ) = InstalledApp(
        packageName = "pkg.test",
        label = "Test",
        versionName = "1",
        versionCode = 1,
        source = source,
        storagePath = storagePath,
        storageFolderName = storageFolderName,
        apkSize = apkSize,
        dataSize = dataSize,
        cacheSize = cacheSize,
    )

    // ---- joiPlaySizeKey ----

    @Test
    fun nonJoiPlaySourceReturnsNull() {
        val a = app(source = AppSource.Android, storagePath = "/storage/emulated/0/Games/MyGame/www")

        assertNull(joiPlaySizeKey(a))
    }

    @Test
    fun managedGameUsesStablePackageKeyAndAbsoluteFolderTarget() {
        val a = app(
            source = AppSource.Managed,
            storagePath = "/storage/emulated/0/Games/MyGame",
        ).copy(packageName = "managed:game-id")

        assertEquals("managed:game-id", joiPlaySizeKey(a))
        val target = requireNotNull(joiPlaySizeTarget(a))
        assertEquals("managed:game-id", target.key)
        assertEquals("/storage/emulated/0/Games/MyGame", target.storagePath)
    }

    @Test
    fun managedFolderNamedGameIsNotCollapsedToItsParent() {
        val a = app(
            source = AppSource.Managed,
            storagePath = "/storage/emulated/0/Games/game",
        ).copy(packageName = "managed:game-id")

        assertEquals(
            "/storage/emulated/0/Games/game",
            requireNotNull(joiPlaySizeTarget(a)).storagePath,
        )
    }

    @Test
    fun managedTargetCollectionIncludesUnifiedGamesAndExcludesAndroid() {
        val managed = app(
            source = AppSource.Managed,
            storagePath = "/storage/emulated/0/Games/Managed",
        ).copy(packageName = "managed:one")
        val android = app(
            source = AppSource.Android,
            storagePath = "/storage/emulated/0/Games/Android",
        )

        assertEquals(listOf("managed:one"), managedGameSizeTargets(listOf(managed, android)).map { it.key })
    }

    @Test
    fun wrapperBasenameWithNonBlankParentReturnsParent() {
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/MyGame/www",
        )

        assertEquals("MyGame", joiPlaySizeKey(a))
    }

    @Test
    fun backslashPathIsNormalizedBeforeWrapperCheck() {
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "C:\\Games\\MyGame\\www",
        )

        assertEquals("MyGame", joiPlaySizeKey(a))
    }

    @Test
    fun trailingSlashIsTrimmedBeforeWrapperCheck() {
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/MyGame/www/",
        )

        assertEquals("MyGame", joiPlaySizeKey(a))
    }

    @Test
    fun nonWrapperBasenameFallsBackToStorageFolderNameWhenNonBlank() {
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/MyGame",
            storageFolderName = "CustomFolder",
        )

        assertEquals("CustomFolder", joiPlaySizeKey(a))
    }

    @Test
    fun nonWrapperBasenameWithBlankStorageFolderNameFallsBackToPathLastSegment() {
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "/storage/emulated/0/Games/MyGame",
            storageFolderName = "",
        )

        assertEquals("MyGame", joiPlaySizeKey(a))
    }

    @Test
    fun wrapperBasenameWithBlankParentFallsThroughToStorageFolderName() {
        // storagePath has no '/', so basename == "www" (a wrapper folder) but parent is blank;
        // the wrapper-parent branch is skipped and the fallback chain runs instead.
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "www",
            storageFolderName = "ActualGame",
        )

        assertEquals("ActualGame", joiPlaySizeKey(a))
    }

    @Test
    fun wrapperBasenameWithBlankParentAndBlankStorageFolderNameFallsBackToPathItself() {
        // Same as above but with no storageFolderName override: the fallback then uses the
        // path's own last segment, which (since there is no '/') is the path itself, "www". This
        // is NOT null -- the wrapper-parent short-circuit simply never returns the key itself.
        val a = app(
            source = AppSource.JoiPlay,
            storagePath = "www",
        )

        assertEquals("www", joiPlaySizeKey(a))
    }

    @Test
    fun blankStorageFolderNameAndNullPathReturnsNull() {
        val a = app(source = AppSource.JoiPlay, storagePath = null, storageFolderName = "")

        assertNull(joiPlaySizeKey(a))
    }

    // ---- effectiveInstalledSize ----

    @Test
    fun nonJoiPlayAlwaysUsesAppTotalSizeRegardlessOfSizeInfo() {
        val a = app(source = AppSource.Android, apkSize = 10L, dataSize = 20L, cacheSize = 3L)

        assertEquals(33L, effectiveInstalledSize(a, JoiPlayScanner.SizeInfo(totalBytes = 999L)))
        assertEquals(33L, effectiveInstalledSize(a, null))
    }

    @Test
    fun joiPlayWithPositiveSizeInfoTotalBytesUsesThatValue() {
        val a = app(source = AppSource.JoiPlay, apkSize = 10L)

        assertEquals(777L, effectiveInstalledSize(a, JoiPlayScanner.SizeInfo(totalBytes = 777L)))
    }

    @Test
    fun joiPlayWithNullSizeInfoFallsBackToAppTotalSize() {
        val a = app(source = AppSource.JoiPlay, apkSize = 123L)

        assertEquals(123L, effectiveInstalledSize(a, null))
    }

    @Test
    fun joiPlayWithZeroSizeInfoTotalBytesFallsBackToAppTotalSize() {
        val a = app(source = AppSource.JoiPlay, apkSize = 123L)

        assertEquals(123L, effectiveInstalledSize(a, JoiPlayScanner.SizeInfo(totalBytes = 0L)))
    }

    // ---- effectiveInstalledTotalSize ----

    @Test
    fun mixedListSumsAndroidTotalSizeAndMatchedJoiPlayScannedSize() {
        val android = app(source = AppSource.Android, apkSize = 10L, dataSize = 20L)
        val joiplay = app(
            source = AppSource.JoiPlay,
            apkSize = 1L,
            storagePath = "/storage/emulated/0/Games/Wrapped/www",
            storageFolderName = "www",
        )

        val total = effectiveInstalledTotalSize(
            listOf(android, joiplay),
            mapOf("Wrapped" to JoiPlayScanner.SizeInfo(totalBytes = 777L)),
        )

        // android: 10 + 20 = 30, joiplay: matched key "Wrapped" -> scanned 777
        assertEquals(807L, total)
    }

    @Test
    fun joiPlayAppWithKeyAbsentFromMapFallsBackToAppTotalSize() {
        val joiplay = app(
            source = AppSource.JoiPlay,
            apkSize = 55L,
            storagePath = "/storage/emulated/0/Games/Unscanned/www",
            storageFolderName = "www",
        )

        val total = effectiveInstalledTotalSize(
            listOf(joiplay),
            mapOf("SomeOtherGame" to JoiPlayScanner.SizeInfo(totalBytes = 999L)),
        )

        assertEquals(55L, total)
    }
}
