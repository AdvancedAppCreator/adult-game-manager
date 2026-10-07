package com.example.f95updater

/**
 * Pure, Android-free heuristics that guess a game's engine from the file names present in an
 * extracted folder (or archive listing) and recommend whether it should run under Winlator (Wine)
 * or JoiPlay (native interpreters). Mirrors the engine->runner map agreed with the Winlator Secure
 * (PC_Emulator) session. Advisory only — the caller owns the final routing decision.
 */
object InstallRecommendation {

    enum class GameEngine {
        RENPY,
        RPGM_RGSS,   // RPG Maker XP/VX/VX Ace (RGSS runtime)
        RPGM_MV_MZ,  // RPG Maker MV/MZ (NW.js)
        WOLF_RPG,
        KIRIKIRI,
        UNITY,
        UNREAL,
        HTML,
        NATIVE_OR_UNKNOWN,
    }

    enum class Runner { Winlator, JoiPlay, Kirikiroid }

    enum class Confidence { High, Medium, Low }

    data class RunnerAdvice(
        val engine: GameEngine,
        val recommended: Runner,
        /** True when JoiPlay's native interpreters can actually run this engine. */
        val joiPlayCapable: Boolean,
        val confidence: Confidence,
        val reasons: List<String>,
        /** True when the Kirikiroid2 emulator can run this engine (KiriKiri/KAG/TJS2/XP3). */
        val kirikiroidCapable: Boolean = false,
    )

    /** JoiPlay ships interpreters for these engines; everything else must use Winlator/Wine. */
    private val joiPlayCapableEngines = setOf(
        GameEngine.RENPY,
        GameEngine.RPGM_RGSS,
        GameEngine.RPGM_MV_MZ,
        GameEngine.HTML,
    )

    /** Kirikiroid2 runs the KiriKiri/KirikiriZ (TVP) engine natively (KAG3/TJS2/XP3). */
    private val kirikiroidCapableEngines = setOf(
        GameEngine.KIRIKIRI,
    )

    fun analyze(relativePaths: List<String>): RunnerAdvice {
        val paths = relativePaths
            .asSequence()
            .map { it.replace('\\', '/').trim().trimStart('/').lowercase() }
            .filter { it.isNotBlank() }
            .toList()
        val names = paths.map { it.substringAfterLast('/') }
        fun anyName(pred: (String) -> Boolean) = names.any(pred)
        fun anyPath(pred: (String) -> Boolean) = paths.any(pred)
        fun ext(n: String) = n.substringAfterLast('.', "")

        val engine = when {
            // Ren'Py: renpy/ runtime dir, *.rpa/*.rpyc archives, game/ + lib/.
            anyPath { it.contains("renpy/") } ||
                anyName { ext(it) == "rpa" || ext(it) == "rpyc" || ext(it) == "rpy" } ->
                GameEngine.RENPY
            // RPG Maker MV/MZ: NW.js layout (www/, package.json, nw.dll).
            anyPath { it.contains("www/") } || anyName { it == "package.json" || it == "nw.dll" } ->
                GameEngine.RPGM_MV_MZ
            // RPG Maker XP/VX/VXAce: RGSS packed archives or the RGSS runtime DLL.
            anyName { ext(it) in setOf("rgssad", "rgss2a", "rgss3a") } ||
                anyName { it.startsWith("rgss") && ext(it) == "dll" } ->
                GameEngine.RPGM_RGSS
            // Wolf RPG Editor.
            anyName { it == "data.wolf" || it == "guruguru.dll" } ||
                anyName { it == "config.exe" } && anyName { it == "game.exe" } ->
                GameEngine.WOLF_RPG
            // KiriKiri / KAG.
            anyName { ext(it) == "xp3" } -> GameEngine.KIRIKIRI
            // Unity.
            anyName { it == "unityplayer.dll" } || anyPath { it.contains("_data/") } ->
                GameEngine.UNITY
            // Unreal Engine.
            anyPath { it.contains("engine/binaries") } ||
                anyName { it.endsWith("-win64-shipping.exe") || ext(it) == "pak" } ->
                GameEngine.UNREAL
            // Bare HTML game.
            anyName { ext(it) in setOf("html", "htm") } && anyName { ext(it) == "exe" }.not() ->
                GameEngine.HTML
            else -> GameEngine.NATIVE_OR_UNKNOWN
        }

        return adviceFor(engine)
    }

    fun adviceFor(engine: GameEngine): RunnerAdvice {
        val joiCapable = engine in joiPlayCapableEngines
        return when (engine) {
            GameEngine.RENPY -> RunnerAdvice(
                engine, Runner.JoiPlay, true, Confidence.High,
                listOf("Ren'Py runs natively in JoiPlay with better performance than Wine."),
            )
            GameEngine.RPGM_RGSS -> RunnerAdvice(
                engine, Runner.JoiPlay, true, Confidence.High,
                listOf(
                    "RPG Maker XP/VX/VX Ace runs natively in JoiPlay.",
                    "Under Winlator, RGSS games can hit a Wine DirectSound/mmdevapi audio deadlock.",
                ),
            )
            GameEngine.RPGM_MV_MZ -> RunnerAdvice(
                engine, Runner.JoiPlay, true, Confidence.Medium,
                listOf("RPG Maker MV/MZ runs in JoiPlay; Winlator also works but is heavier."),
            )
            GameEngine.HTML -> RunnerAdvice(
                engine, Runner.JoiPlay, true, Confidence.Medium,
                listOf("HTML games run in JoiPlay's browser engine."),
            )
            GameEngine.WOLF_RPG -> RunnerAdvice(
                engine, Runner.Winlator, false, Confidence.Medium,
                listOf("Wolf RPG is a native Windows engine; use Winlator."),
            )
            GameEngine.KIRIKIRI -> RunnerAdvice(
                engine, Runner.Kirikiroid, false, Confidence.Medium,
                listOf(
                    "KiriKiri/KAG (.xp3) games run natively in the Kirikiroid2 emulator.",
                    "Encrypted XP3 or plugin-heavy (win32 .tpm/.dll) titles may need Winlator instead.",
                ),
            )
            GameEngine.UNITY -> RunnerAdvice(
                engine, Runner.Winlator, false, Confidence.High,
                listOf("Unity Windows builds require Winlator (Wine); JoiPlay can't run them."),
            )
            GameEngine.UNREAL -> RunnerAdvice(
                engine, Runner.Winlator, false, Confidence.High,
                listOf("Unreal Engine Windows builds require Winlator (Wine)."),
            )
            GameEngine.NATIVE_OR_UNKNOWN -> RunnerAdvice(
                engine, Runner.Winlator, false, Confidence.Low,
                listOf("Couldn't detect a JoiPlay-compatible engine; a native Windows game needs Winlator."),
            )
        }.copy(
            joiPlayCapable = joiCapable,
            kirikiroidCapable = engine in kirikiroidCapableEngines,
        )
    }
}
