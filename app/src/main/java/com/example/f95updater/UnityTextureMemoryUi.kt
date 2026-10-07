package com.example.f95updater

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

private sealed interface UnityTextureAnalysisUiState {
    data object Idle : UnityTextureAnalysisUiState
    data object Analyzing : UnityTextureAnalysisUiState
    data class Complete(val result: UnityTextureMemoryAnalyzer.Result) : UnityTextureAnalysisUiState
}

private data class UnityTextureLimitLiveConfig(
    val schema: WinlatorConfigSchema,
    val game: WinlatorApi.ManagedGame,
)

/**
 * This panel is intentionally analysis-only until Winlator's *live* config schema proves that it
 * owns `unityTextureLimit`. Its only write path is a [WinlatorConfigSubmission] callback.
 */
@Composable
internal fun UnityTextureMemoryPane(
    app: InstalledApp,
    onSubmit: (List<WinlatorConfigSubmission>) -> Unit,
) {
    val context = LocalContext.current
    val root = remember(app.storagePath, app.winlatorExecutablePath) { unityGameRoot(app) }
    var analysis by remember(app.packageName, root) { mutableStateOf<UnityTextureAnalysisUiState>(UnityTextureAnalysisUiState.Idle) }
    var liveConfig by remember(app.winlatorGameId) { mutableStateOf<UnityTextureLimitLiveConfig?>(null) }
    var configStatus by remember(app.winlatorGameId) { mutableStateOf<String?>(null) }

    LaunchedEffect(app.winlatorGameId) {
        val gameId = app.winlatorGameId
        if (gameId == null) {
            configStatus = "No Winlator managed game is linked, so controls are unavailable."
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val capabilities = WinlatorClient.capabilities(context.applicationContext).getOrThrow()
                require(
                    capabilities.apiVersion >= 4 &&
                        capabilities.managedGameConfiguration &&
                        capabilities.gameConfigSchemaVersion >= 1 &&
                        capabilities.configSchemaPath == "config-schema" &&
                        capabilities.configConflictDetection &&
                        capabilities.configUpdateExtra == "config_update_json",
                ) { "This Winlator version does not advertise conflict-safe managed configuration." }
                val schema = when (val read = WinlatorClient.getConfigSchema(context.applicationContext)) {
                    is WinlatorClient.Read.Ok -> WinlatorConfigSchema.parse(read.payload)
                    is WinlatorClient.Read.Err -> error("${read.code}: ${read.message}")
                    is WinlatorClient.Read.Unavailable -> error(read.reason)
                }
                require(UnityTextureLimitConfig.isAdvertised(schema)) {
                    "Winlator does not advertise unityTextureLimit with off, 1, 2, and 3."
                }
                val game = when (val read = WinlatorClient.getGame(context.applicationContext, gameId)) {
                    is WinlatorClient.Read.Ok -> WinlatorApi.ManagedGame.parse(JSONObject(read.payload))
                    is WinlatorClient.Read.Err -> error("${read.code}: ${read.message}")
                    is WinlatorClient.Read.Unavailable -> error(read.reason)
                }
                UnityTextureLimitLiveConfig(schema, game)
            }
        }
        liveConfig = loaded.getOrNull()
        configStatus = loaded.exceptionOrNull()?.message
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Unity texture memory", style = MaterialTheme.typography.titleMedium)
        Text(
            "Reads Unity metadata only. AGM never patches, backs up, restores, or otherwise changes game files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (root == null) {
            Text(
                "No accessible game folder is available for analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Button(
                onClick = {
                    analysis = UnityTextureAnalysisUiState.Analyzing
                },
                enabled = analysis !is UnityTextureAnalysisUiState.Analyzing,
            ) {
                Text(if (analysis is UnityTextureAnalysisUiState.Analyzing) "Analyzing…" else "Analyze compatibility")
            }
            LaunchedEffect(analysis) {
                if (analysis !is UnityTextureAnalysisUiState.Analyzing) return@LaunchedEffect
                val result = withContext(Dispatchers.IO) {
                    UnityTextureMemoryAnalyzer.analyze(
                        root,
                        physicalRamBytes(context),
                        app.winlatorExecutablePath?.takeIf { it.isNotBlank() }?.let(::File),
                    )
                }
                analysis = UnityTextureAnalysisUiState.Complete(result)
            }
        }

        when (val state = analysis) {
            UnityTextureAnalysisUiState.Idle -> Unit
            UnityTextureAnalysisUiState.Analyzing -> Row {
                CircularProgressIndicator(modifier = Modifier.width(20.dp))
                Text(" Reading supported Unity metadata…", modifier = Modifier.padding(start = 8.dp))
            }
            is UnityTextureAnalysisUiState.Complete -> UnityTextureAnalysisReport(
                result = state.result,
                liveConfig = liveConfig,
                configStatus = configStatus,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun UnityTextureAnalysisReport(
    result: UnityTextureMemoryAnalyzer.Result,
    liveConfig: UnityTextureLimitLiveConfig?,
    configStatus: String?,
    onSubmit: (List<WinlatorConfigSubmission>) -> Unit,
) {
    when (result) {
        is UnityTextureMemoryAnalyzer.Result.Missing -> Text(
            result.reason,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        is UnityTextureMemoryAnalyzer.Result.Unsupported -> Text(
            "Unsupported: ${result.reason}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        is UnityTextureMemoryAnalyzer.Result.Success -> {
            val report = result.report
            Text(
                "${report.textures.size} Texture2D objects • Unity ${report.unityVersions.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Streamed payload metadata: ${formatBytes(report.streamedPayloadBytes)}. " +
                    "This is not resident-memory usage.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Current projection (${if (report.currentProjectionUsesConservativeFullResolution) "full-resolution fallback" else "quality ${report.currentProjectionLimit}"}): " +
                    formatBytes(report.risk.projectedResidentBytes),
                style = MaterialTheme.typography.bodySmall,
            )
            report.currentQuality?.let { quality ->
                Text(
                    "Quality profile ${quality.currentProfileIndex}: texture quality " +
                        (quality.textureQuality?.toString() ?: "not stored") +
                        ", streaming mips ${quality.streamingMipmapsActive ?: "not stored"}" +
                        quality.streamingMipmapsBudgetMiB?.let { ", budget ${"%.1f".format(Locale.US, it)} MiB" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Risk: ${report.risk.level.name.lowercase().replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.bodySmall,
                color = when (report.risk.level) {
                    UnityTextureMemoryAnalyzer.RiskLevel.Critical -> MaterialTheme.colorScheme.error
                    UnityTextureMemoryAnalyzer.RiskLevel.High -> MaterialTheme.colorScheme.tertiary
                    UnityTextureMemoryAnalyzer.RiskLevel.None -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            report.risk.flags.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall)
            }
            Text("Projected resident memory", style = MaterialTheme.typography.labelLarge)
            report.projections.forEach { projection ->
                Text(
                    "• ${if (projection.limit == 0) "off" else projection.limit}: " +
                        formatBytes(projection.estimatedResidentBytes) +
                        if (projection.unknownFormatTextureCount > 0) {
                            " (plus ${projection.unknownFormatTextureCount} unknown format(s))"
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val recommendation = report.recommendation
            Text(
                "Recommendation: ${recommendation.value ?: "unavailable"} — ${recommendation.reason}",
                style = MaterialTheme.typography.bodySmall,
            )
            report.warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (liveConfig != null) {
                Text("Winlator managed texture limit", style = MaterialTheme.typography.labelLarge)
                val currentValue = UnityTextureLimitConfig.currentValue(liveConfig.game)
                Text(
                    "Current: ${if (currentValue == "off") "Off" else currentValue}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    UnityTextureLimitConfig.values.forEach { value ->
                        TextButton(
                            onClick = {
                                UnityTextureLimitConfig.buildSubmission(liveConfig.game, liveConfig.schema, value)
                                    ?.let { onSubmit(listOf(it)) }
                            },
                            enabled = value != currentValue,
                        ) {
                            Text(if (value == "off") "Off" else "Apply $value")
                        }
                    }
                }
            } else {
                Text(
                    configStatus ?: "Winlator managed texture-limit controls are unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun unityGameRoot(app: InstalledApp): File? {
    app.storagePath?.takeIf { it.isNotBlank() }?.let { return File(it) }
    app.winlatorExecutablePath?.takeIf { it.isNotBlank() }?.let { executable ->
        return File(executable).parentFile
    }
    return null
}

private fun physicalRamBytes(context: Context): Long? =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem.takeIf { it > 0 }
        }

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(Locale.US, bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(Locale.US, bytes.toDouble() / (1024L * 1024L))
    else -> "$bytes B"
}
