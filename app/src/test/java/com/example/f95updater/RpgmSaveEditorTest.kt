package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RpgmSaveEditorTest {
    @Test
    fun backupNamesRemainUniqueWhenClockDoesNotAdvance() {
        val dir = createTempDir(prefix = "rpgm-backup-collision-")
        try {
            val save = File(dir, "file1.rpgsave").apply { writeText("""{"gold":1}""") }

            val first = RpgmSaveEditor.createBackup(save) { 1000L }
            val second = RpgmSaveEditor.createBackup(save) { 1000L }

            assertEquals("file1.rpgsave.agm-bak-1000", first.name)
            assertEquals("file1.rpgsave.agm-bak-1001", second.name)
            assertEquals(save.readText(), second.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    private val variables = listOf("", "Gold", "HeroLevel", "")   // ids 1..3 (0 unused)
    private val switches = listOf("", "MetHero", "DoorOpen")       // ids 1..2

    @Test
    fun mapsVariableNamesForCommonSavePaths() {
        assertEquals("Gold", RpgmSaveEditor.resolveName("variables._data.@a[1]", variables, switches))
        assertEquals("HeroLevel", RpgmSaveEditor.resolveName("variables._data[2]", variables, switches))
        assertEquals("Gold", RpgmSaveEditor.resolveName("variables.@a[1]", variables, switches))
        assertEquals("Gold", RpgmSaveEditor.resolveName("variables[1]", variables, switches))
    }

    @Test
    fun mapsSwitchNames() {
        assertEquals("MetHero", RpgmSaveEditor.resolveName("switches._data.@a[1]", variables, switches))
        assertEquals("DoorOpen", RpgmSaveEditor.resolveName("switches._data[2]", variables, switches))
    }

    @Test
    fun returnsNullForUnnamedOrUnrelatedPaths() {
        // Blank name in the System.json array.
        assertNull(RpgmSaveEditor.resolveName("variables._data.@a[3]", variables, switches))
        // Out of range.
        assertNull(RpgmSaveEditor.resolveName("variables._data.@a[99]", variables, switches))
        // Not a variable/switch path.
        assertNull(RpgmSaveEditor.resolveName("party._gold[0]", variables, switches))
        assertNull(RpgmSaveEditor.resolveName("actors._data.@a[1].name", variables, switches))
    }

    private val names = RpgmSaveEditor.RpgmDataNames(
        variables = variables,
        switches = switches,
        items = listOf("", "Potion", "Ether"),
        weapons = listOf("", "Sword"),
        armors = listOf("", "Shield"),
        actors = listOf("", "Hero", "Mage"),
    )

    @Test
    fun categorizesPartyGoldAndSteps() {
        RpgmSaveEditor.describePath("party._gold", names).let {
            assertEquals("Gold", it.label); assertEquals(RpgmCategory.PARTY, it.category)
        }
        assertEquals(RpgmCategory.PARTY, RpgmSaveEditor.describePath("party._steps", names).category)
    }

    @Test
    fun categorizesInventoryWithNames() {
        RpgmSaveEditor.describePath("party._items.1", names).let {
            assertEquals(RpgmCategory.INVENTORY, it.category)
            assertTrue(it.label!!.contains("Potion"))
        }
        RpgmSaveEditor.describePath("party._weapons[1]", names).let {
            assertEquals(RpgmCategory.INVENTORY, it.category)
            assertTrue(it.label!!.contains("Sword"))
        }
    }

    @Test
    fun categorizesActorFields() {
        RpgmSaveEditor.describePath("actors._data.@a[1]._level", names).let {
            assertEquals(RpgmCategory.ACTORS, it.category)
            assertEquals("Hero · Level", it.label)
        }
        RpgmSaveEditor.describePath("actors._data.@a[2]._hp", names).let {
            assertEquals(RpgmCategory.ACTORS, it.category)
            assertEquals("Mage · HP", it.label)
        }
        // Nested exp classId path still resolves to the EXP label.
        assertEquals("Hero · EXP", RpgmSaveEditor.describePath("actors._data.@a[1]._exp.1", names).label)
    }

    @Test
    fun categorizesVariablesAndSwitches() {
        assertEquals(RpgmCategory.VARIABLES, RpgmSaveEditor.describePath("variables._data.@a[1]", names).category)
        assertEquals(RpgmCategory.SWITCHES, RpgmSaveEditor.describePath("switches._data.@a[1]", names).category)
    }

    @Test
    fun unrelatedPathsAreOther() {
        RpgmSaveEditor.describePath("map._displayX", names).let {
            assertNull(it.label); assertEquals(RpgmCategory.OTHER, it.category)
        }
    }
}
