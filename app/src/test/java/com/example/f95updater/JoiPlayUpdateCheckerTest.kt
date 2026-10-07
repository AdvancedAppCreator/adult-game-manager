package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoiPlayUpdateCheckerTest {

    private val manifest = """
        [
          { "id": 1, "title": "JoiPlay", "version": "1.21.000", "type": "APK", "size": "26.4 MB",
            "description": "Main application", "link": "https://mega.nz/file/core", "date": "2026-02-14", "badge": "Required" },
          { "id": 2, "title": "RPG Maker Plugin", "version": "1.22.00", "type": "APK", "size": "80.0 MB",
            "description": "Required for RPG Maker", "link": "https://mega.nz/file/rm", "date": "2026-02-14", "badge": "RPG Maker" },
          { "id": 3, "title": "Ren'Py 8.5 Plugin", "version": "1.01.00", "type": "APK", "size": "53.4 MB",
            "description": "Required for Ren'Py 8+", "link": "https://mega.nz/file/rp", "date": "2026-02-14", "badge": "Ren'Py" }
        ]
    """.trimIndent()

    @Test
    fun parsesManifestAndPicksCoreByTitle() {
        val downloads = JoiPlayUpdateChecker.parse(manifest)
        assertEquals(3, downloads.size)
        val core = JoiPlayUpdateChecker.core(downloads)
        assertEquals("JoiPlay", core?.title)
        assertEquals("1.21.000", core?.version)
    }

    @Test
    fun coreFallsBackToRequiredBadge() {
        val downloads = JoiPlayUpdateChecker.parse(
            """[{ "id": 9, "title": "JoiPlay Core", "version": "1.21.000", "type": "APK", "badge": "Required" }]""",
        )
        assertEquals(9, JoiPlayUpdateChecker.core(downloads)?.id)
    }

    @Test
    fun malformedManifestParsesToEmpty() {
        assertTrue(JoiPlayUpdateChecker.parse("not json").isEmpty())
        assertTrue(JoiPlayUpdateChecker.parse("").isEmpty())
    }

    private fun status(installed: String?): JoiPlayUpdateChecker.Status {
        val downloads = JoiPlayUpdateChecker.parse(manifest)
        return JoiPlayUpdateChecker.Status(installed, JoiPlayUpdateChecker.core(downloads), downloads)
    }

    @Test
    fun updateAvailableOnlyWhenInstalledIsStrictlyOlder() {
        assertTrue(status("1.20.611").updateAvailable)
        assertTrue(status("1.20.999").updateAvailable)
        assertFalse(status("1.21.000").updateAvailable)
        // Equivalent numeric value with fewer components is not an update.
        assertFalse(status("1.21").updateAvailable)
        // Installed ahead of the site (e.g. a newer Patreon build) is not an update.
        assertFalse(status("1.22.000").updateAvailable)
        // Not installed -> no update nudge.
        assertFalse(status(null).updateAvailable)
        assertNull(status(null).installedVersion)
    }

    @Test
    fun versionCompareOrdersNumericComponents() {
        assertTrue(VersionCompare.compare("1.21.000", "1.20.611") > 0)
        assertTrue(VersionCompare.compare("1.20.611", "1.21.000") < 0)
        assertEquals(0, VersionCompare.compare("1.21", "1.21.000"))
        assertEquals(0, VersionCompare.compare("1.21.0", "1.21.000"))
        assertTrue(VersionCompare.compare("2.0", "1.99.99") > 0)
    }
}
