package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * Detail popup for a cross-source game group. The header stays on the representative title/cover;
 * when the group has more than one source a tab row lets the user switch between each source's own
 * version, rating, synopsis, and "Open page" link.
 */
@Composable
internal fun CatalogGroupDetailDialog(
    group: CatalogGroupDisplay,
    catalog: CatalogRepository,
    labels: CatalogLabelsV2?,
    installed: Boolean,
    ignored: Boolean,
    translateTitles: Boolean,
    translationTarget: String,
    onDismiss: () -> Unit,
    onOpenSource: (SourceCatalogEntry) -> Unit,
    onToggleIgnored: () -> Unit,
    onGoToInstalled: (() -> Unit)? = null,
) {
    val members = group.members.ifEmpty { listOf(group.representative) }
    val configuration = LocalConfiguration.current
    val wideDialog = configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp

    var selectedIndex by remember(group.agmGroupId) {
        mutableStateOf(members.indexOf(group.representative).coerceAtLeast(0))
    }
    val safeIndex = selectedIndex.coerceIn(0, members.size - 1)
    val game = members[safeIndex]

    val headerTitle = rememberCatalogTranslatedTitle(
        entry = group.representative,
        enabled = translateTitles,
        targetLanguage = translationTarget,
    )
    val tagNames = displayTags(game, labels)
    var detailsResult by remember(game.source, game.sourceId) {
        mutableStateOf<CatalogDetailsLoadResult?>(null)
    }
    LaunchedEffect(game.source, game.sourceId) {
        detailsResult = catalog.entryDetails(game)
    }
    var groupSynopsisResult by remember(group.agmGroupId) {
        mutableStateOf<Pair<Boolean, CatalogGroupSynopsis?>>(false to null)
    }
    LaunchedEffect(group.agmGroupId) {
        groupSynopsisResult = true to catalog.groupSynopsis(group.agmGroupId)
    }
    val groupSynopsis = groupSynopsisResult.second
    val sourceSynopsis = (detailsResult as? CatalogDetailsLoadResult.Available)
        ?.details
        ?.synopsis
        ?.takeIf { it.isNotBlank() }
    val synopsis = sourceSynopsis ?: groupSynopsis?.synopsis?.takeIf { it.isNotBlank() }
    val usingGroupSynopsis = sourceSynopsis == null && synopsis != null
    var forceSynopsisTranslation by remember(game.source, game.sourceId) { mutableStateOf(0) }
    val synopsisTranslation = rememberCatalogTranslatedSynopsis(
        entry = game,
        synopsis = synopsis.orEmpty(),
        enabled = translateTitles && synopsis != null,
        targetLanguage = translationTarget,
        forceRequest = forceSynopsisTranslation,
    )
    val translatedSynopsis = synopsisTranslation.text
    var showOriginalSynopsis by remember(game.source, game.sourceId) { mutableStateOf(false) }
    val detailCoverUrl = game.coverUrl?.takeIf { it.isNotBlank() }
        ?: game.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: group.coverUrl?.takeIf { it.isNotBlank() }
        ?: dlsiteImageUrls(game.canonicalUrl)?.coverUrl
    var fullSizeOpen by remember { mutableStateOf(false) }
    val bodyScrollState = rememberScrollState()
    AlertDialog(
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .then(if (wideDialog) Modifier.fillMaxHeight(0.9f) else Modifier),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        headerTitle ?: group.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    headerTitle?.let {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (installed) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("Installed") },
                        leadingIcon = {
                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                DiagnosticsScreenshotIconButton(namePrefix = "catalog-dialog")
            }
        },
        text = {
            val sourceTabs: @Composable () -> Unit = {
                if (members.size > 1) {
                    ScrollableTabRow(
                        selectedTabIndex = safeIndex,
                        edgePadding = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        members.forEachIndexed { index, member ->
                            Tab(
                                selected = index == safeIndex,
                                onClick = { selectedIndex = index },
                                text = {
                                    Text(
                                        member.source.sourceDisplayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            val coverPane: @Composable (Modifier) -> Unit = { modifier ->
                if (!detailCoverUrl.isNullOrBlank()) {
                    coil.compose.SubcomposeAsyncImage(
                        model = detailCoverUrl,
                        contentDescription = "Cover",
                        modifier = modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { fullSizeOpen = true },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        loading = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            }
                        },
                        error = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.BrokenImage,
                                    contentDescription = "Cover unavailable",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }

            val detailsPane: @Composable () -> Unit = {
                var viewportHeightPx by remember { mutableStateOf(0) }
                val canScrollForward = bodyScrollState.maxValue > 0 && bodyScrollState.value < bodyScrollState.maxValue
                Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { viewportHeightPx = it.height },
                ) {
                Column(
                    modifier = Modifier
                        .dialogVerticalScroll(bodyScrollState)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("Source: ${game.source.sourceDisplayName}", style = MaterialTheme.typography.bodySmall)
                    game.developer?.let { Text("Developer: $it", style = MaterialTheme.typography.bodySmall) }
                    game.versionText?.let { Text("Version: $it", style = MaterialTheme.typography.bodySmall) }
                    game.rating?.let { Text("Rating: %.2f / 5".format(it), style = MaterialTheme.typography.bodySmall) }
                    game.popularity?.takeIf { it > 0.0 }?.let { Text("Popularity: ${formatViews(it.toLong())}", style = MaterialTheme.typography.bodySmall) }
                    game.publishedAt?.let { Text("Published: ${formatIsoDate(it)}", style = MaterialTheme.typography.bodySmall) }
                    game.modifiedAt?.let { Text("Updated: ${formatIsoDate(it)}", style = MaterialTheme.typography.bodySmall) }
                    Text("Source ID: #${game.sourceId}", style = MaterialTheme.typography.bodySmall)
                    if (game.platforms.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Platforms: ${game.platforms.joinToString(" • ")}",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                    if (tagNames.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Tags: ${tagNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Synopsis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    when {
                        detailsResult == null && !groupSynopsisResult.first -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Loading…", style = MaterialTheme.typography.bodySmall)
                        }
                        synopsis != null -> {
                            Text(
                                translatedSynopsis ?: synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (translatedSynopsis != null) {
                                TextButton(
                                    onClick = { showOriginalSynopsis = !showOriginalSynopsis },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text(if (showOriginalSynopsis) "Hide original" else "Show original")
                                }
                                if (showOriginalSynopsis) {
                                    Text(
                                        synopsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (usingGroupSynopsis) {
                                Text(
                                    "Showing the English synopsis indexed from ${groupSynopsis?.source?.sourceDisplayName}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if ((detailsResult as? CatalogDetailsLoadResult.Available)?.stale == true) {
                                Text(
                                    "Showing cached synopsis.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        detailsResult is CatalogDetailsLoadResult.Missing -> Text(
                            "No synopsis is available for this entry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        detailsResult is CatalogDetailsLoadResult.Unavailable -> Text(
                            "Synopsis unavailable: ${(detailsResult as CatalogDetailsLoadResult.Unavailable).message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (bodyScrollState.maxValue > 0 && viewportHeightPx > 0) {
                    val density = LocalDensity.current
                    val contentHeightPx = viewportHeightPx + bodyScrollState.maxValue
                    val thumbFraction = (viewportHeightPx.toFloat() / contentHeightPx.toFloat())
                        .coerceIn(0.08f, 1f)
                    val trackHeightPx = viewportHeightPx.toFloat()
                    val thumbHeightPx = trackHeightPx * thumbFraction
                    val maxThumbOffsetPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                    val scrollFraction = bodyScrollState.value.toFloat() / bodyScrollState.maxValue.toFloat()
                    val thumbOffsetPx = maxThumbOffsetPx * scrollFraction
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                            .width(4.dp)
                            .height(with(density) { thumbHeightPx.toDp() })
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                MaterialTheme.shapes.small,
                            ),
                    )
                }
                }
                if (canScrollForward) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 10.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        tonalElevation = 3.dp,
                    ) {
                        Text(
                            "More below ↓",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                }
            }
            if (wideDialog && !detailCoverUrl.isNullOrBlank()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    sourceTabs()
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        coverPane(Modifier.width(280.dp).fillMaxHeight())
                        Box(Modifier.weight(1f).fillMaxHeight()) { detailsPane() }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxHeight()) {
                    sourceTabs()
                    coverPane(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp))
                    if (!detailCoverUrl.isNullOrBlank()) Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f)) { detailsPane() }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                if (synopsis != null) {
                    synopsisTranslation.error?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (synopsis != null) {
                        if (synopsisTranslation.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp).padding(end = 4.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        TextButton(
                            enabled = !synopsisTranslation.loading,
                            onClick = { forceSynopsisTranslation++ },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                if (translatedSynopsis == null) {
                                    "Translate synopsis"
                                } else {
                                    "Retranslate synopsis"
                                }
                            )
                        }
                    }
                    onGoToInstalled?.let { navigate ->
                        TextButton(onClick = navigate) {
                            Icon(Icons.Default.Apps, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Go to installed game")
                        }
                    }
                    TextButton(onClick = onToggleIgnored) {
                        Icon(
                            if (ignored) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (ignored) "Unignore" else "Ignore")
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                    TextButton(onClick = { onOpenSource(game); onDismiss() }) { Text("Open page") }
                }
            }
        },
    )
    if (fullSizeOpen && !detailCoverUrl.isNullOrBlank()) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullSizeOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { fullSizeOpen = false },
                contentAlignment = Alignment.Center,
            ) {
                coil.compose.SubcomposeAsyncImage(
                    model = detailCoverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    },
                )
            }
        }
    }
}
