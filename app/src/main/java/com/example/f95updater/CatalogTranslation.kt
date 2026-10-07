package com.example.f95updater

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberCatalogTranslatedTitle(
    entry: SourceCatalogEntry,
    enabled: Boolean,
    targetLanguage: String,
): String? = rememberCatalogTranslatedTitle(
    source = entry.source,
    sourceId = entry.sourceId,
    title = entry.title,
    enabled = enabled,
    targetLanguage = targetLanguage,
)

@Composable
internal fun rememberCatalogTranslatedTitle(
    source: String,
    sourceId: String?,
    title: String,
    enabled: Boolean,
    targetLanguage: String,
): String? {
    val context = LocalContext.current
    return produceState<String?>(
        initialValue = null,
        source,
        sourceId,
        title,
        enabled,
        targetLanguage,
    ) {
        value = null
        if (!enabled || sourceId.isNullOrBlank() || title.isBlank()) return@produceState
        value = CatalogTitleTranslator.translateIfNeeded(
            context = context.applicationContext,
            entryKey = "$source:$sourceId",
            text = title,
            targetLanguageTag = targetLanguage,
        ).onFailure {
            AppLog.w(
                "CatalogTranslate",
                "Could not translate $source:$sourceId: ${it.message}",
            )
        }.getOrNull()
    }.value
}

internal data class CatalogSynopsisTranslation(
    val text: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun rememberCatalogTranslatedSynopsis(
    entry: SourceCatalogEntry,
    synopsis: String,
    enabled: Boolean,
    targetLanguage: String,
    forceRequest: Int,
): CatalogSynopsisTranslation {
    val context = LocalContext.current
    return produceState(
        initialValue = CatalogSynopsisTranslation(),
        entry.source,
        entry.sourceId,
        synopsis,
        enabled,
        targetLanguage,
        forceRequest,
    ) {
        val forced = forceRequest > 0
        value = CatalogSynopsisTranslation()
        if ((!enabled && !forced) || synopsis.isBlank()) return@produceState
        value = CatalogSynopsisTranslation(loading = true)
        val result = CatalogTitleTranslator.translateIfNeeded(
            context = context.applicationContext,
            entryKey = "synopsis:${entry.source}:${entry.sourceId}",
            text = synopsis,
            targetLanguageTag = targetLanguage,
            force = forced,
            bypassCache = forced,
        ).onFailure {
            AppLog.w(
                "CatalogTranslate",
                "Could not translate synopsis ${entry.source}:${entry.sourceId}: ${it.message}",
            )
        }
        value = result.fold(
            onSuccess = { translated ->
                CatalogSynopsisTranslation(
                    text = translated,
                    error = if (forced && translated == null) {
                        "No non-target-language text was detected to translate."
                    } else null,
                )
            },
            onFailure = {
                CatalogSynopsisTranslation(
                    error = if (forced) {
                        "Could not translate synopsis: ${it.message ?: "unknown error"}"
                    } else null,
                )
            },
        )
    }.value
}

@Composable
internal fun CatalogTranslationDialog(
    enabled: Boolean,
    targetLanguage: String,
    onEnabledChange: (Boolean) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var languageMenuOpen by remember { mutableStateOf(false) }
    val languages = remember {
        CatalogTitleTranslator.supportedTargetLanguages()
            .distinct()
            .map { it to CatalogTitleTranslator.languageDisplayName(it) }
            .sortedBy { it.second.lowercase() }
    }
    val targetName = languages.firstOrNull { it.first == targetLanguage }?.second ?: targetLanguage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catalog translation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Translate catalog text", fontWeight = FontWeight.Medium)
                        Text(
                            "Titles and available synopses already detected as the output language remain unchanged.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Box {
                    OutlinedButton(
                        onClick = { languageMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Output language: $targetName")
                    }
                    DropdownMenu(
                        expanded = languageMenuOpen,
                        onDismissRequest = { languageMenuOpen = false },
                    ) {
                        languages.forEach { (tag, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                trailingIcon = {
                                    if (tag == targetLanguage) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    onTargetLanguageChange(tag)
                                    languageMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Language models download once when needed. Titles translate lazily and in best-effort background passes; synopses translate only when opened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
