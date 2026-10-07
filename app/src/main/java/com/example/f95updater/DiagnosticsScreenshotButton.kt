package com.example.f95updater

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreenshotIconButton(
    namePrefix: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val appConfig by AppConfigStore.observe(context.applicationContext).collectAsState()
    var capturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (!appConfig.diagnosticsEnabled) return

    IconButton(
        modifier = modifier,
        enabled = !capturing,
        onClick = {
            if (capturing) return@IconButton
            capturing = true
            scope.launch {
                runCatching {
                    val file = ScreenshotDiagnostics.capture(
                        context.applicationContext,
                        view,
                        "$namePrefix-${System.currentTimeMillis()}",
                    )
                    AppLog.i("Screenshots", "Manual screenshot captured: ${file.name}")
                    Toast.makeText(context, "Screenshot added to diagnostics logs", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    AppLog.e("Screenshots", "Manual screenshot capture failed", it)
                    Toast.makeText(context, "Screenshot failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
                capturing = false
            }
        },
    ) {
        if (capturing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.BugReport, contentDescription = "Add screenshot to diagnostics logs")
        }
    }
}
