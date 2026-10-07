package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterises the archive shapes AGM accepts and, more importantly, everything it refuses. The
 * layouts mirror real walkthrough/cheat mods; only their structure is reproduced, never their
 * content.
 */
class PatchArchiveScannerTest {

    private fun file(name: String, size: Long = 16L) =
        PatchArchiveEntry(name, size, PatchEntryKind.File)

    private fun directory(name: String) = PatchArchiveEntry(name, 0L, PatchEntryKind.Directory)

    private fun accepted(entries: List<PatchArchiveEntry>): PatchArchiveScan.Accepted {
        val scan = PatchArchiveScanner.scan(entries)
        assertTrue("expected acceptance but got $scan", scan is PatchArchiveScan.Accepted)
        return scan as PatchArchiveScan.Accepted
    }

    private fun rejection(entries: List<PatchArchiveEntry>): String {
        val scan = PatchArchiveScanner.scan(entries)
        assertTrue("expected rejection but got $scan", scan is PatchArchiveScan.Rejected)
        return (scan as PatchArchiveScan.Rejected).reason
    }

    @Test
    fun rootRenPyContentMergesIntoTheGameFolder() {
        val scan = accepted(
            listOf(
                file("options.rpy"),
                file("script.rpy"),
                file("scripts.rpa"),
                file("screens.rpy"),
            ),
        )
        assertNull(scan.wrapperFolder)
        assertEquals(PatchDestination.GameFolder, scan.destination)
        assertEquals(
            listOf("options.rpy", "screens.rpy", "script.rpy", "scripts.rpa"),
            scan.relativePaths,
        )
    }

    @Test
    fun singleWrapperFolderWithAGameTreeMergesIntoTheInstallRoot() {
        val scan = accepted(
            listOf(
                directory("WT_mod_v1/"),
                directory("WT_mod_v1/game/"),
                file("WT_mod_v1/game/script.rpy"),
                file("WT_mod_v1/game/gallery.rpy"),
                file("WT_mod_v1/game/images/thumb.png"),
            ),
        )
        assertEquals("WT_mod_v1", scan.wrapperFolder)
        assertEquals(PatchDestination.InstallRoot, scan.destination)
        assertEquals(
            listOf("game/gallery.rpy", "game/images/thumb.png", "game/script.rpy"),
            scan.relativePaths,
        )
    }

    @Test
    fun rootLevelGameFolderWithoutAWrapperIsAlsoAccepted() {
        val scan = accepted(
            listOf(
                directory("game/"),
                file("game/script.rpy"),
                file("game/extra/data.rpa"),
            ),
        )
        // A single top-level folder is stripped exactly the way the extractor strips it.
        assertEquals("game", scan.wrapperFolder)
        assertEquals(PatchDestination.GameFolder, scan.destination)
        assertEquals(listOf("extra/data.rpa", "script.rpy"), scan.relativePaths)
    }

    @Test
    fun windowsSeparatorsAreNormalised() {
        val scan = accepted(listOf(file("""wrap\game\script.rpy"""), file("""wrap\game\ui.rpy""")))
        assertEquals("wrap", scan.wrapperFolder)
        assertEquals(listOf("game/script.rpy", "game/ui.rpy"), scan.relativePaths)
    }

    @Test
    fun traversalIsRejected() {
        assertTrue(rejection(listOf(file("../script.rpy"))).contains("traverse"))
        assertTrue(rejection(listOf(file("game/../../script.rpy"))).contains("traverse"))
        assertTrue(rejection(listOf(file("./script.rpy"), file("a/b.rpy"))).contains("traverse"))
    }

    @Test
    fun absoluteAndDrivePathsAreRejected() {
        assertTrue(rejection(listOf(file("/etc/passwd"), file("a/b.rpy"))).contains("absolute"))
        assertTrue(rejection(listOf(file("""C:\game\script.rpy"""))).contains("drive"))
    }

    @Test
    fun emptySegmentsAndControlCharactersAreRejected() {
        assertTrue(rejection(listOf(file("game//script.rpy"))).contains("empty path segment"))
        assertTrue(rejection(listOf(file("game/scr\u0001ipt.rpy"))).contains("control character"))
    }

    @Test
    fun namesTheExtractorWouldRewriteAreRejectedInsteadOfSilentlyRenamed() {
        assertTrue(rejection(listOf(file("game/scr?ipt.rpy"))).contains("rewrite"))
        assertTrue(rejection(listOf(file("game/.hidden./data.rpy"))).contains("rewrite"))
    }

