package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFolderTreeTest {
    @Test
    fun buildsPrimaryStorageHierarchyAndVirtualAndroidGroup() {
        val entries = buildLibraryFolderTree(
            listOf(
                row("managed-a", AppSource.Managed, "/storage/emulated/0/Download/Games/Alpha"),
                row("managed-b", AppSource.Managed, "/storage/emulated/0/Download/Games/Beta"),
                row("android", AppSource.Android, null),
            ),
        )

        val folders = entries.filterIsInstance<LibraryListEntry.Folder>()
        assertEquals(
            listOf("Android apps", "Internal storage", "Download", "Games", "Alpha", "Beta"),
            folders.map { it.label },
        )
        assertEquals(2, folders.first { it.label == "Internal storage" }.gameCount)
        assertEquals(
            setOf("managed-a", "managed-b", "android"),
            entries.filterIsInstance<LibraryListEntry.Game>().map { it.row.installed.packageName }.toSet(),
        )
    }

    @Test
    fun collapsedFolderHidesDescendantsAndForceExpandedRevealsSearchResults() {
        val rows = listOf(
            row("managed", AppSource.Managed, "/storage/emulated/0/Download/Games/Alpha"),
        )
        val expanded = buildLibraryFolderTree(rows)
        val internal = expanded.filterIsInstance<LibraryListEntry.Folder>()
            .first { it.label == "Internal storage" }

        val collapsed = buildLibraryFolderTree(
            rows,
            collapsedFolders = setOf(internal.key.removePrefix("folder:")),
        )
        assertFalse(collapsed.any { it is LibraryListEntry.Game })

        val forced = buildLibraryFolderTree(
            rows,
            collapsedFolders = setOf(internal.key.removePrefix("folder:")),
            forceExpanded = true,
        )
        assertTrue(forced.any { it is LibraryListEntry.Game })
    }

    @Test
    fun preservesIncomingGameOrderWithinSameFolder() {
        val rows = listOf(
            row("second", AppSource.Managed, "/storage/emulated/0/Games/Same"),
            row("first", AppSource.Managed, "/storage/emulated/0/Games/Same"),
        )

        assertEquals(
            listOf("second", "first"),
            buildLibraryFolderTree(rows)
                .filterIsInstance<LibraryListEntry.Game>()
                .map { it.row.installed.packageName },
        )
    }

    private fun row(packageName: String, source: AppSource, path: String?) = AppRow(
        installed = InstalledApp(
            packageName = packageName,
            label = packageName,
            versionName = "",
            versionCode = 0,
            source = source,
            storagePath = path,
        ),
        mapping = null,
        status = UpdateStatus.Unknown,
    )
}
