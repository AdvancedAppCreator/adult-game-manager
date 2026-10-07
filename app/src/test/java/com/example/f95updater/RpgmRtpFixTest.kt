package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RpgmRtpFixTest {
    private val vxAceIni =
        "[Game]\r\nRTP=RPGVXAce\r\nLibrary=System\\RGSS301.dll\r\nScripts=Data\\Scripts.rvdata2\r\nTitle=Sample\r\n"

    @Test
    fun detectsNonEmptyRtpLine() {
        assertTrue(RpgmRtpFix.needsBlanking(vxAceIni))
        assertTrue(RpgmRtpFix.needsBlanking("[Game]\nrtp = RPGVX\n"))
        assertFalse(RpgmRtpFix.needsBlanking("[Game]\r\nRTP=\r\nTitle=X\r\n"))
        assertFalse(RpgmRtpFix.needsBlanking("[Game]\r\nTitle=X\r\n"))
    }

    @Test
    fun blanksRtpValuePreservingCrlfAndOtherLines() {
        val fixed = RpgmRtpFix.blankRtp(vxAceIni)
        assertEquals(
            "[Game]\r\nRTP=\r\nLibrary=System\\RGSS301.dll\r\nScripts=Data\\Scripts.rvdata2\r\nTitle=Sample\r\n",
            fixed,
        )
        // CRLF preserved (no lone-LF conversion introduced).
        assertTrue(fixed.contains("RTP=\r\n"))
        assertFalse(RpgmRtpFix.needsBlanking(fixed))
    }

    @Test
    fun blankRtpIsIdempotent() {
        val once = RpgmRtpFix.blankRtp(vxAceIni)
        assertEquals(once, RpgmRtpFix.blankRtp(once))
    }

    @Test
    fun blankRtpPreservesKeySpacing() {
        assertEquals("[Game]\r\nRTP =\r\n", RpgmRtpFix.blankRtp("[Game]\r\nRTP = RPGVXAce\r\n"))
    }

    @Test
    fun detectsSelfContainedByArchive() {
        val dir = createTempDir()
        try {
            File(dir, "Game.rgss3a").writeText("x")
            File(dir, "Game.ini").writeText(vxAceIni)
            assertTrue(RpgmRtpFix.isSelfContainedRgss(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun detectsSelfContainedByUnencryptedAssets() {
        val dir = createTempDir()
        try {
            File(dir, "Data").mkdir()
            File(dir, "Graphics").mkdir()
            assertTrue(RpgmRtpFix.isSelfContainedRgss(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun notSelfContainedWhenNoAssets() {
        val dir = createTempDir()
        try {
            File(dir, "Game.ini").writeText(vxAceIni)
            assertFalse(RpgmRtpFix.isSelfContainedRgss(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyIfNeededBlanksAndBacksUpSelfContainedGame() {
        val dir = createTempDir()
        try {
            File(dir, "Game.rgss3a").writeText("packed")
            val ini = File(dir, "Game.ini").apply { writeText(vxAceIni, Charsets.ISO_8859_1) }

            assertTrue(RpgmRtpFix.applyIfNeeded(dir.absolutePath))

            assertFalse(RpgmRtpFix.needsBlanking(ini.readText(Charsets.ISO_8859_1)))
            assertTrue(File(dir, "Game.ini.agmbak").isFile)
            // Backup retains the original RTP value.
            assertTrue(RpgmRtpFix.needsBlanking(File(dir, "Game.ini.agmbak").readText(Charsets.ISO_8859_1)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyIfNeededLeavesRtpDependentGameUntouched() {
        val dir = createTempDir()
        try {
            // No archive and no Data/Graphics -> genuinely RTP-dependent, must not be edited.
            val ini = File(dir, "Game.ini").apply { writeText(vxAceIni, Charsets.ISO_8859_1) }
            assertFalse(RpgmRtpFix.applyIfNeeded(dir.absolutePath))
            assertTrue(RpgmRtpFix.needsBlanking(ini.readText(Charsets.ISO_8859_1)))
            assertFalse(File(dir, "Game.ini.agmbak").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyIfNeededIsIdempotentNoSecondBackup() {
        val dir = createTempDir()
        try {
            File(dir, "Game.rgss3a").writeText("packed")
            File(dir, "Game.ini").writeText(vxAceIni, Charsets.ISO_8859_1)
            assertTrue(RpgmRtpFix.applyIfNeeded(dir.absolutePath))
            val backup = File(dir, "Game.ini.agmbak")
            val firstBackupText = backup.readText(Charsets.ISO_8859_1)
            // Second run: nothing to blank, backup unchanged.
            assertFalse(RpgmRtpFix.applyIfNeeded(dir.absolutePath))
            assertEquals(firstBackupText, backup.readText(Charsets.ISO_8859_1))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyIfNeededHandlesNestedGameFolder() {
        val root = createTempDir()
        try {
            val nested = File(root, "LHReiwa_eng").apply { mkdir() }
            File(nested, "Game.rgss3a").writeText("packed")
            val ini = File(nested, "Game.ini").apply { writeText(vxAceIni, Charsets.ISO_8859_1) }
            assertTrue(RpgmRtpFix.applyIfNeeded(root.absolutePath))
            assertFalse(RpgmRtpFix.needsBlanking(ini.readText(Charsets.ISO_8859_1)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyIfNeededToleratesBlankOrMissingPath() {
        assertFalse(RpgmRtpFix.applyIfNeeded(null))
        assertFalse(RpgmRtpFix.applyIfNeeded("   "))
        assertFalse(RpgmRtpFix.applyIfNeeded(File(createTempDir(), "does-not-exist").absolutePath))
    }
}
