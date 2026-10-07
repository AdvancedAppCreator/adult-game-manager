package com.example.f95updater

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A grouped Catalog row: one card per cross-source game group. Shows the representative title/cover,
 * the newest version and best rating across sources, a badge for EVERY source the game appears on,
 * and the engine hint. Tapping opens the group detail popup (a tab per source).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun CatalogGroupRowCard(
    group: CatalogGroupDisplay,
    labels: CatalogLabelsV2?,
    installed: Boolean,
    wishlisted: Boolean,
    ignored: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    highlighted: Boolean = false,
    translateTitles: Boolean,
    translationTarget: String,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleWishlist: () -> Unit,
    onToggleIgnored: () -> Unit,
    onOpen: () -> Unit,
) {
    val rep = group.representative
    val translatedTitle = rememberCatalogTranslatedTitle(
        entry = rep,
        enabled = translateTitles,
        targetLanguage = translationTarget,
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = onLongPress,
            ),
        colors = if (selected) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else if (highlighted) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            )
        } else {
            CardDefaults.elevatedCardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogThumbnail(rep)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (installed) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        translatedTitle ?: group.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    group.engine?.let {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                translatedTitle?.let {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.developer?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    group.versionText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val tagNames = displayTags(rep, labels).take(6)
                if (tagNames.isNotEmpty()) {
                    Text(
                        tagNames.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.sources.forEach { source ->
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(source.sourceDisplayName, fontSize = 10.sp) },
                            modifier = Modifier.height(24.dp),
                        )
                    }
                    group.rating?.let {
                        Text("%.2f ★".format(it), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    group.popularity?.takeIf { it > 0.0 }?.let {
                        Text(formatViews(it.toLong()), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
            IconButton(onClick = onToggleWishlist, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (wishlisted) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (wishlisted) "Remove from wishlist" else "Add to wishlist",
                    modifier = Modifier.size(20.dp),
                    tint = if (wishlisted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleIgnored, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (ignored) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    if (ignored) "Unignore game" else "Ignore game",
                    modifier = Modifier.size(19.dp),
                    tint = if (ignored) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.OpenInBrowser, "Open page", modifier = Modifier.size(18.dp))
            }
        }
    }
}
