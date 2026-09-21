package com.almus.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import com.almus.studio.ui.theme.StudioAccent
import com.almus.studio.ui.theme.StudioRecord
import com.almus.studio.ui.theme.StudioSurfaceVariant
import com.almus.studio.viewmodel.TransportState

@Composable
fun TransportBar(
    transportState: TransportState,
    isRecording: Boolean,
    positionLabel: String,
    bpm: Int,
    metronomeEnabled: Boolean,
    monitoringEnabled: Boolean,
    countInBeats: Int,
    punchEnabled: Boolean,
    preRollBeats: Int,
    inputLevelDb: Float,
    inputClipping: Boolean,
    inputGainDb: Float,
    peakHistoryDb: FloatArray,
    onInputGainChange: (Float) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onToggleMetronome: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onCycleCountIn: () -> Unit,
    onTogglePunch: () -> Unit,
    onCyclePreRoll: () -> Unit,
    onOpenMonitoringSettings: () -> Unit,
    onOpenAudioSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportButton(
                icon = Icons.Filled.FiberManualRecord,
                tint = if (isRecording) StudioRecord else StudioRecord.copy(alpha = 0.7f),
                onClick = onRecord,
                contentDescription = "Record"
            )
            Spacer(Modifier.width(8.dp))
            TransportButton(
                icon = if (transportState is TransportState.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                tint = StudioAccent,
                onClick = { if (transportState is TransportState.Playing) onPause() else onPlay() },
                contentDescription = "Play/Pause"
            )
            Spacer(Modifier.width(8.dp))
            TransportButton(
                icon = Icons.Filled.Stop,
                tint = Color.White,
                onClick = onStop,
                contentDescription = "Stop"
            )
            Spacer(Modifier.width(8.dp))
            TransportButton(
                icon = Icons.Filled.Timer,
                tint = if (metronomeEnabled) StudioAccent else Color.White.copy(alpha = 0.6f),
                onClick = onToggleMetronome,
                contentDescription = "Metronome"
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onCycleCountIn, enabled = !isRecording) {
                Text(if (countInBeats == 0) "Count-in OFF" else "Count-in ${countInBeats}B")
            }
            TextButton(onClick = onTogglePunch, enabled = !isRecording) {
                Text(if (punchEnabled) "PUNCH ON" else "Punch OFF")
            }
            TextButton(onClick = onCyclePreRoll, enabled = !isRecording) {
                Text(if (preRollBeats == 0) "Pre 0" else "Pre ${preRollBeats}B")
            }
            Spacer(Modifier.width(2.dp))
            TransportButton(
                icon = Icons.Filled.GraphicEq,
                tint = if (monitoringEnabled) StudioAccent else Color.White.copy(alpha = 0.6f),
                onClick = onToggleMonitoring,
                onLongClick = onOpenMonitoringSettings,
                contentDescription = "Live pitch monitor (long-press for settings)"
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onOpenAudioSettings) { Text("AUDIO") }
        }

        Column(
            modifier = Modifier.width(180.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (inputClipping) "INPUT CLIP" else "INPUT ${"%.1f".format(inputLevelDb.coerceAtLeast(-96f))} dBFS",
                style = MaterialTheme.typography.labelSmall,
                color = if (inputClipping) StudioRecord else Color.White.copy(alpha = 0.75f)
            )
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val values = peakHistoryDb
                if (values.isNotEmpty()) {
                    val mid = size.height / 2f
                    val step = size.width / max(1, values.size - 1)
                    for (i in values.indices.drop(1)) {
                        val a = values[i - 1].coerceIn(-60f, 0f)
                        val b = values[i].coerceIn(-60f, 0f)
                        val y1 = size.height - ((a + 60f) / 60f) * size.height
                        val y2 = size.height - ((b + 60f) / 60f) * size.height
                        drawLine(Color.White.copy(alpha = 0.75f), Offset((i - 1) * step, y1), Offset(i * step, y2), strokeWidth = 2f)
                    }
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("IN ${inputGainDb.toInt()} dB", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = inputGainDb,
                    onValueChange = onInputGainChange,
                    valueRange = -24f..24f,
                    modifier = Modifier.width(110.dp)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(positionLabel, style = MaterialTheme.typography.titleMedium)
            Text("$bpm BPM", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    contentDescription: String,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
