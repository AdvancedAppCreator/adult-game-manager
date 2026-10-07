package com.example.f95updater

/**
 * The explicit compatibility override: the only thing about a patch install a user may decide for
 * themselves, and the pure rules that gate it.
 *
 * AGM's patch safety gates fall into two very different groups.
 *
 * **Never overridable.** Archive header validation (paths, links, reserved `.agm-` names, entry and
 * total size limits), staged-tree verification, a writable and contained destination, a *uniquely
 * proven* managed-game target, the "one unresolved transaction per game" guard, and the
 * backup/journal/rollback transaction. Those either protect files outside the patch's business or
 * establish which game is being written to at all; no user acknowledgement can make an unidentified
 * or unsafe archive safe, so nothing in this file can unlock them.
 *
 * **Overridable, explicitly.** Whether the *version* of an already-identified patch provably fits
 * the installed build. AGM often cannot prove this: fan patches routinely ship no `config.version`
 * and only a structured label such as `v002`, which has no defined mapping to a Ren'Py version. The
 * consequence of getting it wrong is a broken game that a rollback fully undoes — not data outside
 * the game and not a silent overwrite. So this one question is presented to the user as a decision
 * with the exact target, the exact evidence and the exact reason, and requires an explicit
 * acknowledgement before AGM will run the very same transactional install it runs for a proven
 * match.
 *
 * Everything here is pure so the rules can be unit tested without Compose or Android.
 */

/** Everything the preview dialog reports before the user authorises a patch install. */
data class PatchInstallPreview(
    val archiveName: String,
    val engineLabel: String,
    val patchIdentityLines: List<String>,
    val proofs: List<String>,
    val targetManagedGameId: String,
    val targetLabel: String,
    val targetStoragePath: String,
    val targetIdentityLines: List<String>,
    val destinationRoot: String,
    val additions: List<String>,
    val replacements: List<String>,
    val totalBytes: Long,
    val compatibility: PatchCompatibility = PatchCompatibility.Proven,
    /** Why compatibility is not proven. Always null for [PatchCompatibility.Proven]. */
    val compatibilityReason: String? = null,
    val installedVersion: String? = null,
    val patchVersionMarker: String? = null,
    val patchVersionHint: String? = null,
) {
    /** True when this preview is an override decision rather than a plain confirmation. */
    val requiresOverrideAcknowledgement: Boolean
        get() = compatibility != PatchCompatibility.Proven
}

/**
 * The pure gate between a preview and [PatchInstallFlow.confirmInstall].
 *
 * A preview that needs no override installs on a plain confirmation, exactly as before. A preview
 * that does need one installs only while the acknowledgement for *that* preview is currently
 * checked; the flow drops the acknowledgement whenever the preview it belonged to is replaced or
 * cleared, so a recreated dialog always starts unchecked and can never inherit consent.
 */
object PatchOverrideGate {

    fun title(preview: PatchInstallPreview): String = when (preview.compatibility) {
        PatchCompatibility.Proven -> "Install patch?"
        PatchCompatibility.Unproven -> "Compatibility not verified"
        PatchCompatibility.ExplicitMismatch -> "Incompatible version"
    }

    fun confirmLabel(preview: PatchInstallPreview): String =
        if (preview.requiresOverrideAcknowledgement) "Install anyway" else "Install patch"

    fun headline(preview: PatchInstallPreview): String = when (preview.compatibility) {
        PatchCompatibility.Proven -> "AGM proved this patch belongs to this game and fits the " +
            "installed version."
        PatchCompatibility.Unproven -> "AGM proved which game this patch belongs to, but it could " +
            "not prove the patch fits the installed version."
        PatchCompatibility.ExplicitMismatch -> "AGM proved which game this patch belongs to, and " +
            "the patch states it is for a different version than the one installed."
    }

    fun acknowledgementLabel(preview: PatchInstallPreview): String =
        "I understand this patch may break ${preview.targetLabel}, and that AGM is applying it only " +
            "to ${preview.targetLabel} (${preview.targetStoragePath})."

    /** The rollback promise shown next to every override decision. */
    fun rollbackStatement(preview: PatchInstallPreview): String =
        "AGM backs up every one of the ${preview.replacements.size} replaced file(s) before writing " +
            "and journals the whole install, so this patch can be undone completely with Roll back " +
            "installed patch. Your archive is never deleted."

    /** True while the confirm button may be pressed at all. */
    fun mayInstall(preview: PatchInstallPreview?, acknowledged: Boolean): Boolean =
        blockReason(preview, acknowledged) == null

    /** Why the install must not start, or null when it may. */
    fun blockReason(preview: PatchInstallPreview?, acknowledged: Boolean): String? {
        if (preview == null) return "There is no staged patch to install."
        if (!preview.requiresOverrideAcknowledgement) return null
        if (!acknowledged) {
            return "AGM could not prove this patch fits the installed version of " +
                "${preview.targetLabel}. Tick the acknowledgement to install it anyway."
        }
        return null
    }

    /**
     * The reason to persist with the transaction, or null when nothing was overridden. Only ever
     * non-null for an acknowledged override, so a record can never claim an override the user did
     * not make.
     */
    fun overrideReason(preview: PatchInstallPreview, acknowledged: Boolean): String? {
        if (!preview.requiresOverrideAcknowledgement || !acknowledged) return null
        return preview.compatibilityReason ?: headline(preview)
    }

    /**
     * The single log line AGM writes when a user confirms an override. It names the decision, the
     * exact target, the installed version and the compatibility reason, and never contains archive
     * or game source content.
     */
    fun logLine(preview: PatchInstallPreview): String =
        "User-confirmed compatibility override for '${preview.archiveName}': " +
            "compatibility=${preview.compatibility} " +
            "managedGameId=${preview.targetManagedGameId} label=${preview.targetLabel} " +
            "installedVersion=${preview.installedVersion ?: "unknown"} " +
            "patchVersionMarker=${preview.patchVersionMarker ?: "none"} " +
            "patchVersionHint=${preview.patchVersionHint ?: "none"} " +
            "reason=${preview.compatibilityReason ?: headline(preview)}"

    /** The note shown for an installed patch that was applied through an override. */
    fun installedNote(reason: String?): String =
        "Installed with an explicit compatibility override. " + (reason ?: "Compatibility was not proven.")
}
