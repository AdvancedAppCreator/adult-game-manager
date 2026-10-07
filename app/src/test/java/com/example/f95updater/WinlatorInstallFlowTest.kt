package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WinlatorInstallFlowTest {
    private val candidate = WinlatorExecutableCandidate(
        file = File("C:\\Games\\Example\\game.exe"),
        archiveTitle = "Example Game 1.2",
        executableTitle = "game",
        folderTitle = "Example",
    )

    @Test
    fun exposesEveryRequestedTitleSource() {
        assertEquals(WinlatorTitleSource.Archive, preferredWinlatorTitleSource(candidate))
        assertEquals(
            "Example Game 1.2",
            selectedWinlatorTitle(candidate, WinlatorTitleSource.Archive, ""),
        )
        assertEquals(
            "game",
            selectedWinlatorTitle(candidate, WinlatorTitleSource.Executable, ""),
        )
        assertEquals(
            "Example",
            selectedWinlatorTitle(candidate, WinlatorTitleSource.Folder, ""),
        )
        assertEquals(
            "My custom name",
            selectedWinlatorTitle(candidate, WinlatorTitleSource.Other, "  My custom name  "),
        )
    }
}
