package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogResultRow(result: CatalogSearchResult, onClick: () -> Unit) {
    val game = result.game
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(game.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            result.translatedTitle?.let { translatedTitle ->
                Text(
                    translatedTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val sub = buildString {
                append(game.source.sourceDisplayName)
                game.version?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" \u2022 ")
                    append("v")
                    append(it)
                }
                game.creator?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(it)
                }
                if (isNotEmpty()) append(" \u2022 ")
                append("id ").append(game.sourceId ?: game.thread_id.toString())
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CatalogResultList(
    results: List<CatalogSearchResult>,
    onPick: (CatalogGame) -> Unit,
    modifier: Modifier = Modifier,
    fillAvailable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillAvailable) Modifier.fillMaxHeight() else Modifier.heightIn(max = 160.dp))
            .dialogVerticalScroll(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (result in results) {
            CatalogResultRow(result = result, onClick = { onPick(result.game) })
        }
    }
}

@Composable
internal fun ExternalMirrorResultList(
    results: List<ExternalMirrorResult>,
    onPick: (ExternalMirrorResult) -> Unit,
    modifier: Modifier = Modifier,
    fillAvailable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillAvailable) Modifier.fillMaxHeight() else Modifier.heightIn(max = 160.dp))
            .dialogVerticalScroll(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (result in results) {
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(result) },
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(result.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = buildString {
                        append(result.sourceHost.ifBlank { "external source" })
                        result.threadId?.let { append(" • linked catalog id ").append(it) }
                        result.version?.takeIf { it.isNotBlank() }?.let {
                            append(" • ")
                            append(it)
                        }
                    }
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
