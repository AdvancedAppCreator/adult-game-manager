package com.example.f95updater

/**
 * Deterministic identity evidence for "does this archive replace an installed managed game?".
 *
 * An upgrade overwrites a game the user already plays, so the bar is a *proven, unique, strongly
 * equivalent* title — never a fuzzy score and never a single shared word. Names are ranked by how
 * strongly the archive proves them: engine metadata the game itself wrote beats structural names
 * (a wrapper folder, a Ren'Py launcher pair) which beat the archive's own file name.
 */

enum class ManagedNameEvidence {
    /** Read out of the archive's own engine metadata (options.rpy, package.json, game.ini). */
    Metadata,

    /** Derived from the archive's structure: its single wrapper folder or a launcher file pair. */
    Structure,

    /** The archive's file name. A hint the uploader chose, so it is the weakest evidence. */
    ArchiveName,
}

data class ManagedNameHint(
    val value: String,
    val evidence: ManagedNameEvidence,
    val source: String,
)

/** One installed game an archive proved it is an update of, with the evidence that proved it. */
data class ManagedUpgradeCandidate(
    val app: InstalledApp,
    val evidence: ManagedNameEvidence,
    val archiveTitle: String,
    val installedTitle: String,
    val source: String,
    val exact: Boolean,
) {
    val reason: String
        get() = buildString {
            append(
                when (evidence) {
                    ManagedNameEvidence.Metadata -> "The archive's game metadata ($source)"
                    ManagedNameEvidence.Structure -> "The archive's $source"
                    ManagedNameEvidence.ArchiveName -> "The archive file name"
                },
            )
            append(" reads \u201C").append(archiveTitle).append("\u201D, ")
            append(if (exact) "the same title as " else "which contains the whole title of ")
            append("the installed game \u201C").append(installedTitle).append("\u201D.")
        }
}

object ManagedUpgradeIdentity {

    /**
     * Words that describe a build, a release channel or a repack rather than a game. They carry no
     * identity, so they are stripped before anything is compared: a shared "release" or "patch"
     * must never make two unrelated archives look like the same game.
     */
    private val genericTokens = setOf(
        "release", "releases", "prerelease", "public", "patreon", "subscribestar", "itch",
        "patch", "patched", "unofficial", "official", "mod", "modded", "fix", "fixed", "hotfix",
        "crack", "cracked", "repack", "compressed", "compression", "port", "ported",
        "update", "updated", "upgrade", "build", "final", "beta", "alpha", "demo", "preview",
        "full", "complete", "edition", "version", "ver", "rev", "revision",
        "game", "games", "data", "setup", "install", "installer", "extract", "extracted",
        "uncensored", "censored", "decensored", "eng", "english", "translation", "translated",
        "walkthrough", "multi", "part", "parts", "archive", "folder", "new", "latest", "copy",
        "pc", "win", "win32", "win64", "windows", "mac", "osx", "linux", "android", "apk",
        "x64", "x86", "amd64", "arm64", "joiplay", "renpy", "rpgm", "rpgmaker", "unity",
    )

    /** A one-word title is only identity when it is long enough to not be a common noun. */
    private const val MIN_SINGLE_WORD_KEY = 5

    /** Containment is only identity when the contained title is long and multi-word. */
    private const val MIN_CONTAINED_WORDS = 2
    private const val MIN_CONTAINED_KEY = 8

    data class Identity(val words: List<String>, val key: String) {
        val isBlank: Boolean get() = key.isEmpty()

        companion object {
            val BLANK = Identity(emptyList(), "")
        }
    }

    enum class Relation { Exact, Contains }

    /**
     * Normalised identity of a raw name. Version/platform tails and articles are removed by
     * [CatalogRepository.titleWords]; possessive "'s" is glued back onto its noun so `Grandma's
     * House` and `GrandmasHouse` produce the same words, and generic release words are dropped.
     */
    fun identity(raw: String): Identity {
        val merged = mergePossessives(CatalogRepository.titleWords(raw))
        val words = merged.filter { it !in genericTokens }
        if (words.isEmpty()) return Identity.BLANK
        return Identity(words, words.joinToString(""))
    }

