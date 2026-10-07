package com.example.f95updater

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DetailRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
        }
    }
}

@Composable
internal fun DetailRowWithAction(
    key: String,
    value: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionDesc: String,
    onAction: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
        }
        IconButton(onClick = onAction, modifier = Modifier.size(28.dp)) {
            Icon(actionIcon, contentDescription = actionDesc, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Inbox,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
