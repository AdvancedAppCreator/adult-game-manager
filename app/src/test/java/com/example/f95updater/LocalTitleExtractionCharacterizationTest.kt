package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Characterization tests pinning the CURRENT behavior of the local title-extraction logic in
 * MainActivity.kt: [catalogMatchLabels] and [joiplayInternalTitleLabels] (which transitively
 * exercises the private RenPy/RPGM/HTML/readme readers and [cleanCatalogMetadataTitle]). These
 * tests intentionally document existing behavior, including any quirks, rather than an ideal
 * behavior - this is a behavior-preserving refactor.
 */
class LocalTitleExtractionCharacterizationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun app(
        label: String = "Label",
        launcherLabel: String? = null,
        readmeTitle: String? = null,
        storagePath: String? = null,
        storageFolderName: String? = null,
        source: AppSource = AppSource.Android,
    ) = InstalledApp(
        packageName = "test.pkg",
        label = label,
        launcherLabel = launcherLabel,
        versionName = "1.0",
        versionCode = 1L,
        source = source,
        storagePath = storagePath,
        storageFolderName = storageFolderName,
        readmeTitle = readmeTitle,
    )

    // ---------------------------------------------------------------------
    // catalogMatchLabels - pure/no file IO for non-JoiPlay apps.
    // ---------------------------------------------------------------------

    @Test
    fun catalogMatchLabels_assemblesLabelLauncherLabelAndReadmeTitleInOrder() {
        val a = app(label = "Manifest Label", launcherLabel = "Launcher Label", readmeTitle = "Readme Title")

        assertEquals(listOf("Manifest Label", "Launcher Label", "Readme Title"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_nullLauncherLabelAndReadmeTitleAreOmitted() {
        val a = app(label = "Only Label")

        assertEquals(listOf("Only Label"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_wrapperFolderBasename_includesParentFolder() {
        // basename "www" is in the wrapper-folder set, so the parent folder name is appended.
        // Assembly order is: storageFolderName, basename, parent - so basename comes before parent.
        val a = app(label = "L", storagePath = "/storage/emulated/0/Games/SuperGame/www")

        assertEquals(listOf("L", "www", "SuperGame"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_nonWrapperBasename_excludesParentFolder() {
        // basename "RandomFolder" is not one of {www, game, app, src, resources}, so the parent
        // ("Parent") must NOT be included, only the basename itself.
        val a = app(label = "L", storagePath = "/storage/emulated/0/Parent/RandomFolder")

        assertEquals(listOf("L", "RandomFolder"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_wrapperFolderMatchIsCaseSensitive() {
        // The wrapper-folder set {"www","game","app","src","resources"} is matched with plain
        // `in` set membership (no lowercasing), unlike GameReadmeTitle's README_WRAPPER_FOLDERS
        // check which does `.lowercase()` first. So an uppercase "WWW" folder name does NOT
        // trigger parent-folder inclusion here, even though it clearly plays the same wrapper role.
        val a = app(label = "L", storagePath = "/storage/emulated/0/Games/SuperGame/WWW")

        assertEquals(listOf("L", "WWW"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_backslashPathsAndTrailingSlashesAreNormalized() {
        val a = app(label = "L", storagePath = "C:\\Games\\MyGame\\www\\\\\\")

        assertEquals(listOf("L", "www", "MyGame"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_pathWithNoSegments_yieldsNoBasenameOrParent() {
        // Only separators: after trimEnd('/'), the path collapses to "" so both basename and
        // parent resolve to blank/null and are silently dropped - no crash.
        val a = app(label = "L", storagePath = "///")

        assertEquals(listOf("L"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_storageFolderNameIncludedEvenWithoutStoragePath() {
        val a = app(label = "L", storagePath = null, storageFolderName = "FolderName")

        assertEquals(listOf("L", "FolderName"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_duplicateValuesAcrossFieldsAreDeduped() {
        // label, storageFolderName and the path basename are all identical -> distinct() keeps one.
        val a = app(label = "Game", storagePath = "/root/Game", storageFolderName = "Game")

        assertEquals(listOf("Game"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_valuesAreTrimmedAndBlanksAreFiltered() {
        val a = app(label = "  Padded Label  ", launcherLabel = "   ", readmeTitle = "")

        assertEquals(listOf("Padded Label"), catalogMatchLabels(a))
    }

    @Test
    fun catalogMatchLabels_fullAssemblyIncludingStorageFolderNameBasenameAndParent() {
        val a = app(
            label = "Label",
            launcherLabel = "Launcher",
            readmeTitle = "Readme",
            storagePath = "/root/Parent/game",
            storageFolderName = "StorageFolder",
        )

        // Order: label, launcherLabel, readmeTitle, (joiplay labels - empty, non-JoiPlay source),
        // storageFolderName, basename, parent (basename "game" is a wrapper folder).
        assertEquals(
            listOf("Label", "Launcher", "Readme", "StorageFolder", "game", "Parent"),
            catalogMatchLabels(a),
        )
    }

    // ---------------------------------------------------------------------
    // joiplayInternalTitleLabels - source/directory gating.
    // ---------------------------------------------------------------------

    @Test
    fun joiplayInternalTitleLabels_nonJoiPlaySource_returnsEmptyList() {
        val root = temp.newFolder("androidApp")
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("""{"name":"Should Not Appear"}""")
        val a = app(source = AppSource.Android, storagePath = root.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_nullStoragePath_returnsEmptyList() {
        val a = app(source = AppSource.JoiPlay, storagePath = null)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_nonDirectoryStoragePath_returnsEmptyList() {
        val file = temp.newFile("not-a-directory.txt")
        val a = app(source = AppSource.JoiPlay, storagePath = file.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    // ---------------------------------------------------------------------
    // joiplayInternalTitleLabels - individual readers, direct (non-subdir-scan) root.
    // ---------------------------------------------------------------------

    @Test
    fun joiplayInternalTitleLabels_renPyBuildInfoJson_yieldsName() {
        val root = temp.newFolder("renpyBuildInfo")
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("""{"name":"Some Game"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Some Game"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_renPyOptionsRpy_yieldsConfigName() {
        val root = temp.newFolder("renpyOptions")
        File(root, "game").mkdirs()
        File(root, "game/options.rpy").writeText("define config.name = \"Foo\"")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Foo"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_renPyAboutRpyFallback_usedWhenOptionsRpyMissing() {
        val root = temp.newFolder("renpyAboutFallback")
        File(root, "game/gui").mkdirs()
        File(root, "game/gui/about.rpy").writeText("""config.name = _("About Name")""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("About Name"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_rpgmSystemJsonUnderWww_yieldsGameTitle() {
        val root = temp.newFolder("rpgmWww")
        File(root, "www/data").mkdirs()
        File(root, "www/data/System.json").writeText("""{"gameTitle":"Bar"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Bar"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_rpgmSystemJsonFallback_usedWhenNoWwwFolder() {
        val root = temp.newFolder("rpgmDataOnly")
        File(root, "data").mkdirs()
        File(root, "data/System.json").writeText("""{"gameTitle":"OnlyDataTitle"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        // "data" alone also satisfies the directHasGame check, so this is still a direct root
        // (no subdirectory scan happens even though other, unrelated subfolders might exist).
        val junk = File(root, "Junk/www").apply { mkdirs() }
        File(junk, "index.html").writeText("<html><title>Junk Title</title></html>")

        assertEquals(listOf("OnlyDataTitle"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_htmlTitleUnderWww_yieldsTitleWithCollapsedWhitespace() {
        val root = temp.newFolder("htmlWww")
        File(root, "www").mkdirs()
        File(root, "www/index.html").writeText("<html><head><title>\n  Baz  \n</title></head></html>")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Baz"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_htmlTitleFallback_usedWhenNoWwwFolder() {
        val root = temp.newFolder("htmlRootOnly")
        File(root, "index.html").writeText("<html><title>RootPageTitle</title></html>")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("RootPageTitle"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_htmlEntitiesAreUnescaped() {
        val root = temp.newFolder("htmlEntities")
        File(root, "www").mkdirs()
        File(root, "www/index.html").writeText("<html><title>Bob &amp; Ann Adventure</title></html>")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Bob & Ann Adventure"), joiplayInternalTitleLabels(a))
    }

    // ---------------------------------------------------------------------
    // cleanCatalogMetadataTitle rejection rules, exercised via readRenPyBuildInfoTitle.
    // ---------------------------------------------------------------------

    @Test
    fun joiplayInternalTitleLabels_rejectsBlocklistedNormalizedTitle() {
        val root = temp.newFolder("blocklisted")
        File(root, "game/cache").mkdirs()
        // normalizeTitle("Game") == "game", which is in the reject set.
        File(root, "game/cache/build_info.json").writeText("""{"name":"Game"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_rejectsTooShortNormalizedTitle() {
        val root = temp.newFolder("tooShort")
        File(root, "game/cache").mkdirs()
        // normalizeTitle("Ab") == "ab", length 2 < 3.
        File(root, "game/cache/build_info.json").writeText("""{"name":"Ab"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_rejectsAllDigitNormalizedTitle() {
        val root = temp.newFolder("allDigits")
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("""{"name":"12345"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_acceptsExactlyThreeCharacterNormalizedTitle() {
        // Pins the boundary: normalized.length < 3 rejects, but == 3 is accepted.
        val root = temp.newFolder("threeChars")
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("""{"name":"Foo"}""")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Foo"), joiplayInternalTitleLabels(a))
    }

    // ---------------------------------------------------------------------
    // readSmallTextFile rejection: empty and oversized files.
    // ---------------------------------------------------------------------

    @Test
    fun joiplayInternalTitleLabels_emptyBuildInfoFileIsRejected() {
        val root = temp.newFolder("emptyFile")
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_oversizedOptionsRpyFileIsRejected() {
        val root = temp.newFolder("oversizedFile")
        File(root, "game").mkdirs()
        // Pad well past the 128 KiB readSmallTextFile ceiling; the config.name assignment is
        // still present in the content, but the file must be skipped purely due to size.
        val padding = "# ".repeat(70_000)
        File(root, "game/options.rpy").writeText("$padding\ndefine config.name = \"Oversized Name\"")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(emptyList<String>(), joiplayInternalTitleLabels(a))
    }

    // ---------------------------------------------------------------------
    // Subdirectory-scan branch: root has no direct game/www/data folder.
    // ---------------------------------------------------------------------

    @Test
    fun joiplayInternalTitleLabels_subdirectoryScanBranch_findsNestedGameData() {
        val root = temp.newFolder("subdirScan")
        File(root, "MyGame/www").mkdirs()
        File(root, "MyGame/www/index.html").writeText("<html><title>Nested Title</title></html>")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Nested Title"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_subdirectoryScanBranch_skipsNonContentReadmeFolders() {
        val root = temp.newFolder("subdirScanSkip")
        // "redist" is a non-content folder (dependency/third-party material) and must be skipped
        // entirely by the subdirectory scan, even though it contains what looks like game data.
        File(root, "redist/www").mkdirs()
        File(root, "redist/www/index.html").writeText("<html><title>Redist Title</title></html>")
        File(root, "MyGame/www").mkdirs()
        File(root, "MyGame/www/index.html").writeText("<html><title>Subdir Title</title></html>")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Subdir Title"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_subdirectoryScanIsCappedAtEightCandidates() {
        val root = temp.newFolder("subdirScanCap")
        // 10 direct subfolders, each individually a valid, distinct game candidate: the
        // subdirectory enumeration is take(8), and the final labels list is also take(8), so
        // the result must never exceed 8 entries.
        repeat(10) { i ->
            val sub = File(root, "Game$i/www").apply { mkdirs() }
            File(sub, "index.html").writeText("<html><title>Title Number $i</title></html>")
        }
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        // Exactly 8 subdirectories are scanned (take(8) on the subdir sequence) and each
        // contributes exactly one distinct title, so the total is deterministically 8 - not 10 -
        // regardless of which 8 of the 10 subfolders the filesystem happens to enumerate first.
        assertEquals(8, joiplayInternalTitleLabels(a).size)
    }

    // ---------------------------------------------------------------------
    // Ordering, dedup, and cross-reader combination on a single (direct) root.
    // ---------------------------------------------------------------------

    @Test
    fun joiplayInternalTitleLabels_ordersReadmeThenRenPyThenRpgmThenHtml() {
        val root = temp.newFolder("orderedRoot")
        // A readme.txt at the root (bare "readme" filename, highest naming priority).
        File(root, "readme.txt").writeText("Alpha Title One\n")
        // RenPy build_info.json.
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("""{"name":"Beta Build Name"}""")
        // RenPy options.rpy (also under game/, alongside cache/ above).
        File(root, "game/options.rpy").writeText("define config.name = \"Gamma Options Name\"")
        // RPGM System.json under www/data.
        File(root, "www/data").mkdirs()
        File(root, "www/data/System.json").writeText("""{"gameTitle":"Delta System Name"}""")
        // HTML title under www/.
        File(root, "www/index.html").writeText("<html><title>Epsilon Html Name</title></html>")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        // "game" directory exists directly under root, so this is a single direct root (no
        // subdirectory scan); the reader call order per root is: readme, RenPy build_info,
        // RenPy options, RPGM system, HTML title.
        assertEquals(
            listOf(
                "Alpha Title One",
                "Beta Build Name",
                "Gamma Options Name",
                "Delta System Name",
                "Epsilon Html Name",
            ),
            joiplayInternalTitleLabels(a),
        )
    }

    @Test
    fun joiplayInternalTitleLabels_dedupesIdenticalTitlesAcrossReaders() {
        val root = temp.newFolder("dedupRoot")
        File(root, "game/cache").mkdirs()
        File(root, "game/cache/build_info.json").writeText("""{"name":"Duplicate Title"}""")
        // options.rpy yields the exact same cleaned title as build_info.json above.
        File(root, "game/options.rpy").writeText("define config.name = \"Duplicate Title\"")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Duplicate Title"), joiplayInternalTitleLabels(a))
    }

    @Test
    fun joiplayInternalTitleLabels_readmeTitleBypassesCleanCatalogMetadataTitleBlocklist() {
        // Unlike the other four readers, readGameReadmeTitle's result is added to the output
        // directly, WITHOUT going through cleanCatalogMetadataTitle - so a readme-derived title
        // whose normalized form would otherwise be blocklisted (e.g. "game") is still accepted
        // as long as readGameReadmeTitle/isUsableReadmeTitle itself considers it usable.
        val root = temp.newFolder("readmeBypass")
        File(root, "readme.txt").writeText("Game\n")
        val a = app(source = AppSource.JoiPlay, storagePath = root.absolutePath)

        assertEquals(listOf("Game"), joiplayInternalTitleLabels(a))
    }
}
