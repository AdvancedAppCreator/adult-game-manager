package com.example.f95updater

import java.io.File

/**
 * Deterministic identity evidence for Ren'Py patches and for the installed managed games they could
 * target. Nothing here guesses from titles or file names: a match is only produced when the patch
 * and exactly one installed game agree on a concrete identifier that Ren'Py itself writes.
 */

enum class PatchEngine { RenPy, Unknown }

data class RenPyOptions(
    val configName: String? = null,
    val buildName: String? = null,
    val version: String? = null,
    val saveDirectory: String? = null,
) {
    val isEmpty: Boolean
        get() = configName == null && buildName == null && version == null && saveDirectory == null

    companion object {
        val EMPTY = RenPyOptions()
    }
}

object RenPyOptionsParser {

    private fun assignment(field: String): Regex = Regex(
        """(?m)^[ \t]*(?:define[ \t]+)?(?:default[ \t]+)?""" +
            Regex.escape(field) +
            """[ \t]*=[ \t]*(?:_\([ \t]*)?(?:u|b|r)?(["'])(.*?)\1""",
    )

    private val CONFIG_NAME = assignment("config.name")
    private val BUILD_NAME = assignment("build.name")
    private val CONFIG_VERSION = assignment("config.version")
    private val SAVE_DIRECTORY = assignment("config.save_directory")

    fun parse(text: String): RenPyOptions = RenPyOptions(
        configName = single(CONFIG_NAME, text),
        buildName = single(BUILD_NAME, text),
        version = single(CONFIG_VERSION, text),
        saveDirectory = single(SAVE_DIRECTORY, text),
    )

    /** A field is evidence only when the file assigns it exactly one value. */
    private fun single(pattern: Regex, text: String): String? {
        val values = pattern.findAll(text)
            .map { it.groupValues[2].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(2)
            .toList()
        return values.singleOrNull()
    }
}

/** Normalises a version string for the exact-equality gate. Never treats 0.2 and 0.20 as equal. */
fun normalizePatchVersion(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val stripped = trimmed
        .removePrefix("v")
        .removePrefix("V")
        .trim()
        .removePrefix("ersion")
        .trim()
        .trimStart('.', '-', '_', ' ')
    return stripped.lowercase().replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotEmpty() }
}

