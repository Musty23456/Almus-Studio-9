package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.MidiFileCodec
import com.almus.studio.audio.MidiQuantize
import com.almus.studio.data.MidiClip
import com.almus.studio.data.MidiNote
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val WHITE_KEYS = 36
private const val KEY_HEIGHT = 20f
private const val LEFT_KEYS_DP = 54
private const val TICKS_VISIBLE = 3840L

@Composable
fun MidiPianoRoll(
    clip: MidiClip,
    selectedNoteId: String?,
    selectedNoteIds: Set<String> = emptySet(),
    subdivision: Int,
    zoomX: Float,
    zoomY: Float,
    onSubdivisionChange: (Int) -> Unit,
    onZoomX: (Float) -> Unit,
    onZoomY: (Float) -> Unit,
    onAddNote: (pitch: Int, startTick: Long, durationTicks: Long) -> Unit,
    onMoveNote: (noteId: String, startTick: Long, pitch: Int) -> Unit,
    onResizeNote: (noteId: String, durationTicks: Long) -> Unit,
    onVelocity: (noteId: String, velocity: Int) -> Unit,
    onDeleteNote: (noteId: String) -> Unit,
    onSelectNote: (String?) -> Unit,
    onToggleNoteSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopy: () -> Unit,
    onPaste: (Long) -> Unit,
    onDuplicate: () -> Unit,
    onQuantize: (Int) -> Unit,
    onSwing: (Int) -> Unit,
    onHumanize: (Int, Int) -> Unit,
    onVelocityScale: (Int) -> Unit,
    onLegato: () -> Unit,
    onTranspose: (Int) -> Unit,
    onScaleQuantize: (Int, Int) -> Unit,
    onCleanup: () -> Unit
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val ticksPerQuarter = MidiFileCodec.PPQ.toFloat()
    val pixelsPerTick = (0.10f * zoomX).coerceIn(0.025f, 1f)
    val rowHeight = (KEY_HEIGHT * zoomY).coerceIn(14f, 34f)
    val totalTicks = max(clip.lengthFrames.toFloat(), TICKS_VISIBLE.toFloat())
    val contentWidth = max(900f, totalTicks * pixelsPerTick)
    val contentHeight = 128 * rowHeight
    val grid = MidiQuantize.gridTicks(subdivision)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Grid", modifier = Modifier.padding(vertical = 8.dp))
            listOf(1 to "1/4", 2 to "1/8", 4 to "1/16").forEach { (value, label) ->
                FilterChip(selected = subdivision == value, onClick = { onSubdivisionChange(value) }, label = { Text(label) })
            }
            TextButton(onClick = { onZoomX((zoomX * 1.25f).coerceAtMost(6f)) }) { Text("X+") }
            TextButton(onClick = { onZoomX((zoomX / 1.25f).coerceAtLeast(.5f)) }) { Text("X-") }
            TextButton(onClick = { onZoomY((zoomY * 1.15f).coerceAtMost(2f)) }) { Text("Y+") }
            TextButton(onClick = { onZoomY((zoomY / 1.15f).coerceAtLeast(.7f)) }) { Text("Y-") }
            TextButton(onClick = { onQuantize(100) }) { Text("Quantize") }
            TextButton(onClick = { onSwing(50) }) { Text("Swing") }
            TextButton(onClick = { onHumanize(18, 8) }) { Text("Humanize") }
            TextButton(onClick = onLegato) { Text("Legato") }
            TextButton(onClick = { onVelocityScale(25) }) { Text("Vel+") }
            TextButton(onClick = { onTranspose(1) }) { Text("+1") }
            TextButton(onClick = { onTranspose(-1) }) { Text("-1") }
            TextButton(onClick = { onScaleQuantize(0, 1) }) { Text("C Major") }
            TextButton(onClick = onCleanup) { Text("Clean") }
            TextButton(onClick = onSelectAll) { Text("All") }
            TextButton(onClick = onClearSelection) { Text("Clear") }
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = { onPaste(0L) }) { Text("Paste") }
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
        }
        Row(Modifier.height(420.dp)) {
            PianoKeyboard(rowHeight = rowHeight, modifier = Modifier.width(LEFT_KEYS_DP.dp).verticalScroll(vertical))
            Box(Modifier.weight(1f).horizontalScroll(horizontal).verticalScroll(vertical)) {
                Canvas(
                    Modifier
                        .width(contentWidth.dp)
                        .height(contentHeight.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(grid, pixelsPerTick, rowHeight) {
                            detectTapGestures { pos ->
                                val rawTick = (pos.x / pixelsPerTick).toLong().coerceAtLeast(0)
                                val pitch = (127 - floor(pos.y / rowHeight).toInt()).coerceIn(0, 127)
                                val start = MidiQuantize.snapTick(rawTick, subdivision)
                                onAddNote(pitch, start, grid)
                            }
                        }
                ) {
                    // horizontal beat/grid lines
                    for (pitch in 0..127) {
                        val y = pitch * rowHeight
                        drawLine(
                            color = if (isBlackKey(pitch)) Color.Gray.copy(alpha = .16f) else Color.Gray.copy(alpha = .08f),
                            start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f
                        )
                    }
                    var t = 0L
                    while (t <= totalTicks.toLong()) {
                        val x = t * pixelsPerTick
                        val major = t % ticksPerQuarter.toLong() == 0L
                        drawLine(
                            color = if (major) Color.Gray.copy(alpha = .42f) else Color.Gray.copy(alpha = .16f),
                            start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = if (major) 2f else 1f
                        )
                        t += grid
                    }
                    clip.notes.forEach { note ->
                        val y = (127 - note.pitch) * rowHeight
                        val x = note.startTick * pixelsPerTick
                        val w = max(6f, note.durationTicks * pixelsPerTick)
                        val alpha = .45f + .5f * (note.velocity / 127f)
                        drawRect(
                            color = if (note.id == selectedNoteId || note.id in selectedNoteIds) primaryColor else secondaryColor,
                            topLeft = Offset(x, y + 1f), size = androidx.compose.ui.geometry.Size(w, rowHeight - 2f), alpha = alpha
                        )
                    }
                }
                // Gesture overlay for selecting/moving/resizing notes.
                clip.notes.forEach { note ->
                    val y = (127 - note.pitch) * rowHeight
                    val x = note.startTick * pixelsPerTick
                    val w = max(6f, note.durationTicks * pixelsPerTick)
                    Box(
                        Modifier.offset(x = x.dp, y = y.dp).width(w.dp).height(rowHeight.dp)
                            .pointerInput(note.id) { detectTapGestures(onTap = { onToggleNoteSelection(note.id) }) }
                            .pointerInput(note.id, pixelsPerTick, rowHeight, subdivision) {
                                detectDragGestures(
                                    onDragStart = { onSelectNote(note.id) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val dt = (amount.x / pixelsPerTick).toLong()
                                        val dp = (-amount.y / rowHeight).toInt()
                                        if (dt != 0L || dp != 0) {
                                            val targetTick = MidiQuantize.snapTick(note.startTick + dt, subdivision).coerceAtLeast(0)
                                            onMoveNote(note.id, targetTick, (note.pitch + dp).coerceIn(0,127))
                                        }
                                    }
                                )
                            }
                    ) {
                        // Small right-edge handle: drag horizontally to change note length.
                        Box(
                            Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                                .width(12.dp).fillMaxHeight()
                                .pointerInput(note.id, pixelsPerTick) {
                                    detectDragGestures { change, amount ->
                                        change.consume()
                                        val deltaTicks = (amount.x / pixelsPerTick).toLong()
                                        val duration = MidiQuantize.snapTick(note.durationTicks + deltaTicks, subdivision).coerceAtLeast(grid)
                                        onResizeNote(note.id, duration)
                                    }
                                }
                        )
                    }
                }
            }
        }
        selectedNoteId?.let { id ->
            val note = clip.notes.firstOrNull { it.id == id }
            if (note != null) {
                var velocity by remember(id, note.velocity) { mutableFloatStateOf(note.velocity.toFloat()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Note ${midiName(note.pitch)}", modifier = Modifier.padding(top = 10.dp))
                    Slider(value = velocity, onValueChange = { velocity = it }, onValueChangeFinished = { onVelocity(id, velocity.toInt()) }, valueRange = 1f..127f, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDeleteNote(id); onSelectNote(null) }) { Text("Delete") }
                }
            }
        }
        Text("Tap note to multi-select • drag to move • All/Clear/Copy/Paste/Duplicate for editing • selected: ${selectedNoteIds.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PianoKeyboard(rowHeight: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (pitch in 127 downTo 0) {
            val black = isBlackKey(pitch)
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).background(if (black) Color.DarkGray else Color.LightGray).border(0.5.dp, Color.Gray)) {
                if (!black && pitch % 12 == 0) Text("C${pitch / 12 - 1}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun isBlackKey(pitch: Int): Boolean = when (pitch % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
private fun midiName(pitch: Int): String { val names = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B"); return "${names[pitch % 12]}${pitch / 12 - 1}" }
