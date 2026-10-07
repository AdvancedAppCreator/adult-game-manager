package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RenPySaveLocationRow(
    location: RenPySaveLocation,
    associationActionsEnabled: Boolean,
    onAssociate: (RenPySaveLocation) -> Unit,
    onClearAssociation: (RenPySaveLocation) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(location.saveDirPath, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        val association = if (location.associatedPackageName != null) {
            "Associated: ${location.associatedLabel ?: location.associatedPackageName} (${location.confidence}%)"
        } else {
            "Unassociated"
        }
        Text(association, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        location.reason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Owner: ${location.ownerId} • saves: ${location.saveCount} • latest: ${fmtDateTime(location.latestModified)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        location.renpyVersion?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (location.sampleSaveNames.isNotEmpty()) {
            Text(
                "Samples: ${location.sampleSaveNames.joinToString(" • ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = associationActionsEnabled,
                onClick = { onAssociate(location) },
            ) {
                Text(if (location.associatedPackageName == null) "Associate" else "Reassign")
            }
            if (location.reason == "Manually associated") {
                TextButton(
                    enabled = associationActionsEnabled,
                    onClick = { onClearAssociation(location) },
                ) {
                    Text("Clear manual")
                }
            }
        }
    }
}

@Composable
internal fun RpgmSaveLocationRow(
    location: RpgmSaveLocation,
    associationActionsEnabled: Boolean,
    onAssociate: (RpgmSaveLocation) -> Unit,
    onClearAssociation: (RpgmSaveLocation) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(location.saveDirPath, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (location.associatedPackageName != null) {
                "Associated: ${location.associatedLabel ?: location.associatedPackageName} (${location.confidence}%)"
            } else "Unassociated",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        location.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "Owner: ${location.ownerId} • saves: ${location.saveCount} • latest: ${fmtDateTime(location.latestModified)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = associationActionsEnabled,
                onClick = { onAssociate(location) },
            ) {
                Text(if (location.associatedPackageName == null) "Associate" else "Reassign")
            }
            if (location.reason == "Manually associated") {
                TextButton(
                    enabled = associationActionsEnabled,
                    onClick = { onClearAssociation(location) },
                ) {
                    Text("Clear manual")
                }
            }
        }
    }
}

@Composable
internal fun RenPySaveLocationHeader(path: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RpgmSaveSlotRow(slot: RpgmSaveSlot, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(slot.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Modified: ${fmtDateTime(slot.modifiedAt)} • Size: ${fmtSize(slot.sizeBytes)} • ${slot.codec}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            slot.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
