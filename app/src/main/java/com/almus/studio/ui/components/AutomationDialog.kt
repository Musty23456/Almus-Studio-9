package com.almus.studio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.almus.studio.data.AutomationParameter
import com.almus.studio.data.AutomationPoint

@Composable
fun AutomationDialog(
    trackName: String,
    playheadFrame: Long,
    lanes: Map<String, List<AutomationPoint>>,
    onAddPoint: (AutomationParameter, Float) -> Unit,
    onClearLane: (AutomationParameter) -> Unit,
    onDismiss: () -> Unit
) {
    var parameter by remember { mutableStateOf(AutomationParameter.VOLUME_DB) }
    var value by remember { mutableFloatStateOf(0f) }
    val range = when (parameter) {
        AutomationParameter.VOLUME_DB -> -60f..12f
        AutomationParameter.PAN -> -1f..1f
        AutomationParameter.REVERB_SEND_DB, AutomationParameter.DELAY_SEND_DB -> -60f..0f
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automation • $trackName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Point at frame $playheadFrame", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AutomationParameter.values().forEach { p ->
                        FilterChip(selected = parameter == p, onClick = {
                            parameter = p
                            value = lanes[p.name].orEmpty().lastOrNull()?.value ?: 0f
                        }, label = { Text(p.name.removeSuffix("_DB").replace('_', ' ')) })
                    }
                }
                Text("${"%.2f".format(value)}")
                Slider(value = value, onValueChange = { value = it }, valueRange = range)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAddPoint(parameter, value) }) { Text("Add point") }
                    OutlinedButton(onClick = { onClearLane(parameter) }) { Text("Clear lane") }
                }
                val points = lanes[parameter.name].orEmpty()
                Text("${points.size} point(s)", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(points) { point -> Text("${point.frame}  →  ${"%.2f".format(point.value)}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
