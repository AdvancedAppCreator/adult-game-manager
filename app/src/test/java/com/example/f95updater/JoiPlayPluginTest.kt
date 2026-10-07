package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoiPlayPluginTest {

    // ---- Registry --------------------------------------------------------

    @Test
    fun tier_splits_plugin_and_core_engines() {
        assertEquals(JoiPlayTier.Plugin, JoiPlayPluginRegistry.tier("renpy"))
        assertEquals(JoiPlayTier.Plugin, JoiPlayPluginRegistry.tier("rpgmvxa"))
        assertEquals(JoiPlayTier.Plugin, JoiPlayPluginRegistry.tier("godot4"))
        assertEquals(JoiPlayTier.Plugin, JoiPlayPluginRegistry.tier("ruffle"))
        assertEquals(JoiPlayTier.Core, JoiPlayPluginRegistry.tier("rpgmmv"))
        assertEquals(JoiPlayTier.Core, JoiPlayPluginRegistry.tier("rpgmmz"))
        assertEquals(JoiPlayTier.Core, JoiPlayPluginRegistry.tier("tyrano"))
        assertNull(JoiPlayPluginRegistry.tier("totally-unknown"))
    }

    @Test
    fun engine_lookup_is_case_insensitive_and_trimmed() {
        assertEquals("renpy", JoiPlayPluginRegistry.engine(" RENPY ")?.type)
        assertTrue(JoiPlayPluginRegistry.isPluginTier("RenPy"))
        assertTrue(JoiPlayPluginRegistry.isCoreTier("RPGMMV"))
    }

    @Test
    fun runtime_action_and_display_names() {
        assertEquals("cyou.joiplay.runtime.renpy.run", JoiPlayPluginRegistry.runtimeAction("renpy"))
        assertEquals("cyou.joiplay.runtime.godot4.run", JoiPlayPluginRegistry.runtimeAction("godot4"))
        assertEquals("RPG Maker VX Ace", JoiPlayPluginRegistry.displayName("rpgmvxa"))
        assertEquals("Ren'Py", JoiPlayPluginRegistry.displayName("renpy"))
        // Unknown engine falls back to its raw type.
        assertEquals("weirdengine", JoiPlayPluginRegistry.displayName("weirdengine"))
    }

    @Test
    fun vx_ace_runtime_action_uses_the_plugins_exported_alias() {
        assertEquals("rpgmvxace", JoiPlayPluginRegistry.runtimeEngineType("rpgmvxa"))
        assertEquals("rpgmxp", JoiPlayPluginRegistry.runtimeEngineType("rpgmxp"))
        assertEquals(
            listOf(
                "cyou.joiplay.runtime.rpgmvxace.run",
                "cyou.joiplay.runtime.rpgmvxa.run",
            ),
            JoiPlayPluginRegistry.runtimeActionCandidates("rpgmvxa"),
        )
        assertEquals(
            listOf("cyou.joiplay.runtime.rpgmxp.run"),
            JoiPlayPluginRegistry.runtimeActionCandidates("rpgmxp"),
        )
        assertEquals(
            listOf("cyou.joiplay.runtime.rpgmvx.run"),
            JoiPlayPluginRegistry.runtimeActionCandidates("rpgmvx"),
        )
        assertEquals(
            listOf("cyou.joiplay.runtime.renpy.run"),
            JoiPlayPluginRegistry.runtimeActionCandidates("renpy"),
        )
    }

    @Test
    fun plugin_engine_types_are_only_plugin_tier() {
        assertTrue(JoiPlayPluginRegistry.pluginEngineTypes.contains("renpy"))
        assertFalse(JoiPlayPluginRegistry.pluginEngineTypes.contains("rpgmmv"))
    }

    // ---- Label / title-stem matching -------------------------------------

    @Test
    fun titleStem_strips_for_joiplay_suffix() {
        assertEquals("Ren'Py 8.3.7 Plugin", JoiPlayPluginDiscovery.titleStemOf("Ren'Py 8.3.7 Plugin for JoiPlay"))
        assertEquals("Ruffle Plugin", JoiPlayPluginDiscovery.titleStemOf("Ruffle Plugin"))
        assertEquals("RPG Maker Plugin", JoiPlayPluginDiscovery.titleStemOf("RPG Maker Plugin for JoiPlay "))
    }

    @Test
    fun installed_plugin_serves_matches_case_insensitively() {
        val p = InstalledJoiPlayPlugin("pkg", "Ren'Py 8.3.7 Plugin for JoiPlay", "Ren'Py 8.3.7 Plugin", "1.00.60", 80307, listOf("renpy", "legacyrenpy"))
        assertTrue(p.serves("RenPy"))
        assertTrue(p.serves("legacyrenpy"))
        assertFalse(p.serves("godot4"))
    }

    // ---- Checker: manifest matching + recommendation ---------------------

    private val downloads = listOf(
        dl("JoiPlay", "1.21.000", badge = "Required"),
        dl("RPG Maker Plugin", "1.22.00"),
        dl("Ren'Py 8.5 Plugin", "1.01.00"),
        dl("Ren'Py 8.3.7 Plugin", "1.00.60"),
        dl("Ren'Py 7.7.1 Plugin", "1.00.60"),
        dl("Ruffle Plugin", "1.02.00"),
        dl("Godot 4.3 Plugin", "1.00.60"),
        dl("Godot 3.6 Plugin", "1.00.50"),
    )

    private fun dl(title: String, version: String, badge: String = "") =
        JoiPlayDownload(
            id = title.hashCode(),
            title = title,
            version = version,
            type = "APK",
            size = "10 MB",
            link = "https://mega.nz/file/x#y",
            badge = badge,
        )

    @Test
    fun parseFamilyVersion_extracts_embedded_version() {
        assertEquals("8.3.7", JoiPlayPluginChecker.parseFamilyVersion("Ren'Py 8.3.7 Plugin", "Ren'Py"))
        assertEquals("4.3", JoiPlayPluginChecker.parseFamilyVersion("Godot 4.3 Plugin", "Godot"))
        assertNull(JoiPlayPluginChecker.parseFamilyVersion("Ruffle Plugin", "Ren'Py"))
    }

    @Test
    fun matchLatest_matches_by_title_stem() {
        val plugin = InstalledJoiPlayPlugin("pkg", "Ren'Py 8.3.7 Plugin for JoiPlay", "Ren'Py 8.3.7 Plugin", "1.00.60-patreon", 80307, listOf("renpy"))
        val latest = JoiPlayPluginChecker.matchLatest(plugin, downloads)
        assertEquals("Ren'Py 8.3.7 Plugin", latest?.title)
        assertEquals("1.00.60", latest?.version)
        // The "-patreon" build suffix must NOT read as older than the plain manifest version.
        assertTrue(VersionCompare.compare(latest!!.version, plugin.versionName) <= 0)
    }

    @Test
    fun recommend_renpy_picks_smallest_family_version_covering_the_game() {
        // A 8.3.7 game -> the 8.3.7 plugin (smallest family version >= game version).
        assertEquals("Ren'Py 8.3.7 Plugin", JoiPlayPluginChecker.recommend("renpy", "8.3.7", downloads)?.title)
        // A 8.4 game -> jump up to 8.5 (8.3.7 doesn't cover it).
        assertEquals("Ren'Py 8.5 Plugin", JoiPlayPluginChecker.recommend("renpy", "8.4.0", downloads)?.title)
        // A 7.5 game -> the 7.7.1 plugin.
        assertEquals("Ren'Py 7.7.1 Plugin", JoiPlayPluginChecker.recommend("renpy", "7.5.0", downloads)?.title)
        // Unknown version -> newest family entry.
        assertEquals("Ren'Py 8.5 Plugin", JoiPlayPluginChecker.recommend("renpy", null, downloads)?.title)
    }

    @Test
    fun recommend_single_family_engines() {
        assertEquals("RPG Maker Plugin", JoiPlayPluginChecker.recommend("rpgmvxa", null, downloads)?.title)
        assertEquals("RPG Maker Plugin", JoiPlayPluginChecker.recommend("rpgmxp", "1.0", downloads)?.title)
        assertEquals("Ruffle Plugin", JoiPlayPluginChecker.recommend("ruffle", null, downloads)?.title)
        assertEquals("Godot 4.3 Plugin", JoiPlayPluginChecker.recommend("godot4", null, downloads)?.title)
        assertEquals("Godot 3.6 Plugin", JoiPlayPluginChecker.recommend("godot3", null, downloads)?.title)
    }

    @Test
    fun recommend_returns_null_for_core_tier_engines() {
        assertNull(JoiPlayPluginChecker.recommend("rpgmmv", null, downloads))
        assertNull(JoiPlayPluginChecker.recommend("tyrano", "1.0", downloads))
    }
}
