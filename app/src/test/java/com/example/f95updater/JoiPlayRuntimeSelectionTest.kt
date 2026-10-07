package com.example.f95updater

import com.example.f95updater.JoiPlayLauncher.RuntimeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies AGM's JoiPlay runtime selection mirrors JoiPlay 1.21.000's own algorithm
 * (decompiled from cyou.joiplay.joiplay.utilities.r0.d()). Ground truth for the primary
 * case comes from the real device: TheBigStepSeason2-0.3-pc has game/script_version.txt
 * = "(8, 0, 3)" and JoiPlay runs it under Ren'Py 8.2.1 (v8d2d1), NOT the 7.5.3 that
 * renpy/__init__.py reports (which AGM used to pick, causing the pickle-protocol-5 crash).
 */
class JoiPlayRuntimeSelectionTest {

    private val installed = listOf(
        RuntimeCandidate("cyou.joiplay.runtime.renpy.v7d4d11", 70411, listOf("renpy", "renpy7")),
        RuntimeCandidate("cyou.joiplay.runtime.renpy.v7d7d1", 70701, listOf("renpy", "renpy7")),
        RuntimeCandidate("cyou.joiplay.runtime.renpy.v8d2d1", 80201, listOf("renpy", "renpy8")),
        RuntimeCandidate("cyou.joiplay.runtime.renpy.v8d4d1", 80500, listOf("renpy", "renpy8")),
    )

    @Test
    fun encodesRenpyVersionLikeJoiPlay() {
        assertEquals(80003, JoiPlayLauncher.encodeScriptVersion("(8, 0, 3)"))
        assertEquals(70701, JoiPlayLauncher.encodeScriptVersion("(7, 7, 1)"))
        assertEquals(80201, JoiPlayLauncher.encodeScriptVersion("(8, 2, 1)"))
        assertEquals(81011, JoiPlayLauncher.encodeScriptVersion("(8, 10, 11)"))
        // two-component version -> patch defaults to "00"
        assertEquals(80000, JoiPlayLauncher.encodeScriptVersion("(8, 0)"))
        assertNull(JoiPlayLauncher.encodeScriptVersion("garbage"))
    }

    @Test
    fun theBigStepGamePicksRenpy821NotTheReportedForToken() {
        // script_version.txt = (8, 0, 3) -> 80003; smallest installed runtime >= 80003 is 80201.
        val chosen = JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = 80003, hasPython2Lib = false)
        assertEquals("cyou.joiplay.runtime.renpy.v8d2d1", chosen)
    }

    @Test
    fun picksSmallestRuntimeAtOrAboveGameVersion() {
        assertEquals(
            "cyou.joiplay.runtime.renpy.v7d7d1",
            JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = 70701, hasPython2Lib = false),
        )
        assertEquals(
            "cyou.joiplay.runtime.renpy.v8d4d1",
            JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = 80300, hasPython2Lib = false),
        )
    }

    @Test
    fun exactVersionMatchIsSelected() {
        assertEquals(
            "cyou.joiplay.runtime.renpy.v8d2d1",
            JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = 80201, hasPython2Lib = false),
        )
    }

    @Test
    fun gameNewerThanAllRuntimesFallsBackToMax() {
        assertEquals(
            "cyou.joiplay.runtime.renpy.v8d4d1",
            JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = 99999, hasPython2Lib = false),
        )
    }

    @Test
    fun noScriptVersionPython3FallbackPicksMaxPy3() {
        // No script_version.* and no py2 lib and type doesn't imply py2 -> newest py3 (>=80000).
        assertEquals(
            "cyou.joiplay.runtime.renpy.v8d4d1",
            JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = null, hasPython2Lib = false),
        )
    }

    @Test
    fun noScriptVersionPython2FallbackWhenLegacyEngine() {
        assertEquals(
            "cyou.joiplay.runtime.renpy.v7d7d1",
            JoiPlayLauncher.chooseRuntime("legacyrenpy", installed, gameVersion = null, hasPython2Lib = false),
        )
    }

    @Test
    fun noScriptVersionPython2FallbackWhenPy2LibPresent() {
        assertEquals(
            "cyou.joiplay.runtime.renpy.v7d7d1",
            JoiPlayLauncher.chooseRuntime("renpy", installed, gameVersion = null, hasPython2Lib = true),
        )
    }

    @Test
    fun emptyCandidatesReturnsNull() {
        assertNull(JoiPlayLauncher.chooseRuntime("renpy", emptyList(), gameVersion = 80003, hasPython2Lib = false))
    }
}