private fun normalizeIdentifier(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private fun normalizeDisplayName(raw: String?): String? =
    raw?.lowercase()?.filter(Char::isLetterOrDigit)?.takeIf { it.isNotEmpty() }

/** What a staged patch proves about itself. */
data class PatchEvidence(
    val archiveName: String,
    val engine: PatchEngine,
    val destination: PatchDestination,
    val options: RenPyOptions,
    val optionsSource: String?,
    val fileNameVersionHint: String?,
    val sourceSignatures: RenPySourceSignatures = RenPySourceSignatures.EMPTY,
) {
    val declaredVersion: String? get() = options.version

    /** True when options.rpy gave AGM a concrete Ren'Py identifier to match on. */
    val hasDeclaredIdentity: Boolean
        get() = !options.saveDirectory.isNullOrBlank() || !options.buildName.isNullOrBlank()
}

/** What an installed managed game proves about itself on disk. */
data class GameIdentityEvidence(
    val managedGameId: String,
    val label: String,
    val renPyRoot: String?,
    val gameFolder: String?,
    val engine: PatchEngine,
    val options: RenPyOptions,
    val optionsSource: String?,
    val launcherBuildName: String?,
    val layoutSignatures: List<String>,
    val catalogVersionName: String,
    val sourceSignatures: RenPySourceSignatures = RenPySourceSignatures.EMPTY,
) {
    val provenBuildName: String? get() = options.buildName ?: launcherBuildName
    val provenVersion: String? get() = options.version
}

object RenPyGameProbe {

    private const val MAX_OPTIONS_BYTES = 2L * 1024L * 1024L

    /**
     * Resolves the single Ren'Py root under [storageRoot] and reads its concrete identity evidence.
     * Ambiguous trees (more than one Ren'Py root) return no root, which downstream code treats as
     * "identity not proven".
     *
     * [includeSourceSignatures] additionally reads the game's own bounded .rpy sources. It is only
     * requested when a patch carries no options.rpy identity, because scanning source across the
     * whole library is far more expensive than reading one file per game.
     */
    fun probe(game: ManagedGame, includeSourceSignatures: Boolean = false): GameIdentityEvidence {
        val storageRoot = File(game.storagePath)
        val root = resolveRenPyRoot(storageRoot)
        val gameFolder = root?.let { File(it, "game") }
        val optionsFile = gameFolder?.let { File(it, "options.rpy") }?.takeIf { isReadableText(it) }
        val signatures = if (includeSourceSignatures && gameFolder != null) {
            RenPySourceScanner.scanDirectory(gameFolder)
        } else {
            RenPySourceSignatures.EMPTY
        }
        val declaredOptions = optionsFile?.let { RenPyOptionsParser.parse(readText(it)) }
        val options = declaredOptions
            ?: if (includeSourceSignatures && gameFolder != null) {
                optionsFromSources(gameFolder)
            } else {
                RenPyOptions.EMPTY
            }
        return GameIdentityEvidence(
            managedGameId = game.id,
            label = game.label,
            renPyRoot = root?.absolutePath,
            gameFolder = gameFolder?.absolutePath,
            engine = if (root != null) PatchEngine.RenPy else PatchEngine.Unknown,
            options = options,
            optionsSource = optionsFile?.absolutePath,
            launcherBuildName = root?.let(::launcherBuildName),
            layoutSignatures = root?.let(::layoutSignatures).orEmpty(),
            catalogVersionName = game.versionName,
            sourceSignatures = signatures,
        )
    }

    /**
     * Some builds move config.* out of options.rpy. The same single-value rule applies: a field is
     * evidence only when the bounded source set assigns it exactly one value.
     */
    private fun optionsFromSources(gameFolder: File): RenPyOptions {
        val files = RenPySourceScanner.collectSourceFiles(gameFolder)
            .filter { it.length() in 1..MAX_OPTIONS_BYTES }
            .take(16)
        if (files.isEmpty()) return RenPyOptions.EMPTY
        val text = files.joinToString("\n") { runCatching { it.readText(Charsets.UTF_8) }.getOrElse { "" } }
        return RenPyOptionsParser.parse(text)
    }

    fun resolveRenPyRoot(storageRoot: File): File? {
        if (!storageRoot.isDirectory) return null
        if (isRenPyRoot(storageRoot)) return storageRoot
        val nested = storageRoot.listFiles()
            ?.filter { it.isDirectory && isRenPyRoot(it) }
            .orEmpty()
        return nested.singleOrNull()
    }

    fun isRenPyRoot(directory: File): Boolean {
        val gameFolder = File(directory, "game")
        if (!gameFolder.isDirectory) return false
        if (File(directory, "renpy").isDirectory) return true
        if (File(gameFolder, "script.rpy").isFile) return true
        if (File(gameFolder, "script.rpyc").isFile) return true
        return gameFolder.listFiles()?.any { it.isFile && it.extension.lowercase() == "rpa" } == true
    }

    /**
     * Ren'Py names its launcher scripts after `build.name`, so a `<name>.py` paired with a
     * `<name>.sh` or `<name>.exe` is concrete evidence even when options.rpy was stripped.
     */
    private fun launcherBuildName(root: File): String? {
        val children = root.listFiles()?.filter { it.isFile }.orEmpty()
        val pythonLaunchers = children.filter { it.extension.equals("py", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .filter { it.isNotBlank() }
            .distinct()
        val confirmed = pythonLaunchers.filter { base ->
            children.any { candidate ->
                val name = candidate.name
                name.equals("$base.sh", ignoreCase = true) ||
                    name.equals("$base.exe", ignoreCase = true) ||
                    name.equals("$base-32.exe", ignoreCase = true) ||
                    name.equals("$base.app", ignoreCase = true)
            }
        }
        return confirmed.singleOrNull()
    }

    private fun layoutSignatures(root: File): List<String> {
        val gameFolder = File(root, "game")
        val signatures = mutableListOf<String>()
        if (File(root, "renpy").isDirectory) signatures += "renpy/"
        if (File(root, "lib").isDirectory) signatures += "lib/"
        if (File(gameFolder, "script.rpy").isFile) signatures += "game/script.rpy"
        if (File(gameFolder, "script.rpyc").isFile) signatures += "game/script.rpyc"
        if (File(gameFolder, "options.rpy").isFile) signatures += "game/options.rpy"
        gameFolder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "rpa" }
            ?.sortedBy { it.name }
            ?.take(4)
            ?.forEach { signatures += "game/${it.name}" }
        return signatures
    }

    private fun isReadableText(file: File): Boolean =
        file.isFile && file.canRead() && file.length() in 1..MAX_OPTIONS_BYTES

    private fun readText(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "" }
}

/** Reads a staged patch tree (already extracted into an isolated folder) for its own evidence. */
object PatchEvidenceReader {

    private const val MAX_OPTIONS_BYTES = 2L * 1024L * 1024L

    fun read(
        archiveName: String,
        stagingRoot: File,
        scan: PatchArchiveScan.Accepted,
    ): PatchEvidence {
        val optionsRelative = when (scan.destination) {
            PatchDestination.GameFolder -> "options.rpy"
            PatchDestination.InstallRoot -> "game/options.rpy"
        }.takeIf { relative -> scan.relativePaths.any { it.equals(relative, ignoreCase = false) } }
        val optionsFile = optionsRelative?.let { File(stagingRoot, it) }
        val options = optionsFile
            ?.takeIf { it.isFile && it.length() in 1..MAX_OPTIONS_BYTES }
            ?.let { RenPyOptionsParser.parse(runCatching { it.readText(Charsets.UTF_8) }.getOrElse { "" }) }
            ?: RenPyOptions.EMPTY
        val renPyContent = scan.relativePaths.any {
            it.substringAfterLast('.', "").lowercase() in setOf("rpy", "rpyc", "rpa", "rpyb", "rpym", "rpymc")
        }
        val signatures = RenPySourceScanner.scanFiles(
            RenPySourceScanner.collectSourceFiles(stagingRoot),
        )
        return PatchEvidence(
            archiveName = archiveName,
            engine = if (renPyContent) PatchEngine.RenPy else PatchEngine.Unknown,
            destination = scan.destination,
            options = options,
            optionsSource = optionsRelative,
            fileNameVersionHint = JoiPlayVersionDetector.extractVersionFromArchiveName(archiveName),
            sourceSignatures = signatures,
        )
    }
}

/**
 * The three possible answers to "is this patch safe for the target AGM proved it belongs to?".
 *
 * Identity is a separate question and is never overridable: an outcome that carries a compatibility
 * value has already proven a single managed game is the target.
 */
enum class PatchCompatibility {
    /** The patch's own version evidence matches the installed build. */
    Proven,

    /** Identity is proven but nothing in the patch proves which build it targets. */
    Unproven,

    /** The patch states a version and the installed build provably is a different one. */
    ExplicitMismatch,
}

sealed interface PatchMatchOutcome {
    /** Identity *and* compatibility proven. The only outcome that installs without an override. */
    data class Matched(
        val target: GameIdentityEvidence,
        val proofs: List<String>,
    ) : PatchMatchOutcome

    /**
     * Exactly one managed game is provably the target, but AGM cannot prove the patch fits the
     * installed build. Nothing is installed from this outcome on its own: it only permits the
     * explicit, acknowledged override decision described by [reason].
     */
    data class Overridable(
        val target: GameIdentityEvidence,
        val proofs: List<String>,
        val compatibility: PatchCompatibility,
        val reason: String,
        val installedVersion: String?,
        val patchVersionMarker: String?,
    ) : PatchMatchOutcome

    /**
     * No target, more than one target, contradictory evidence, or a non-Ren'Py archive. There is
     * nothing for the user to override, so this is always a dead end.
     */
    data class Refused(val reason: String) : PatchMatchOutcome
}

object PatchTargetMatcher {

    /** A source-signature identity needs a large, high-coverage, uniquely-best corroborating set. */
    private const val MIN_PATCH_CHARACTERS = 8
    private const val MIN_PATCH_SIGNATURES = 12
    private const val MIN_CHARACTER_COVERAGE = 0.90f
    private const val MIN_MATCHED_SIGNATURES = 12
    private const val RUNNER_UP_MAX_COVERAGE = 0.50f
    private const val RUNNER_UP_MIN_GAP = 5

    private data class Assessment(
        val candidate: GameIdentityEvidence,
        val proofs: List<String>,
        val contradictions: List<String>,
    )

    fun match(
        patch: PatchEvidence,
        candidates: List<GameIdentityEvidence>,
    ): PatchMatchOutcome {
        if (patch.engine != PatchEngine.RenPy) {
            return PatchMatchOutcome.Refused(
                "AGM only installs Ren'Py patches today, and this archive carries no Ren'Py content.",
            )
        }
        if (!patch.hasDeclaredIdentity) return matchBySourceSignatures(patch, candidates)
        if (candidates.isEmpty()) {
            return PatchMatchOutcome.Refused("There are no managed games in the AGM library to patch.")
        }

        val assessments = candidates.map { assess(patch, it) }
        val qualified = assessments.filter { it.proofs.isNotEmpty() && it.contradictions.isEmpty() }
        if (qualified.isEmpty()) {
            return PatchMatchOutcome.Refused(
                "No managed game proved it is this patch's target. AGM compared the patch's " +
                    "Ren'Py identity against every managed game's own options.rpy and launch files " +
                    "and found no compatible match.",
            )
        }
        if (qualified.size > 1) {
            val names = qualified.joinToString(", ") { it.candidate.label }
            return PatchMatchOutcome.Refused(
                "The patch's identity matches more than one managed game ($names), so AGM cannot " +
                    "pick a target safely.",
            )
        }

        val winner = qualified.single()
        // Compatibility is judged by the same unified rule whichever way identity was proven: how
        // AGM identified the target says nothing about whether the patch fits the installed build.
        return outcomeFor(patch, winner.candidate, winner.proofs, sourceVersionVerdict(patch, winner.candidate))
    }

    /**
     * Identity for patches that ship no options.rpy: the patch's own .rpy source must declare a
     * large set of Character objects and labels that exactly one installed game also declares, with
     * near-total coverage and a clear gap to every other game. Anything smaller, weaker or
     * ambiguous is refused — a shared handful of generic declarations is never identity.
     */
    fun matchBySourceSignatures(
        patch: PatchEvidence,
        candidates: List<GameIdentityEvidence>,
    ): PatchMatchOutcome {
        val signatures = patch.sourceSignatures
        if (signatures.characters.size < MIN_PATCH_CHARACTERS ||
            signatures.total < MIN_PATCH_SIGNATURES
        ) {
            return PatchMatchOutcome.Refused(
                "The patch carries no concrete Ren'Py identity (no readable options.rpy with " +
                    "build.name or config.save_directory), so AGM cannot prove which installed game " +
                    "it belongs to. Its readable source declares only " +
                    "${signatures.characters.size} Character definition(s) and ${signatures.total} " +
                    "signature(s) in total, far too few to identify a game. The archive name is only " +
                    "a hint and is never treated as proof.",
            )
        }
        if (candidates.isEmpty()) {
            return PatchMatchOutcome.Refused("There are no managed games in the AGM library to patch.")
        }

        val scored = candidates
            .map { it to RenPySourceScanner.overlap(signatures, it.sourceSignatures) }
            .sortedByDescending { it.second.matches }
        val (best, bestOverlap) = scored.first()
        val runnerUp = scored.getOrNull(1)

        if (bestOverlap.characterCoverage < MIN_CHARACTER_COVERAGE ||
            bestOverlap.matches < MIN_MATCHED_SIGNATURES
        ) {
            return PatchMatchOutcome.Refused(
                "No managed game declares this patch's Ren'Py source identity. The best candidate " +
                    "(${best.label}) shares only ${bestOverlap.characterMatches} of " +
                    "${bestOverlap.characterTotal} Character definitions and " +
                    "${bestOverlap.matches} of ${bestOverlap.total} source signatures, which is not " +
                    "proof. AGM never patches a game it cannot identify.",
            )
        }
        if (runnerUp != null) {
            val gap = bestOverlap.matches - runnerUp.second.matches
            if (runnerUp.second.characterCoverage > RUNNER_UP_MAX_COVERAGE && gap < RUNNER_UP_MIN_GAP) {
                return PatchMatchOutcome.Refused(
                    "The patch's Ren'Py source identity matches more than one managed game " +
                        "(${best.label} and ${runnerUp.first.label} share it), so AGM cannot pick a " +
                        "target safely.",
                )
            }
        }

        val contradiction = signatureContradiction(patch, best)
        if (contradiction != null) return PatchMatchOutcome.Refused(contradiction)

        val proofs = buildList {
            add(
                "${bestOverlap.characterMatches} of ${bestOverlap.characterTotal} Character " +
                    "definitions in the patch source are declared verbatim by ${best.label}",
            )
            if (bestOverlap.labelTotal > 0) {
                add("${bestOverlap.labelMatches} of ${bestOverlap.labelTotal} script labels also match")
            }
            if (bestOverlap.declarationTotal > 0) {
                add(
                    "${bestOverlap.declarationMatches} of ${bestOverlap.declarationTotal} exact " +
                        "source declarations also match",
                )
            }
            add(
                "read from ${signatures.filesScanned} bounded .rpy file(s) in the patch and " +
                    "${best.sourceSignatures.filesScanned} in the installed game",
            )
        }

        sourceVersionVerdict(patch, best)?.let { return outcomeFor(patch, best, proofs, it) }
        return PatchMatchOutcome.Matched(best, proofs)
    }

    /**
     * Turns a version [verdict] into the outcome for an already uniquely-identified [target]. A null
     * verdict means the versions were compared and agreed, or that neither side made a version
     * claim AGM knows how to read.
     */
    private fun outcomeFor(
        patch: PatchEvidence,
        target: GameIdentityEvidence,
        proofs: List<String>,
        verdict: VersionVerdict?,
    ): PatchMatchOutcome {
        if (verdict == null) return PatchMatchOutcome.Matched(target, proofs)
        return PatchMatchOutcome.Overridable(
            target = target,
            proofs = proofs,
            compatibility = verdict.compatibility,
            reason = verdict.reason,
            installedVersion = target.provenVersion,
            patchVersionMarker = PatchSourceVersionEvidence.highestMarker(patch.sourceSignatures.labels),
        )
    }

    /** An options.rpy identity the patch itself states must never disagree with the target. */
    private fun signatureContradiction(patch: PatchEvidence, target: GameIdentityEvidence): String? {
        val patchName = normalizeDisplayName(patch.options.configName) ?: return null
        val gameName = normalizeDisplayName(target.options.configName) ?: return null
        if (patchName == gameName) return null
        return "The patch's source matches ${target.label}, but the patch declares config.name " +
            "\u201C${patch.options.configName}\u201D while ${target.label} declares " +
            "\u201C${target.options.configName}\u201D. AGM refuses contradictory evidence."
    }

    /** A version comparison that did not come out proven, and the exact wording of why. */
    private data class VersionVerdict(
        val compatibility: PatchCompatibility,
        val reason: String,
    )

    private fun declaredVersionVerdict(
        patch: PatchEvidence,
        target: GameIdentityEvidence,
    ): VersionVerdict? {
        val declared = normalizePatchVersion(patch.declaredVersion) ?: return null
        val installed = normalizePatchVersion(target.provenVersion)
            ?: return VersionVerdict(
                PatchCompatibility.Unproven,
                "The patch targets version ${patch.declaredVersion}, but AGM cannot prove which " +
                    "version of ${target.label} is installed (its options.rpy does not " +
                    "declare config.version), so the two versions cannot be compared.",
            )
        if (declared != installed) {
            return VersionVerdict(
                PatchCompatibility.ExplicitMismatch,
                "The patch targets version ${patch.declaredVersion}, but " +
                    "${target.label} is version ${target.provenVersion}.",
            )
        }
        return null
    }

    /**
     * The single compatibility rule, applied to every uniquely-identified target regardless of how
     * its identity was proven (declared options.rpy identity or source signatures).
     *
     * A readable `config.version` declared by the patch is authoritative: when the patch states one,
     * it alone decides the verdict and structured source markers are never consulted, not even to
     * contradict it. Markers only *supplement* — they are read when the patch declares no readable
     * `config.version` at all.
     *
     * A marker-based comparison is only *proven* when the marker maps to a version under AGM's one
     * defined rule (at least two numeric groups) and that version is exactly the installed one.
     * Anything else leaves compatibility unproven or explicitly mismatched — never silently fine,
     * and never a plain refusal, because the target itself is already proven.
     */
    private fun sourceVersionVerdict(
        patch: PatchEvidence,
        target: GameIdentityEvidence,
    ): VersionVerdict? {
        declaredVersionVerdict(patch, target)?.let { return it }
        // A declared config.version AGM could read has already decided this; markers are a fallback
        // for patches that declare none, never a second opinion on one that does.
        if (normalizePatchVersion(patch.declaredVersion) != null) return null
        val marker = PatchSourceVersionEvidence.highestMarker(patch.sourceSignatures.labels)
            ?: return null
        val installedRaw = target.provenVersion
        val installed = normalizePatchVersion(installedRaw)
        val normalized = PatchSourceVersionEvidence.normalizeMarker(marker)
        if (normalized == null) {
            return VersionVerdict(
                PatchCompatibility.Unproven,
                "AGM identified this patch as ${target.label}, but it cannot prove the patch is " +
                    "compatible with the installed build. The patch declares no config.version; its " +
                    "highest structured version marker is the label \u201C$marker\u201D, and a " +
                    "single-number marker has no defined mapping to a Ren'Py config.version " +
                    "(\u201C$marker\u201D could mean ${ambiguousReadings(marker)}). " +
                    "${target.label} is version ${installedRaw ?: "unknown"}.",
            )
        }
        if (installed == null) {
            return VersionVerdict(
                PatchCompatibility.Unproven,
                "AGM identified this patch as ${target.label} and read the structured version marker " +
                    "\u201C$marker\u201D ($normalized) from its source, but ${target.label} does not " +
                    "declare a config.version, so the versions cannot be compared.",
            )
        }
        if (normalized != installed) {
            return VersionVerdict(
                PatchCompatibility.ExplicitMismatch,
                "AGM identified this patch as ${target.label}, but its structured version marker " +
                    "\u201C$marker\u201D resolves to $normalized while ${target.label} is version " +
                    "$installedRaw.",
            )
        }
        return null
    }

    private fun ambiguousReadings(marker: String): String {
        val digits = marker.removePrefix("v").filter { it.isDigit() }
        if (digits.isEmpty()) return "several different versions"
        val plain = digits.trimStart('0').ifEmpty { "0" }
        val leadingZero = "0.$digits"
        val split = if (digits.length >= 2) {
            ", ${digits.first()}.${digits.drop(1)}"
        } else {
            ""
        }
        return "$plain, $leadingZero$split"
    }

    private fun assess(patch: PatchEvidence, candidate: GameIdentityEvidence): Assessment {
        val proofs = mutableListOf<String>()
        val contradictions = mutableListOf<String>()

        val patchSave = normalizeIdentifier(patch.options.saveDirectory)
        val gameSave = normalizeIdentifier(candidate.options.saveDirectory)
        if (patchSave != null && gameSave != null) {
            if (patchSave == gameSave) {
                proofs += "config.save_directory = ${patch.options.saveDirectory}"
            } else {
                contradictions += "config.save_directory"
            }
        }

        val patchBuild = normalizeIdentifier(patch.options.buildName)
        val gameBuild = normalizeIdentifier(candidate.provenBuildName)
        if (patchBuild != null && gameBuild != null) {
            if (patchBuild == gameBuild) {
                proofs += "build.name = ${patch.options.buildName}"
            } else {
                contradictions += "build.name"
            }
        }

        val patchName = normalizeDisplayName(patch.options.configName)
        val gameName = normalizeDisplayName(candidate.options.configName)
        if (patchName != null && gameName != null && patchName != gameName) {
            contradictions += "config.name"
        }

        return Assessment(candidate, proofs, contradictions)
    }
}