    private fun mergePossessives(words: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (word in words) {
            if (word == "s" && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + "s"
            } else {
                out += word
            }
        }
        return out
    }

    /**
     * How two identities relate, or null when the pair is not strong enough to authorise an
     * overwrite. Equal identities are exact; otherwise one title must appear whole, as a run of
     * consecutive words, inside the other.
     */
    fun relate(a: Identity, b: Identity): Relation? {
        if (a.isBlank || b.isBlank) return null
        if (a.key == b.key) {
            val distinctive = a.words.size >= 2 || a.key.length >= MIN_SINGLE_WORD_KEY
            return if (distinctive) Relation.Exact else null
        }
        val contained = if (a.words.size <= b.words.size) a else b
        val container = if (contained === a) b else a
        if (contained.words.size < MIN_CONTAINED_WORDS) return null
        if (contained.key.length < MIN_CONTAINED_KEY) return null
        return if (containsRun(container.words, contained.words)) Relation.Contains else null
    }

    private fun containsRun(container: List<String>, run: List<String>): Boolean {
        if (run.isEmpty() || run.size > container.size) return false
        for (start in 0..(container.size - run.size)) {
            if (container.subList(start, start + run.size) == run) return true
        }
        return false
    }

    /** Every name an installed managed game answers to. */
    fun installedNames(app: InstalledApp): List<String> = listOfNotNull(
        app.label,
        app.launcherLabel,
        app.storageFolderName,
        app.storagePath?.substringAfterLast('/')?.substringAfterLast('\\'),
    ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * The one installed managed game this archive proves it updates, or an empty list.
     *
     * Only the strongest evidence the archive carries is consulted: once the archive states its own
     * name in engine metadata, a folder or file name that says something else is a contradiction,
     * not a second opinion. Anything ambiguous (two games matching equally well) resolves to "no
     * candidate" so the caller installs as new instead of guessing.
     */
    fun candidates(
        hints: List<ManagedNameHint>,
        apps: List<InstalledApp>,
    ): List<ManagedUpgradeCandidate> {
        val eligible = apps.filter {
            it.source == AppSource.Managed &&
                !it.managedGameId.isNullOrBlank() &&
                !it.storagePath.isNullOrBlank()
        }
        if (eligible.isEmpty() || hints.isEmpty()) return emptyList()

        val bestEvidence = hints.minBy { it.evidence.ordinal }.evidence
        val considered = hints.filter { it.evidence == bestEvidence }
        val installedIdentities = eligible.associateWith { app ->
            installedNames(app).map { it to identity(it) }
        }

        val matches = mutableListOf<ManagedUpgradeCandidate>()
        for (hint in considered) {
            val hintIdentity = identity(hint.value)
            if (hintIdentity.isBlank) continue
            for ((app, names) in installedIdentities) {
                for ((installedName, installedIdentity) in names) {
                    val relation = relate(hintIdentity, installedIdentity) ?: continue
                    matches += ManagedUpgradeCandidate(
                        app = app,
                        evidence = hint.evidence,
                        archiveTitle = hint.value,
                        installedTitle = installedName,
                        source = hint.source,
                        exact = relation == Relation.Exact,
                    )
                    break
                }
            }
        }
        if (matches.isEmpty()) return emptyList()

        val exactMatches = matches.filter { it.exact }
        val best = exactMatches.ifEmpty { matches }
        val games = best.mapNotNull { it.app.managedGameId }.distinct()
        if (games.size != 1) {
            AppLog.i(
                "ManagedUpgrade",
                "Ambiguous upgrade identity: ${games.size} managed games match equally, installing as new",
            )
            return emptyList()
        }
        return listOf(best.first())
    }
}
