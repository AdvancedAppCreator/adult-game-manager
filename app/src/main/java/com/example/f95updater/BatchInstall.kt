package com.example.f95updater

/**
 * Pure model + logic for the bulk (multi-file) installer. Every picked file is classified up front
 * by the preflight; anything that could overwrite an installed managed game is decided by the user
 * *before* the first byte is written. The driver in MainActivity then processes the queue one item
 * at a time against those stored decisions, reusing the existing single-item install machinery.
 * Kept free of Android/Compose types so it can be unit-tested.
 */
enum class BatchItemKind {
    Apk,
    Winlator,
    JoiPlay,
    Archive,
    Unsupported,
}

enum class BatchItemStatus {
    Queued,
    Active,
    Done,
    Failed,
    Skipped;

    val isTerminal: Boolean
        get() = this == Done || this == Failed || this == Skipped
}

/** What the user decided to do with an archive that matched an installed managed game. */
sealed interface BatchInstallDecision {
    /** Replace the named installed managed game. */
    data class Upgrade(val managedGameId: String, val targetLabel: String) : BatchInstallDecision

    /** Install alongside whatever is already there. */
    data object InstallAsNew : BatchInstallDecision

    /** Do not process this file at all. */
    data object Skip : BatchInstallDecision
}

/** An installed managed game an archive proved it updates, flattened for the pure model. */
data class BatchUpgradeTarget(
    val managedGameId: String,
    val label: String,
    val storagePath: String,
    val reason: String,
)

data class BatchItem(
    val path: String,
    val name: String,
    val kind: BatchItemKind,
    val status: BatchItemStatus = BatchItemStatus.Queued,
    val decision: BatchInstallDecision? = null,
    val note: String? = null,
)

/**
 * Immutable snapshot of a bulk-install run. Callers replace their held instance with the value
 * returned by [startNext] / [settleActive] to advance the queue.
 */
data class BatchInstallSession(
    val items: List<BatchItem>,
    val activeIndex: Int = -1,
    val automatic: Boolean = false,
    val pausedError: String? = null,
    val runId: Long = 0L,
) {
    val total: Int get() = items.size
    val doneCount: Int get() = items.count { it.status == BatchItemStatus.Done }
    val failedCount: Int get() = items.count { it.status == BatchItemStatus.Failed }
    val skippedCount: Int get() = items.count { it.status == BatchItemStatus.Skipped }
    val settledCount: Int get() = items.count { it.status.isTerminal }
    val isComplete: Boolean get() = items.isNotEmpty() && items.all { it.status.isTerminal }

    /** The currently-processing item, or null if none is active. */
    val current: BatchItem?
        get() = items.getOrNull(activeIndex)?.takeIf { it.status == BatchItemStatus.Active }

    /**
     * True when [runId] and [path] still identify *this* run's active item.
     *
     * The driver settles items through this check. Two bulk runs can hold the very same file paths,
     * so the path alone cannot prove the answer belongs to the run that asked the question: without
     * the run id, a driver coroutine left over from a cancelled run would settle — and advance — the
     * replacement run's first item.
     */
    fun ownsActiveItem(runId: Long, path: String): Boolean =
        this.runId == runId && current?.path == path

    /**
     * True only while this automatic run is actually driving an item of its own.
     *
     * Unattended behaviour — auto-confirming an APK, auto-picking a destination or a runner,
     * turning an error dialog into a run pause — must be keyed off this and never off [automatic]
     * alone. A finished run's banner stays on screen until the user dismisses it, and a session can
     * sit with no active item; in both cases `automatic` is still true while the run owns nothing.
     * Reading `automatic` on its own therefore let a leftover banner silently swallow the
     * confirmations of a completely unrelated, user-initiated standalone install.
     */
    val isUnattendedActive: Boolean
        get() = automatic && !isComplete && current != null

    /** True when this session is still the unattended run identified by [runId]. */
    fun isUnattendedRun(runId: Long): Boolean = isUnattendedActive && this.runId == runId

    /** Human-readable "processed N of M" position (1-based) of the active item. */
    val activePosition: Int get() = if (activeIndex in items.indices) activeIndex + 1 else settledCount

    private fun firstQueuedIndex(): Int = items.indexOfFirst { it.status == BatchItemStatus.Queued }

    /**
     * Promote the next queued item to [BatchItemStatus.Active]. If none remain, clears the active
     * index. Any previously-active item that was not settled is left untouched (the driver settles
     * before calling this).
     */
    fun startNext(): BatchInstallSession {
        if (pausedError != null) return this
        val idx = firstQueuedIndex()
        if (idx < 0) return copy(activeIndex = -1)
        return copy(
            items = items.mapIndexed { i, item ->
                if (i == idx) item.copy(status = BatchItemStatus.Active) else item
            },
            activeIndex = idx,
        )
    }

    /** Give the currently-active item a terminal [status]. */
    fun settleActive(status: BatchItemStatus): BatchInstallSession {
        if (activeIndex !in items.indices) return this
        require(status.isTerminal) { "settleActive requires a terminal status, got $status" }
        return copy(
            items = items.mapIndexed { i, item ->
                if (i == activeIndex) item.copy(status = status) else item
            },
        )
    }

    fun pause(error: String): BatchInstallSession =
        copy(pausedError = error.trim().ifBlank { "Installation failed." })

    fun continueAfterError(): BatchInstallSession =
        copy(pausedError = null).settleActive(BatchItemStatus.Failed).startNext()

    fun stopAfterError(): BatchInstallSession =
        copy(pausedError = null)
            .settleActive(BatchItemStatus.Failed)
            .cancelRemaining()
            .startNext()

    /**
     * Mark every not-yet-started (queued) item as skipped. Any item currently being processed is
     * left to finish naturally; the driver advances to completion once it settles.
     */
    fun cancelRemaining(): BatchInstallSession = copy(
        items = items.map { if (it.status == BatchItemStatus.Queued) it.copy(status = BatchItemStatus.Skipped) else it },
    )
}

