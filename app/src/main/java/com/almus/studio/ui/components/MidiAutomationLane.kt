package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.almus.studio.data.MidiAutomationEvent
import com.almus.studio.audio.MidiFileCodec
import kotlin.math.max

@Composable
fun MidiAutomationLane(
    events: List<MidiAutomationEvent>,
    subdivision: Int,
    zoomX: Float,
    onAdd: (MidiAutomationEvent) -> Unit,
    onMove: (MidiAutomationEvent, Long, Int) -> Unit,
    onDelete: (MidiAutomationEvent) -> Unit
) {
    var type by remember { mutableStateOf("CC") }
    var controller by remember { mutableIntStateOf(1) }
    val scroll = rememberScrollState()
    val pixelsPerTick = (0.10f * zoomX).coerceIn(.025f, 1f)
    val totalTicks = max(3840L, (events.maxOfOrNull { it.tick } ?: 0L) + 960L)
    val width = max(900f, totalTicks * pixelsPerTick)
    val grid = com.almus.studio.audio.MidiQuantize.gridTicks(subdivision)

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Automation", style = MaterialTheme.typography.titleSmall)
            FilterChip(type == "CC", { type = "CC" }, label = { Text("CC") })
            FilterChip(type == "PB", { type = "PB" }, label = { Text("Pitch Bend") })
            FilterChip(type == "AT", { type = "AT" }, label = { Text("Aftertouch") })
            if (type == "CC") {
                Text("CC $controller")
                Slider(value = controller.toFloat(), onValueChange = { controller = it.toInt().coerceIn(0,127) }, valueRange = 0f..127f, modifier = Modifier.width(150.dp))
            }
            Text("Draw: tap • drag: move • long/tap point: delete")
        }
        Box(Modifier.height(150.dp).horizontalScroll(scroll)) {
            Canvas(Modifier.width(width.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.2f)).border(1.dp, MaterialTheme.colorScheme.outlineVariant).pointerInput(type, controller, grid, pixelsPerTick) {
                detectTapGestures { pos ->
                    val tick = (pos.x / pixelsPerTick).toLong().coerceAtLeast(0)
                    val value = ((1f - pos.y / size.height) * if (type == "PB") 16383 else 127).toInt().coerceIn(0, if (type == "PB") 16383 else 127)
                    onAdd(MidiAutomationEvent(tick = com.almus.studio.audio.MidiQuantize.snapTick(tick, subdivision), type = type, controller = if (type == "CC") controller else 0, value = value, channel = 0))
                }
            }) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(Color.Gray.copy(alpha=.2f), Offset(0f,y), Offset(size.width,y))
                }
                var t=0L
                while(t<=totalTicks){ val x=t*pixelsPerTick; drawLine(Color.Gray.copy(alpha=if(t%MidiFileCodec.PPQ==0L).35f else .12f), Offset(x,0f), Offset(x,size.height)); t+=grid }
                events.forEach { e ->
                    val x=e.tick*pixelsPerTick
                    val maxV=if(e.type=="PB")16383 else 127
                    val y=size.height-(e.value.toFloat()/maxV)*size.height
                    drawCircle(MaterialTheme.colorScheme.primary, 5f, Offset(x,y))
                }
            }
            events.forEach { e ->
                val maxV=if(e.type=="PB")16383 else 127
                val x=e.tick*pixelsPerTick-10f
                val y=150f-(e.value.toFloat()/maxV)*150f-10f
                Box(Modifier.offset(x=x.dp,y=y.dp).size(20.dp).pointerInput(e) {
                    detectDragGestures(onDrag={ change, amount -> change.consume(); val nt=(e.tick+(amount.x/pixelsPerTick)).toLong().coerceAtLeast(0); val nv=(e.value-(amount.y/150f*maxV)).toInt().coerceIn(0,maxV); onMove(e, com.almus.studio.audio.MidiQuantize.snapTick(nt,subdivision), nv) })
                }.pointerInput(e) { detectTapGestures(onLongPress={ onDelete(e) }) })
            }
        }
        Text("CC1 = Modulation • CC11 = Expression • PB = Pitch Bend • AT = Aftertouch", style=MaterialTheme.typography.bodySmall)
    }
}
