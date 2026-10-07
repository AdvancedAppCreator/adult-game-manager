package com.example.f95updater

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollIndicators(
    progress: Float,
    visibleFraction: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    indicatorColor: Color,
    trackColor: Color,
) {
    val trackWidth = 4.dp.toPx()
    val margin = 3.dp.toPx()
    val trackHeight = size.height - margin * 2
    val thumbHeight = trackHeight * visibleFraction.coerceIn(0.08f, 1f)
    val thumbTop = margin + (trackHeight - thumbHeight) * progress.coerceIn(0f, 1f)
    val x = size.width - margin - trackWidth
    val arrowCenterX = x + trackWidth / 2f
    drawRoundRect(
        color = trackColor,
        topLeft = Offset(x, margin),
        size = Size(trackWidth, trackHeight),
        cornerRadius = CornerRadius(trackWidth),
    )
    drawRoundRect(
        color = indicatorColor.copy(alpha = 0.82f),
        topLeft = Offset(x, thumbTop),
        size = Size(trackWidth, thumbHeight),
        cornerRadius = CornerRadius(trackWidth),
    )
    val arrowSize = 7.dp.toPx()
    if (canScrollBackward) {
        drawPath(
            Path().apply {
                moveTo(arrowCenterX, 2.dp.toPx())
                lineTo(arrowCenterX - arrowSize, 2.dp.toPx() + arrowSize)
                lineTo(arrowCenterX + arrowSize, 2.dp.toPx() + arrowSize)
                close()
            },
            color = indicatorColor.copy(alpha = 0.82f),
        )
    }
    if (canScrollForward) {
        drawPath(
            Path().apply {
                moveTo(arrowCenterX, size.height - 2.dp.toPx())
                lineTo(arrowCenterX - arrowSize, size.height - 2.dp.toPx() - arrowSize)
                lineTo(arrowCenterX + arrowSize, size.height - 2.dp.toPx() - arrowSize)
                close()
            },
            color = indicatorColor.copy(alpha = 0.82f),
        )
    }
}

@Composable
fun Modifier.dialogVerticalScroll(state: ScrollState = rememberScrollState()): Modifier {
    val colors = MaterialTheme.colorScheme
    return drawWithContent {
            drawContent()
            if (state.maxValue <= 0) return@drawWithContent
            drawScrollIndicators(
                progress = state.value.toFloat() / state.maxValue.toFloat(),
                visibleFraction = size.height / (size.height + state.maxValue),
                canScrollBackward = state.value > 0,
                canScrollForward = state.value < state.maxValue,
                indicatorColor = colors.primary,
                trackColor = colors.onSurface.copy(alpha = 0.18f),
            )
        }
        .verticalScroll(state)
}

@Composable
fun DialogLazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        state = state,
        modifier = modifier.drawWithContent {
            drawContent()
            val info = state.layoutInfo
            if ((!state.canScrollBackward && !state.canScrollForward) || info.totalItemsCount == 0) {
                return@drawWithContent
            }
            drawScrollIndicators(
                progress =
                    state.firstVisibleItemIndex.toFloat() / (info.totalItemsCount - 1).coerceAtLeast(1),
                visibleFraction = info.visibleItemsInfo.size.toFloat() / info.totalItemsCount,
                canScrollBackward = state.canScrollBackward,
                canScrollForward = state.canScrollForward,
                indicatorColor = colors.primary,
                trackColor = colors.onSurface.copy(alpha = 0.18f),
            )
        },
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}
