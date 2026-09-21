package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.PitchCurveAnalyzer
import kotlin.math.roundToInt

@Composable
fun PitchCurveEditor(result: PitchCurveAnalyzer.Result, modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(-1) }
    val manual = remember { mutableStateMapOf<Int, Int>() }
    val points = result.points
    val minMidi = 36f; val maxMidi = 84f
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Text("Original pitch • Target note • Correction", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(230.dp)
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { p ->
                            if (points.isNotEmpty()) selected = ((p.x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                        },
                        onDrag = { change, _ ->
                            if (selected >= 0 && points.isNotEmpty()) {
                                val midi = maxMidi - (change.position.y / size.height).coerceIn(0f,1f) * (maxMidi-minMidi)
                                manual[selected] = PitchCurveAnalyzer.nearestMidi(midi)
                            }
                            change.consume()
                        },
                        onDragEnd = {}, onDragCancel = {}
                    )
                }
        ) {
            val w = size.width; val h = size.height
            for (m in 36..84 step 12) {
                val y = h - ((m - minMidi)/(maxMidi-minMidi)).coerceIn(0f,1f)*h
                drawLine(outlineColor, Offset(0f,y), Offset(w,y), 1f)
            }
            fun xy(i:Int, midi:Float):Offset = Offset(if(points.size<=1) 0f else i.toFloat()/(points.size-1)*w, h-((midi-minMidi)/(maxMidi-minMidi)).coerceIn(0f,1f)*h)
            val original=Path(); var started=false
            points.forEachIndexed { i,p -> if(p.voiced){val q=xy(i,p.midi); if(!started){original.moveTo(q.x,q.y);started=true}else original.lineTo(q.x,q.y)} else started=false }
            drawPath(original, secondaryColor, style=Stroke(3f, cap=StrokeCap.Round))
            points.forEachIndexed { i,p -> if(p.voiced){ val target=manual[i]?.toFloat() ?: PitchCurveAnalyzer.nearestMidi(p.midi).toFloat(); val q=xy(i,target); drawCircle(primaryColor, if(i==selected)7f else 3.5f,q) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if(selected>=0) "Selected: ${points[selected].midi.roundToInt()} → ${manual[selected] ?: PitchCurveAnalyzer.nearestMidi(points[selected].midi)}" else "Tap a pitch point to select")
            Text("${points.count { it.voiced }} voiced points", style=MaterialTheme.typography.bodySmall)
        }
        Text("Manual target-note editing is a non-destructive editor layer; rendering it into audio is the next DSP step.", style=MaterialTheme.typography.bodySmall)
    }
}
