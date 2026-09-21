package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.ui.theme.StudioSurfaceVariant
import com.almus.studio.ui.theme.StudioWaveform

/** Draws a single audio clip's waveform as a filled min/max envelope. */
@Composable
fun ClipWaveform(
    peaks: WaveformAnalyzer.Peaks?,
    widthDp: Dp,
    heightDp: Dp = 64.dp,
    color: Color = StudioWaveform,
    selected: Boolean = false,
    fadeInProgress: Float = 0f,
    fadeOutProgress: Float = 0f
) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(heightDp)
            .clip(RoundedCornerShape(4.dp))
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = color.copy(alpha = if (selected) 0.9f else 0.22f),
                shape = RoundedCornerShape(4.dp)
            )
            .background(StudioSurfaceVariant)
    ) {
        if (peaks != null && peaks.max.isNotEmpty()) {
            Canvas(modifier = Modifier.width(widthDp).height(heightDp)) {
                val midY = size.height / 2f
                val bucketWidth = size.width / peaks.max.size
                val upper = Path()
                val lower = Path()
                peaks.max.forEachIndexed { i, value ->
                    val x = i * bucketWidth + bucketWidth / 2f
                    val y = midY - value.coerceIn(-1f, 1f) * (midY - 5f)
                    if (i == 0) upper.moveTo(x, y) else upper.lineTo(x, y)
                }
                for (i in peaks.min.indices.reversed()) {
                    val x = i * bucketWidth + bucketWidth / 2f
                    val y = midY - peaks.min[i].coerceIn(-1f, 1f) * (midY - 5f)
                    if (i == peaks.min.lastIndex) lower.moveTo(x, y) else lower.lineTo(x, y)
                }
                // Filled envelope gives the waveform the dense, professional DAW look.
                val envelope = Path().apply {
                    addPath(upper)
                    lineTo(size.width, midY)
                    for (i in peaks.min.lastIndex downTo 0) {
                        val x = i * bucketWidth + bucketWidth / 2f
                        val y = midY - peaks.min[i].coerceIn(-1f, 1f) * (midY - 5f)
                        if (i == peaks.min.lastIndex) lineTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(envelope, color.copy(alpha = if (selected) 0.34f else 0.24f))
                drawPath(upper, color.copy(alpha = 0.92f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.7f))
                drawPath(lower, color.copy(alpha = 0.92f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.7f))
                drawLine(color.copy(alpha = 0.16f), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
                if (fadeInProgress > 0f) {
                    val x = size.width * fadeInProgress.coerceIn(0f, 1f)
                    drawLine(color.copy(alpha = 0.7f), Offset(x, 4f), Offset(x, size.height - 4f), strokeWidth = 1.5f)
                }
                if (fadeOutProgress > 0f) {
                    val x = size.width * (1f - fadeOutProgress.coerceIn(0f, 1f))
                    drawLine(color.copy(alpha = 0.7f), Offset(x, 4f), Offset(x, size.height - 4f), strokeWidth = 1.5f)
                }
                // Visual fade curves: these mirror the linear gain ramps used by the native engine.
                if (fadeInProgress > 0f) {
                    val endX = size.width * fadeInProgress.coerceIn(0f, 1f)
                    drawLine(
                        color.copy(alpha = 0.72f),
                        Offset(0f, size.height - 5f),
                        Offset(endX, midY),
                        strokeWidth = 2f
                    )
                }
                if (fadeOutProgress > 0f) {
                    val startX = size.width * (1f - fadeOutProgress.coerceIn(0f, 1f))
                    drawLine(
                        color.copy(alpha = 0.72f),
                        Offset(startX, midY),
                        Offset(size.width, size.height - 5f),
                        strokeWidth = 2f
                    )
                }
                for (i in peaks.max.indices step 8) {
                    val x = i * bucketWidth + bucketWidth / 2f
                    val topY = midY - peaks.max[i].coerceIn(-1f, 1f) * (midY - 5f)
                    val bottomY = midY - peaks.min[i].coerceIn(-1f, 1f) * (midY - 5f)
                    drawLine(color.copy(alpha = 0.28f), Offset(x, topY), Offset(x, bottomY), strokeWidth = 2f, cap = StrokeCap.Round)
                }
            }
        }
    }
}