/** One classified file, before anything is installed. */
data class BatchPreflightItem(
    val path: String,
    val name: String,
    val kind: BatchItemKind,
    val targets: List<BatchUpgradeTarget> = emptyList(),
    val note: String? = null,
    val installable: Boolean = true,
) {
    /** Only an installable file with a proven upgrade target needs the user to decide anything. */
    val needsDecision: Boolean get() = installable && targets.isNotEmpty()
}

/**
 * The scan phase of a bulk run: every file is classified, then every archive that proved it updates
 * an installed game is decided by the user. Installation starts only once [isResolved] is true, so
 * no destructive choice is ever made mid-queue.
 */
data class BatchInstallPreflight(
    val runId: Long = 0L,
    val items: List<BatchPreflightItem> = emptyList(),
    val decisions: Map<String, BatchInstallDecision> = emptyMap(),
    val total: Int = 0,
    val scanning: Boolean = true,
    val automatic: Boolean = false,
    val modeChosen: Boolean = false,
) {
    val scanned: Int get() = items.size
    val decisionItems: List<BatchPreflightItem> get() = items.filter { it.needsDecision }
    val pendingDecisions: List<BatchPreflightItem>
        get() = decisionItems.filterNot { decisions.containsKey(it.path) }

    /** The item whose decision the UI is currently asking for, or null when none are left. */
    val currentDecision: BatchPreflightItem? get() = pendingDecisions.firstOrNull()
    val decidedCount: Int get() = decisionItems.size - pendingDecisions.size
    val installableCount: Int get() = items.count { it.installable }
    val isResolved: Boolean get() = !scanning && modeChosen && pendingDecisions.isEmpty()

    fun withItem(item: BatchPreflightItem): BatchInstallPreflight = copy(items = items + item)

    fun finishScan(): BatchInstallPreflight = copy(scanning = false)

    fun chooseMode(automatic: Boolean): BatchInstallPreflight =
        copy(automatic = automatic, modeChosen = true)

    fun decide(path: String, decision: BatchInstallDecision): BatchInstallPreflight =
        copy(decisions = decisions + (path to decision))

    /** Skip everything still undecided. Used when the user backs out of the decision run. */
    fun skipRemaining(): BatchInstallPreflight =
        copy(decisions = decisions + pendingDecisions.associate { it.path to BatchInstallDecision.Skip })

    /**
     * Freeze the preflight into a runnable queue. Files that cannot be installed (missing,
     * unsupported) and files the user skipped are settled before execution instead of being
     * discovered — and guessed at — while the queue runs.
     */
    fun toSession(): BatchInstallSession {
        val built = items.map { item ->
            val decision = decisions[item.path]
            val status = when {
                !item.installable -> BatchItemStatus.Skipped
                decision is BatchInstallDecision.Skip -> BatchItemStatus.Skipped
                else -> BatchItemStatus.Queued
            }
            BatchItem(
                path = item.path,
                name = item.name,
                kind = item.kind,
                status = status,
                decision = decision,
                note = item.note,
            )
        }
        return BatchInstallSession(items = built, automatic = automatic, runId = runId)
    }
}

object BatchInstall {
    fun classify(fileName: String): BatchItemKind =
        when (InstallRouting.routePick(fileName)) {
            InstallRouting.PickRoute.InstallApk -> BatchItemKind.Apk
            InstallRouting.PickRoute.ChooseRunner -> BatchItemKind.Winlator
            InstallRouting.PickRoute.LaunchJoiPlayFile -> BatchItemKind.JoiPlay
            InstallRouting.PickRoute.HtmlOnly -> BatchItemKind.JoiPlay
            InstallRouting.PickRoute.InspectArchive -> BatchItemKind.Archive
            is InstallRouting.PickRoute.Unsupported -> BatchItemKind.Unsupported
        }

    /**
     * Classify a picked file for the preflight scan. Upgrade targets for archives are attached by
     * the caller, which is the only step that needs the installed library and the archive itself.
     */
    fun preflightItem(name: String, path: String, exists: Boolean): BatchPreflightItem {
        val kind = classify(name)
        if (!exists) {
            return BatchPreflightItem(
                path = path,
                name = name,
                kind = kind,
                note = "File no longer exists",
                installable = false,
            )
        }
        val unsupported = (InstallRouting.routePick(name) as? InstallRouting.PickRoute.Unsupported)?.message
        return BatchPreflightItem(
            path = path,
            name = name,
            kind = kind,
            note = unsupported,
            installable = unsupported == null,
        )
    }

    fun kindLabel(kind: BatchItemKind): String = when (kind) {
        BatchItemKind.Apk -> "Android"
        BatchItemKind.Winlator -> "Winlator"
        BatchItemKind.JoiPlay -> "JoiPlay"
        BatchItemKind.Archive -> "Archive"
        BatchItemKind.Unsupported -> "Unsupported"
    }
}
