package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which long-running archive operation the minimized card is currently tracking. */
enum class ProgressOperationKind { Extract, Upgrade, Patch }

/** A snapshot of the one archive operation that is currently running. */
data class MinimizedProgress(
    val kind: ProgressOperationKind,
    val archiveName: String,
    val phase: JoiPlayExtractFlow.Phase,
    val progress: ArchiveExtractor.Progress?,
    val batchPosition: String? = null,
    val cancelEnabled: Boolean = true,
) {
    /** Identity of the operation. A new archive (or a new flow) is a new operation. */
    val key: String get() = "$kind:$archiveName"

    val label: String
        get() = when (kind) {
            ProgressOperationKind.Extract -> "Extracting"
            ProgressOperationKind.Upgrade -> "Updating game"
            ProgressOperationKind.Patch -> "Installing patch"
        }
}

/**
 * Whether the running operation's progress dialog is hidden behind the compact card.
 *
 * This only moves UI: the extraction coroutine, its job ownership and its cancellation are
 * untouched, exactly like minimizing a browser tab. Nothing here survives process death and no
 * foreground service is involved.
 */
data class ProgressMinimizeState(
    val minimized: Boolean = false,
    val batchScoped: Boolean = false,
    val operationKey: String? = null,
    val batchId: Long? = null,
)

object ProgressMinimize {

    /**
     * Reconciles the held state with whatever is running now.
     *
     * A bulk run minimizes once for the whole remaining queue, so the state survives the handoff
     * from one item to the next. A standalone operation resets as soon as it finishes, is cancelled
     * or is replaced, so the next operation always opens its dialog normally.
     */
    fun onActiveOperation(
        state: ProgressMinimizeState,
        operationKey: String?,
        batchId: Long?,
    ): ProgressMinimizeState {
        if (state.batchId != batchId) return ProgressMinimizeState(batchId = batchId, operationKey = operationKey)
        if (state.batchScoped && batchId != null) return state.copy(operationKey = operationKey)
        if (state.operationKey != operationKey) {
            return ProgressMinimizeState(batchId = batchId, operationKey = operationKey)
        }
        return state
    }

    fun minimize(
        state: ProgressMinimizeState,
        operationKey: String?,
        batchId: Long?,
    ): ProgressMinimizeState = ProgressMinimizeState(
        minimized = operationKey != null,
        batchScoped = batchId != null,
        operationKey = operationKey,
        batchId = batchId,
    )

    fun restore(state: ProgressMinimizeState): ProgressMinimizeState =
        state.copy(minimized = false, batchScoped = false)
}

/**
 * Compact, always-visible replacement for a minimized progress dialog. Mirrors the minimized
 * browser card so the two behave the same way.
 */
@Composable
fun MinimizedProgressCard(
    status: MinimizedProgress,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelling = status.phase == JoiPlayExtractFlow.Phase.Cancelling
    val total = status.progress?.bytesTotal ?: 0L
    val written = status.progress?.bytesWritten ?: 0L
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (cancelling) "Cancelling \u2014 ${status.label}" else status.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        status.archiveName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (cancelling || total <= 0L) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { status.progress?.percent ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                buildString {
                    status.batchPosition?.let { append("Item ").append(it).append("  \u2022  ") }
                    append(fmtSize(written))
                    append(" of ")
                    append(if (total > 0) fmtSize(total) else "?")
                    if (total > 0) {
                        append("  \u2022  ")
                        append("%.0f%%".format((written.toDouble() / total) * 100))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (status.cancelEnabled) {
                    TextButton(onClick = onCancel, enabled = !cancelling) {
                        Text(if (cancelling) "Cancelling\u2026" else "Cancel")
                    }
                }
                TextButton(onClick = onRestore) { Text("Restore") }
            }
        }
    }
}
