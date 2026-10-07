package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT behavior of the pure Winlator decision helpers
 * ([preferredWinlatorTitleSource], [selectedWinlatorTitle], [shouldRefreshAfterWinlatorOperation],
 * [shouldMatchCatalogAfterWinlatorOperation], [shouldRefreshAfterWinlatorEvent]) as implemented in
 * MainActivity.kt (package com.example.f95updater). These helpers are `internal`, so this test
 * (same module, same package, unit test source set) can call them without imports. A concurrent
 * behavior-preserving refactor may move them verbatim to WinlatorInstallModel.kt in the same
 * package; these tests should keep resolving and passing either way.
 *
 * These tests intentionally document existing behavior (including edge cases) rather than
 * desired behavior, to guard against accidental changes during the refactor. The helpers never
 * touch the filesystem -- only [WinlatorExecutableCandidate.file] is a real `java.io.File`, and it
 * is not read from -- so a dummy, non-existent file is used throughout.
 */
class WinlatorDecisionHelpersCharacterizationTest {

    private val dummyFile = java.io.File("x")

    // ---- preferredWinlatorTitleSource ----

    @Test
    fun archivePresentYieldsArchiveSourceRegardlessOfOtherFields() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = "MyArchive",
            executableTitle = "MyExe",
            folderTitle = "MyFolder",
            readmeTitle = "MyReadme",
        )

        assertEquals(WinlatorTitleSource.Archive, preferredWinlatorTitleSource(candidate))
    }

    @Test
    fun archiveBlankOrNullFallsThroughToFolderWhenFolderPresent() {
        val blankArchive = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = "   ",
            executableTitle = "MyExe",
            folderTitle = "MyFolder",
        )
        val nullArchive = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = null,
            executableTitle = "MyExe",
            folderTitle = "MyFolder",
        )

        assertEquals(WinlatorTitleSource.Folder, preferredWinlatorTitleSource(blankArchive))
        assertEquals(WinlatorTitleSource.Folder, preferredWinlatorTitleSource(nullArchive))
    }

    @Test
    fun archiveAndFolderBothAbsentFallsThroughToExecutableWhenNonBlank() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = null,
            executableTitle = "MyExe",
            folderTitle = "  ",
        )

        assertEquals(WinlatorTitleSource.Executable, preferredWinlatorTitleSource(candidate))
    }

    @Test
    fun allTitleFieldsBlankOrAbsentYieldsOther() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = null,
            executableTitle = "   ",
            folderTitle = null,
        )

        assertEquals(WinlatorTitleSource.Other, preferredWinlatorTitleSource(candidate))
    }

    // ---- selectedWinlatorTitle ----

    @Test
    fun archiveSourceReturnsTrimmedArchiveTitle() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = "  My Archive  ",
            executableTitle = "MyExe",
        )

        assertEquals("My Archive", selectedWinlatorTitle(candidate, WinlatorTitleSource.Archive, "custom"))
    }

    @Test
    fun executableSourceReturnsTrimmedExecutableTitle() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            executableTitle = "  My Exe  ",
        )

        assertEquals("My Exe", selectedWinlatorTitle(candidate, WinlatorTitleSource.Executable, "custom"))
    }

    @Test
    fun folderSourceReturnsTrimmedFolderTitle() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            executableTitle = "MyExe",
            folderTitle = "  My Folder  ",
        )

        assertEquals("My Folder", selectedWinlatorTitle(candidate, WinlatorTitleSource.Folder, "custom"))
    }

    @Test
    fun readmeSourceReturnsTrimmedReadmeTitle() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            executableTitle = "MyExe",
            readmeTitle = "  My Readme  ",
        )

        assertEquals("My Readme", selectedWinlatorTitle(candidate, WinlatorTitleSource.Readme, "custom"))
    }

    @Test
    fun otherSourceReturnsTrimmedCustomTitle() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            executableTitle = "MyExe",
        )

        assertEquals("Custom Name", selectedWinlatorTitle(candidate, WinlatorTitleSource.Other, "  Custom Name  "))
    }

    @Test
    fun nullBackingFieldForSelectedSourceYieldsEmptyString() {
        val candidate = WinlatorExecutableCandidate(
            file = dummyFile,
            archiveTitle = null,
            executableTitle = "MyExe",
            folderTitle = null,
            readmeTitle = null,
        )

        assertEquals("", selectedWinlatorTitle(candidate, WinlatorTitleSource.Archive, "custom"))
        assertEquals("", selectedWinlatorTitle(candidate, WinlatorTitleSource.Folder, "custom"))
        assertEquals("", selectedWinlatorTitle(candidate, WinlatorTitleSource.Readme, "custom"))
    }

    // ---- shouldRefreshAfterWinlatorOperation ----

    @Test
    fun nullOperationKindDoesNotTriggerRefresh() {
        assertFalse(shouldRefreshAfterWinlatorOperation(null))
    }

    @Test
    fun launchOperationKindDoesNotTriggerRefresh() {
        assertFalse(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.Launch))
    }

    @Test
    fun nonLaunchNonNullOperationKindsTriggerRefresh() {
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.CreatePortable))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.CreateInstaller))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.Configure))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.RunInstaller))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.Delete))
        assertTrue(shouldRefreshAfterWinlatorOperation(WinlatorOperationKind.MoveToIsolated))
    }

    // ---- shouldMatchCatalogAfterWinlatorOperation ----

    @Test
    fun createPortableAndCreateInstallerTriggerCatalogMatch() {
        assertTrue(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.CreatePortable))
        assertTrue(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.CreateInstaller))
    }

    @Test
    fun otherOperationKindsAndNullDoNotTriggerCatalogMatch() {
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(null))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.Configure))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.RunInstaller))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.Launch))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.Delete))
        assertFalse(shouldMatchCatalogAfterWinlatorOperation(WinlatorOperationKind.MoveToIsolated))
    }

    // ---- shouldRefreshAfterWinlatorEvent ----

    @Test
    fun installCompletedAndInstallFailedTriggerRefresh() {
        assertTrue(shouldRefreshAfterWinlatorEvent("install_completed"))
        assertTrue(shouldRefreshAfterWinlatorEvent("install_failed"))
        assertTrue(shouldRefreshAfterWinlatorEvent("settings_changed"))
    }

    @Test
    fun otherOrEmptyEventTypesDoNotTriggerRefresh() {
        assertFalse(shouldRefreshAfterWinlatorEvent("install_started"))
        assertFalse(shouldRefreshAfterWinlatorEvent(""))
    }

    @Test
    fun eventTypeMatchIsCaseSensitiveExactMatch() {
        assertFalse(shouldRefreshAfterWinlatorEvent("INSTALL_COMPLETED"))
        assertFalse(shouldRefreshAfterWinlatorEvent("Install_Completed"))
        assertFalse(shouldRefreshAfterWinlatorEvent("install_completed "))
        assertFalse(shouldRefreshAfterWinlatorEvent(" install_failed"))
    }
}
