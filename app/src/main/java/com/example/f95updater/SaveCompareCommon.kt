package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

internal data class SaveCompareValue(
    val key: String,
    val type: String,
    val value: String,
)

internal data class SaveCompareDiff(
    val key: String,
    val type: String,
    val leftValue: String?,
    val rightValue: String?,
)

internal fun buildSaveDiffs(left: List<SaveCompareValue>, right: List<SaveCompareValue>): List<SaveCompareDiff> {
    val leftMap = left.associateBy { it.key }
    val rightMap = right.associateBy { it.key }
    return (leftMap.keys + rightMap.keys)
        .distinct()
        .sorted()
        .mapNotNull { key ->
            val l = leftMap[key]
            val r = rightMap[key]
            if (l?.value == r?.value) null
            else SaveCompareDiff(
                key = key,
                type = l?.type ?: r?.type ?: "",
                leftValue = l?.value,
                rightValue = r?.value,
            )
        }
}

@Composable
internal fun SaveCompareDialogContent(
    title: String,
    slots: List<Pair<String, String>>,
    leftPath: String?,
    rightPath: String?,
    warning: String?,
    diffs: List<SaveCompareDiff>?,
    onLeft: (String) -> Unit,
    onRight: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wideEditor = isWideEditorLayout(configuration)
    val dialogWidthFraction = if (wideEditor) 0.86f else 0.96f
    val dialogHeightFraction = if (wideEditor) 0.88f else 0.92f
    val contentMaxHeight = if (wideEditor) {
        (configuration.screenHeightDp * 0.62f).dp
    } else {
        600.dp
    }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    val diffScroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(dialogWidthFraction)
            .fillMaxHeight(dialogHeightFraction),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                warning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val loaded = diffs
                if (loaded == null) {
                    Text("Left save", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        slots.forEach { (path, name) ->
                            FilterChip(
                                selected = leftPath == path,
                                onClick = { onLeft(path) },
                                label = { Text(name, fontSize = 12.sp) },
                            )
                        }
                    }
                    Text("Right save", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        slots.forEach { (path, name) ->
                            FilterChip(
                                selected = rightPath == path,
                                onClick = { onRight(path) },
                                label = { Text(name, fontSize = 12.sp) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Comparing…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val controls: @Composable ColumnScope.() -> Unit = {
                        Text("Left save", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            slots.forEach { (path, name) ->
                                FilterChip(
                                    selected = leftPath == path,
                                    onClick = { onLeft(path) },
                                    label = { Text(name, fontSize = 12.sp) },
                                )
                            }
                        }
                        Text("Right save", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            slots.forEach { (path, name) ->
                                FilterChip(
                                    selected = rightPath == path,
                                    onClick = { onRight(path) },
                                    label = { Text(name, fontSize = 12.sp) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Filter differences") },
                            placeholder = { Text("key, value, or type") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotBlank()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(null, "int", "float", "bool", "string").forEach { type ->
                                FilterChip(
                                    selected = typeFilter == type,
                                    onClick = { typeFilter = if (typeFilter == type) null else type },
                                    label = { Text(type ?: "All", fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                    val q = query.trim()
                    val filteredDiffs = loaded.filter { diff ->
                        val typeOk = typeFilter == null || diff.type == typeFilter
                        val queryOk = q.isBlank() ||
                            diff.key.contains(q, ignoreCase = true) ||
                            diff.type.contains(q, ignoreCase = true) ||
                            diff.leftValue?.contains(q, ignoreCase = true) == true ||
                            diff.rightValue?.contains(q, ignoreCase = true) == true
                        typeOk && queryOk
                    }
                    val diffList: @Composable (Modifier) -> Unit = { modifier ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${filteredDiffs.size} of ${loaded.size} differing value${if (loaded.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (filteredDiffs.isEmpty()) {
                                ReportEmptyRow()
                            } else {
                                ScrollableColumnWithScrollbar(
                                    scrollState = diffScroll,
                                    modifier = modifier,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    filteredDiffs.forEach { diff -> SaveCompareDiffRow(diff) }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                content = controls,
                            )
                            diffList(
                                Modifier
                                    .weight(1f)
                                    .heightIn(max = contentMaxHeight)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = controls)
                        diffList(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 330.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun SaveCompareDiffRow(diff: SaveCompareDiff) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(diff.key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text("Type: ${diff.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "A: ${diff.leftValue ?: "(missing)"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "B: ${diff.rightValue ?: "(missing)"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