    @Test
    fun reservedAgmNamesAreRejectedAtTheArchiveRoot() {
        assertTrue(
            rejection(listOf(file(".agm-managed-game.json"), file("script.rpy")))
                .contains("reserved name '.agm-managed-game.json'"),
        )
        assertTrue(
            rejection(listOf(file(".agm-install.json"), file("script.rpy")))
                .contains("reserved name"),
        )
    }

    @Test
    fun reservedAgmNamesAreRejectedAtAnyDepth() {
        assertTrue(
            rejection(listOf(file("game/.agm-install.json"), file("game/script.rpy")))
                .contains("reserved name '.agm-install.json'"),
        )
        assertTrue(
            rejection(
                listOf(
                    file("wrap/game/images/deep/.agm-managed-game.json"),
                    file("wrap/game/script.rpy"),
                ),
            ).contains("reserved name"),
        )
        assertTrue(
            rejection(
                listOf(
                    directory("wrap/game/.agm-patch-tx/"),
                    file("wrap/game/.agm-patch-tx/payload.rpy"),
                    file("wrap/game/script.rpy"),
                ),
            ).contains("reserved name '.agm-patch-tx'"),
        )
    }

    @Test
    fun reservedAgmNamesAreRejectedRegardlessOfCase() {
        assertTrue(
            rejection(listOf(file("game/.AGM-Install.json"), file("game/script.rpy")))
                .contains("reserved name '.AGM-Install.json'"),
        )
        assertTrue(
            rejection(listOf(file("game/.Agm-anything"), file("game/script.rpy")))
                .contains("reserved name"),
        )
        assertTrue(
            rejection(
                listOf(directory("wrap/.AGM-Patch-Tx/"), file("wrap/.AGM-Patch-Tx/script.rpy")),
            ).contains("reserved name '.AGM-Patch-Tx'"),
        )
    }

    @Test
    fun patchTemporaryAndControlNamesAreRejected() {
        assertTrue(
            rejection(listOf(file("game/.agm-patch-part"), file("game/script.rpy")))
                .contains("reserved name"),
        )
        assertTrue(
            rejection(listOf(file(".agm-patch-abc/backup/script.rpy"), file("script.rpy")))
                .contains("reserved name '.agm-patch-abc'"),
        )
    }

    @Test
    fun namesThatMerelyContainTheReservedPrefixAreStillAccepted() {
        val scan = accepted(listOf(file("game/script.rpy.agm-bak-1"), file("game/script.rpy")))
        assertEquals(listOf("script.rpy", "script.rpy.agm-bak-1"), scan.relativePaths)
    }

    @Test
    fun linkEntriesAreRejected() {
        val reason = rejection(
            listOf(
                file("game/script.rpy"),
                PatchArchiveEntry("game/evil", 8L, PatchEntryKind.Link),
            ),
        )
        assertTrue(reason.contains("link entry"))
    }

    @Test
    fun duplicateEntriesAreRejected() {
        val reason = rejection(listOf(file("game/script.rpy"), file("game/script.rpy")))
        assertTrue(reason.contains("more than once"))
    }

    @Test
    fun fileAndFolderCollisionsAreRejected() {
        val reason = rejection(
            listOf(
                file("game/data"),
                file("game/data/inner.rpy"),
            ),
        )
        assertTrue(reason.contains("both a file and a folder"))
    }

    @Test
    fun oversizedEntriesAndArchivesAreRejected() {
        assertTrue(
            rejection(listOf(file("game/huge.rpa", PATCH_MAX_ENTRY_BYTES + 1)))
                .contains("per-file patch limit"),
        )
        val many = (1..5).map { file("game/part$it.rpa", PATCH_MAX_TOTAL_BYTES / 4) }
        assertTrue(rejection(many).contains("patch size limit"))
    }

    @Test
    fun emptyArchivesAreRejected() {
        assertTrue(rejection(emptyList()).contains("empty"))
        assertTrue(rejection(listOf(directory("game/"))).contains("no files"))
    }

    @Test
    fun unknownLayoutsAreRejected() {
        val reason = rejection(listOf(file("readme.txt"), file("setup.exe")))
        assertTrue(reason.contains("does not recognise this archive's layout"))
    }

    @Test
    fun ambiguousMixOfRootContentAndGameTreeIsRejected() {
        val reason = rejection(
            listOf(
                file("wrap/script.rpy"),
                file("wrap/game/other.rpy"),
            ),
        )
        assertTrue(reason.contains("does not recognise this archive's layout"))
    }
}
