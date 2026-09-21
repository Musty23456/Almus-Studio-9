package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.studio.ui.theme.StudioTextSecondary

/** Professional bar/beat ruler. The timeline remains frame-based; pixels are presentation only. */
@Composable
fun TimelineRuler(
    widthDp: Dp,
    pixelsPerBeat: Float,
    beatsPerBar: Int,
    heightDp: Dp = 32.dp,
    subdivisions: Int = 4,
    onSelectionDrag: ((startPx: Float, endPx: Float) -> Unit)? = null,
    onTapSeek: ((positionPx: Float) -> Unit)? = null
) {
    val textColor = StudioTextSecondary
    var dragStartX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var dragCurrentX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    Canvas(
        modifier = Modifier
            .width(widthDp)
            .height(heightDp)
            .pointerInput(onSelectionDrag) {
                if (onSelectionDrag != null) {
                    detectDragGestures(
                        onDragStart = { offset -> dragStartX = offset.x; dragCurrentX = offset.x },
                        onDragEnd = { onSelectionDrag(dragStartX, dragCurrentX) }
                    ) { change, _ -> change.consume(); dragCurrentX = change.position.x }
                }
            }
            .pointerInput(onTapSeek) {
                if (onTapSeek != null) detectTapGestures(onTap = { onTapSeek(it.x) })
            }
    ) {
        val safeSubdivisions = subdivisions.coerceIn(1, 8)
        val subBeatPx = pixelsPerBeat / safeSubdivisions
        val totalSubdivisions = (size.width / subBeatPx).toInt() + 1

        for (step in 0..totalSubdivisions) {
            val x = step * subBeatPx
            val beat = step / safeSubdivisions
            val sub = step % safeSubdivisions
            val isBarStart = sub == 0 && beat % beatsPerBar == 0
            val isBeatStart = sub == 0
            val visibleSubdivisions = subBeatPx >= 7f
            if (!visibleSubdivisions && !isBeatStart) continue

            val lineHeight = when {
                isBarStart -> size.height * 0.9f
                isBeatStart -> size.height * 0.58f
                else -> size.height * 0.28f
            }
            val alpha = when {
                isBarStart -> 0.95f
                isBeatStart -> 0.60f
                else -> 0.30f
            }
            drawLine(
                color = textColor.copy(alpha = alpha),
                start = Offset(x, size.height - lineHeight),
                end = Offset(x, size.height),
                strokeWidth = if (isBarStart) 2.2f else 1f
            )

            if (isBarStart) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${beat / beatsPerBar + 1}", x + 5f, size.height * 0.54f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 22f
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                )
            }
        }

        // A subtle bottom rail makes the ruler read as a dedicated timeline surface.
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
    }
}
