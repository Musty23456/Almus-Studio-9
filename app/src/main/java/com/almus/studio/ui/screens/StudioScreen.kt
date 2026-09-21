package com.almus.studio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.AudioEngine
import com.almus.studio.audio.AutoPitchCaptureMode
import com.almus.studio.audio.EffectChainParams
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.audio.PitchCurveAnalyzer
import com.almus.studio.ui.components.PitchCurveEditor
import com.almus.studio.audio.AutoPitchCategory
import com.almus.studio.audio.AutoPitchPreset
import com.almus.studio.audio.AutoPitchPresetEngine
import com.almus.studio.data.AudioClip
import com.almus.studio.data.AutomationParameter
import com.almus.studio.data.Project
import com.almus.studio.data.ProjectRepository
import com.almus.studio.data.DrumSample
import com.almus.studio.ui.components.ClipWaveform
import com.almus.studio.ui.components.EffectsDialog
import com.almus.studio.ui.components.AutomationDialog
import com.almus.studio.ui.components.TimelineRuler
import com.almus.studio.ui.components.TrackHeader
import com.almus.studio.ui.components.TransportBar
import com.almus.studio.ui.components.MidiPianoRoll
import com.almus.studio.ui.components.MidiAutomationLane
import com.almus.studio.ui.theme.StudioAccent
import com.almus.studio.ui.theme.StudioWarning
import com.almus.studio.viewmodel.StudioViewModel
import java.io.File
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val PIXELS_PER_BEAT = 40f
private val TRACK_ROW_HEIGHT = 300.dp

@Composable
fun StudioScreen(viewModel: StudioViewModel, onBack: () -> Unit) {
    val project by viewModel.currentProject.collectAsState()
    val transportState by viewModel.transportState.collectAsState()
    val playheadFrame by viewModel.playheadFrame.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val midiRecording by viewModel.midiRecording.collectAsState()
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context) }

    var micPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionGranted = granted
    }

    var importTargetTrackId by remember { mutableStateOf<String?>(null) }
    var drumSampleTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var drumSampleEditor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val trackId = importTargetTrackId
        if (uri != null && trackId != null) {
            viewModel.importAudioFile(trackId, uri, uri.lastPathSegment ?: "import.wav")
        }
    }
    val drumSampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = drumSampleTarget
        if (uri != null && target != null) {
            viewModel.importDrumSample(target.first, target.second, uri, uri.lastPathSegment ?: "drum.wav")
        }
        drumSampleTarget = null
    }

    var effectsDialogTrackId by remember { mutableStateOf<String?>(null) }
    var selectedClip by remember { mutableStateOf<Pair<String, String>?>(null) } // trackId to clipId
    val exportState by viewModel.exportState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val clipboardAvailable by viewModel.clipboardAvailable.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val metronomeEnabled by viewModel.metronomeEnabled.collectAsState()
    val monitoringEnabled by viewModel.monitoringEnabled.collectAsState()
    val countInBeats by viewModel.countInBeats.collectAsState()
    val punchEnabled by viewModel.punchEnabled.collectAsState()
    val preRollBeats by viewModel.preRollBeats.collectAsState()
    val monitoringSettings by viewModel.monitoringSettings.collectAsState()
    val selectionRange by viewModel.selectionRange.collectAsState()
    val loopRegion by viewModel.loopRegion.collectAsState()
    val snapEnabled by viewModel.snapEnabled.collectAsState()
    var showMonitoringSettings by remember { mutableStateOf(false) }
    var showAudioSettings by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }
    var showMidi by remember { mutableStateOf(false) }
    var showDrumMachine by remember { mutableStateOf(false) }
    var showDrumKitManager by remember { mutableStateOf(false) }
    var pianoRollTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var instrumentTrackId by remember { mutableStateOf<String?>(null) }
    var samplerTrackId by remember { mutableStateOf<String?>(null) }
    var selectedMidiNoteId by remember { mutableStateOf<String?>(null) }
    var selectedMidiNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var midiClipboard by remember { mutableStateOf(emptyList<com.almus.studio.data.MidiNote>()) }
    var midiSubdivision by remember { mutableIntStateOf(2) }
    var midiZoomX by remember { mutableFloatStateOf(1f) }
    var midiZoomY by remember { mutableFloatStateOf(1f) }
    var automationTrackId by remember { mutableStateOf<String?>(null) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val currentProject = project ?: run {
        // Project was closed or not yet loaded; nothing to show.
        Scaffold(topBar = { TopAppBar(title = { Text("Almus Studio") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val bpm = currentProject.bpm
    val secondsPerBeat = 60f / bpm
    val framesPerBeat = secondsPerBeat * currentProject.sampleRate
    val effectivePixelsPerBeat = PIXELS_PER_BEAT * zoomLevel
    val pixelsPerFrame = effectivePixelsPerBeat / framesPerBeat
    val playheadSeconds = playheadFrame / currentProject.sampleRate.toFloat()
    val positionLabel = "%02d:%02d.%02d".format(
        (playheadSeconds / 60).toInt(),
        (playheadSeconds % 60).toInt(),
        ((playheadSeconds % 1) * 100).toInt()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentProject.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { viewModel.toggleSnap() }) {
                        Icon(Icons.Filled.GridOn, contentDescription = "Snap ${if (snapEnabled) "on" else "off"}", tint = if (snapEnabled) StudioAccent else LocalContentColor.current)
                    }
                    IconButton(onClick = { showMixer = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Open mixer")
                    }
                    TextButton(onClick = { automationTrackId = currentProject.tracks.firstOrNull()?.id }) { Text("AUTO") }
                    TextButton(onClick = { showMidi = true }) { Text("MIDI") }
                    TextButton(onClick = { showDrumMachine = true }) { Text("DRUMS") }
                    IconButton(onClick = { viewModel.addTrack() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add track")
                    }
                    IconButton(onClick = { viewModel.exportProject() }, enabled = exportState == null) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            TransportBar(
                transportState = transportState,
                isRecording = recordingState.isRecording,
                positionLabel = positionLabel,
                bpm = bpm,
                metronomeEnabled = metronomeEnabled,
                monitoringEnabled = monitoringEnabled,
                countInBeats = countInBeats,
                punchEnabled = punchEnabled,
                preRollBeats = preRollBeats,
                inputLevelDb = recordingState.inputLevelDb,
                inputClipping = recordingState.isClipping,
                inputGainDb = recordingState.inputGainDb,
                peakHistoryDb = recordingState.peakHistoryDb,
                onInputGainChange = viewModel::setInputGain,
                onPlay = viewModel::play,
                onPause = viewModel::pause,
                onStop = viewModel::stop,
                onRecord = {
                    if (!micPermissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (recordingState.isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                },
                onToggleMetronome = { viewModel.toggleMetronome() },
                onCycleCountIn = { viewModel.cycleCountIn() },
                onTogglePunch = { viewModel.togglePunchMode() },
                onCyclePreRoll = { viewModel.cyclePreRoll() },
                onToggleMonitoring = {
                    if (!micPermissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.toggleMonitoring()
                    }
                },
                onOpenMonitoringSettings = { showMonitoringSettings = true },
                onOpenAudioSettings = { showAudioSettings = true }
            )
        }
    ) { padding ->
        if (!micPermissionGranted) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    "Microphone permission not granted yet — recording is disabled until you allow it. Editing and mixing imported audio still works fully offline.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val verticalScroll = rememberScrollState()
            val horizontalScroll = rememberScrollState()

            Row(Modifier.weight(1f)) {
                // Track headers (fixed, vertically scrolls with the timeline)
                Column(
                    modifier = Modifier
                        .verticalScroll(verticalScroll)
                        .padding(top = 28.dp) // aligns with ruler height
                ) {
                    currentProject.tracks.forEach { track ->
                        Box(Modifier.height(TRACK_ROW_HEIGHT).padding(vertical = 4.dp)) {
                            TrackHeader(
                                track = track,
                                isArmed = recordingState.armedTrackId == track.id,
                                onVolumePreview = { viewModel.previewTrackVolume(track.id, it) },
                                onVolumeCommit = { viewModel.setTrackVolume(track.id, it) },
                                onPanPreview = { viewModel.previewTrackPan(track.id, it) },
                                onPanCommit = { viewModel.setTrackPan(track.id, it) },
                                onReverbSendCommit = { viewModel.setTrackReverbSend(track.id, it) },
                                onDelaySendCommit = { viewModel.setTrackDelaySend(track.id, it) },
                                onToggleMute = { viewModel.toggleMute(track.id) },
                                onToggleSolo = { viewModel.toggleSolo(track.id) },
                                onToggleArm = { viewModel.toggleArm(track.id) },
                                onOpenEffects = { effectsDialogTrackId = track.id },
                                onOpenAutomation = { automationTrackId = track.id }
                            )
                        }
                    }
                }

                // Timeline: ruler + per-track clip lanes, scrolls both ways
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom.isFinite() && zoom > 0f) {
                                    zoomLevel = (zoomLevel * zoom).coerceIn(0.25f, 8f)
                                }
                            }
                        }
                ) {
                    Box {
                        Column {
                            TimelineRuler(
                                widthDp = (4000f * zoomLevel).coerceAtLeast(4000f).dp,
                                pixelsPerBeat = effectivePixelsPerBeat,
                                beatsPerBar = currentProject.timeSignatureNumerator,
                                onSelectionDrag = { startPx, endPx ->
                                    val startFrame = (minOf(startPx, endPx) / pixelsPerFrame).toLong().coerceAtLeast(0)
                                    val endFrame = (maxOf(startPx, endPx) / pixelsPerFrame).toLong().coerceAtLeast(0)
                                    if (endFrame > startFrame) viewModel.setSelectionRange(startFrame..endFrame)
                                },
                                onTapSeek = { positionPx -> viewModel.seekTo((positionPx / pixelsPerFrame).toLong().coerceAtLeast(0)) }
                            )
                            Column(modifier = Modifier.verticalScroll(verticalScroll)) {
                                currentProject.tracks.forEach { track ->
                                    Box(Modifier.height(TRACK_ROW_HEIGHT).padding(vertical = 4.dp)) {
                                        TrackTimelineLane(
                                            projectId = currentProject.id,
                                            track = track,
                                            pixelsPerFrame = pixelsPerFrame,
                                            canPaste = clipboardAvailable,
                                            onImportRequested = {
                                                importTargetTrackId = track.id
                                                importLauncher.launch("audio/*")
                                            },
                                            onClipTapped = { clipId -> selectedClip = track.id to clipId },
                                            onPasteRequested = { viewModel.pasteClip(track.id, playheadFrame) },
                                            onClipMoved = { clipId, newStartFrame -> viewModel.moveClip(track.id, clipId, newStartFrame) },
                                            onClipTrimStart = { clipId, deltaFrames -> viewModel.trimClipStart(track.id, clipId, deltaFrames) },
                                            onClipTrimEnd = { clipId, deltaFrames -> viewModel.trimClipEnd(track.id, clipId, deltaFrames) },
                                            onAutomationPointMoved = { parameter, oldFrame, newFrame, value ->
                                                viewModel.moveTrackAutomationPoint(track.id, parameter, oldFrame, newFrame, value)
                                            },
                                            onAutomationPointDeleted = { parameter, frame -> viewModel.removeTrackAutomationPoint(track.id, parameter, frame) },
                                            selectedClipId = selectedClip?.second
                                        )
                                    }
                                }
                            }
                        }

                        // Selection, loop and playhead overlays share the same timeline coordinate space.
                        Box(modifier = Modifier.matchParentSize()) {
                            selectionRange?.let { range ->
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                val xDp = with(density) { (range.first * pixelsPerFrame).toDp() }
                                val wDp = with(density) { ((range.last - range.first) * pixelsPerFrame).coerceAtLeast(1f).toDp() }
                                Box(
                                    Modifier
                                        .offset(x = xDp)
                                        .width(wDp)
                                        .fillMaxHeight()
                                        .background(StudioAccent.copy(alpha = 0.15f))
                                )
                            }
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val playheadX = with(density) { (playheadFrame * pixelsPerFrame).toDp() }
                            Box(
                                Modifier
                                    .offset(x = playheadX - 10.dp)
                                    .width(20.dp)
                                    .fillMaxHeight()
                                    .pointerInput(pixelsPerFrame) {
                                        detectDragGestures(
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val deltaFrames = (dragAmount.x / pixelsPerFrame).toLong()
                                                viewModel.seekTo((playheadFrame + deltaFrames).coerceAtLeast(0L))
                                            }
                                        )
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(StudioAccent.copy(alpha = 0.95f))
                                )
                            }

                            loopRegion?.let { range ->
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                val xDp = with(density) { (range.first * pixelsPerFrame).toDp() }
                                val wDp = with(density) { ((range.last - range.first) * pixelsPerFrame).coerceAtLeast(1f).toDp() }
                                Box(
                                    Modifier
                                        .offset(x = xDp)
                                        .width(wDp)
                                        .fillMaxHeight()
                                        .border(2.dp, StudioWarning)
                                )
                            }
                        }
                    }
                }
            }

            // Zoom + A-B loop controls, below the timeline
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { zoomLevel = (zoomLevel / 1.4f).coerceAtLeast(0.25f) }) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { zoomLevel = (zoomLevel * 1.4f).coerceAtMost(8f) }) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.setLoopToSelection() }, enabled = selectionRange != null) {
                        Text("Set loop from selection")
                    }
                    if (loopRegion != null) {
                        TextButton(onClick = { viewModel.setLoopRegion(null) }) { Text("Clear loop") }
                    }
                }
            }
        }
    }

    if (showDrumMachine) {
        val drumTrackId = currentProject.tracks.firstOrNull { it.type == com.almus.studio.data.TrackType.MIDI && it.drumPattern != null }?.id
        val drumTrack = drumTrackId?.let { id -> currentProject.tracks.firstOrNull { it.id == id } }
        val pattern = drumTrack?.drumPattern?.normalized() ?: com.almus.studio.data.DrumPattern(steps = 16, bars = 1, sounds = emptyList())
        val sounds = listOf(36 to "Kick", 38 to "Snare", 39 to "Clap", 42 to "Closed Hat", 46 to "Open Hat", 45 to "Tom", 49 to "Crash", 44 to "Perc")
        AlertDialog(
            onDismissRequest = { showDrumMachine = false },
            title = { Text("🥁 Drum Machine • Professional") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Each step is a 1/16-note MIDI hit. Tap a cell to toggle it; long-press/drag editing can be added later without changing the stored pattern.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Button(onClick = { viewModel.ensureDrumTrack() }) { Text(if (drumTrack == null) "Create" else "Ready") }
                        OutlinedButton(onClick = { drumTrackId?.let { viewModel.addDrumPattern(it) } }, enabled = drumTrack != null) { Text("+ Pattern") }
                        OutlinedButton(onClick = { drumTrackId?.let { viewModel.copyDrumPattern(it) } }, enabled = drumTrack != null) { Text("Copy") }
                        OutlinedButton(onClick = { drumTrackId?.let { viewModel.randomizeDrumPattern(it) } }, enabled = drumTrack != null) { Text("Random") }
                    }
                    if (drumTrackId != null) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val bank = if (drumTrack?.drumPatterns?.isNotEmpty() == true) drumTrack!!.drumPatterns else listOfNotNull(drumTrack?.drumPattern)
                            bank.forEachIndexed { index, item ->
                                FilterChip(selected = index == (drumTrack?.activeDrumPattern ?: 0), onClick = { viewModel.selectDrumPattern(drumTrackId, index) }, label = { Text(item.name) })
                            }
                        }
                        val activePattern = (drumTrack?.drumPatterns?.getOrNull(drumTrack.activeDrumPattern) ?: drumTrack?.drumPattern)?.normalized()
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Steps", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                            FilterChip(selected = activePattern?.steps == 16, onClick = { viewModel.setDrumPatternLength(drumTrackId, 16) }, label = { Text("16") })
                            FilterChip(selected = activePattern?.steps == 32, onClick = { viewModel.setDrumPatternLength(drumTrackId, 32) }, label = { Text("32") })
                        }
                        Text("Swing ${activePattern?.swingPercent ?: 0}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = (activePattern?.swingPercent ?: 0).toFloat(), onValueChange = { viewModel.setDrumSwing(drumTrackId, it.toInt()) }, valueRange = 0f..75f)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Chain", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                            listOf(1, 2, 4).forEach { count -> OutlinedButton(onClick = { viewModel.setDrumChain(drumTrackId, List(count) { drumTrack?.activeDrumPattern ?: 0 }) }) { Text("${count}x") } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { viewModel.startMidiPlayback() }, enabled = drumTrack != null) { Text("Preview") }
                        OutlinedButton(onClick = { viewModel.stopMidiPlayback() }, enabled = drumTrack != null) { Text("Stop") }
                        OutlinedButton(onClick = { showDrumKitManager = true }, enabled = drumTrack != null) { Text("Kits") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(pattern.steps * pattern.bars) { i -> Text("${i + 1}", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall) }
                    }
                    sounds.forEach { (pitch, name) ->
                        val velocities = pattern.sounds.firstOrNull { it.pitch == pitch }?.velocities ?: emptyList()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(82.dp)) {
                                Text(name, style = MaterialTheme.typography.labelSmall)
                                val hasSample = drumTrack?.drumKit?.samples?.any { it.pitch == pitch && it.enabled } == true
                                TextButton(onClick = {
                                    drumTrackId?.let { id ->
                                        val existing = drumTrack?.drumKit?.samples?.firstOrNull { it.pitch == pitch && it.enabled }
                                        if (existing != null) drumSampleEditor = id to existing.id
                                        else { drumSampleTarget = id to pitch; drumSampleLauncher.launch("audio/*") }
                                    }
                                }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (hasSample) "Edit sample ✓" else "Load sample", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(pattern.steps * pattern.bars) { step ->
                                    val on = velocities.getOrNull(step)?.let { it > 0 } == true
                                    Surface(
                                        modifier = Modifier.size(34.dp).clickable {
                                            drumTrackId?.let { id -> viewModel.setDrumStep(id, pitch, step, if (on) 0 else 105) }
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        tonalElevation = if (on) 5.dp else 0.dp,
                                        color = if (on) StudioAccent else MaterialTheme.colorScheme.surfaceVariant
                                    ) { Box(contentAlignment = Alignment.Center) { Text(if (on) "●" else "·") } }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDrumMachine = false }) { Text("Close") } }
        )
    }

    if (showDrumKitManager) {
        val drumTrackId = currentProject.tracks.firstOrNull { it.type == com.almus.studio.data.TrackType.MIDI && it.drumPattern != null }?.id
        val drumTrack = drumTrackId?.let { id -> currentProject.tracks.firstOrNull { it.id == id } }
        if (drumTrackId == null || drumTrack == null) {
            showDrumKitManager = false
        } else {
            val kits = if (drumTrack.drumKits.isNotEmpty()) drumTrack.drumKits else listOf(drumTrack.drumKit)
            var kitName by remember(drumTrackId) { mutableStateOf(drumTrack.drumKit.name) }
            AlertDialog(
                onDismissRequest = { showDrumKitManager = false },
                title = { Text("🥁 Drum Kit Manager") },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Offline kit library • ${kits.size} kit(s)", style = MaterialTheme.typography.bodySmall)
                        kits.forEachIndexed { index, kit ->
                            val selected = index == drumTrack.activeDrumKit
                            Card(Modifier.fillMaxWidth().clickable { viewModel.selectDrumKit(drumTrackId, index) }) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(kit.name, style = MaterialTheme.typography.titleSmall)
                                        Text("${kit.samples.size} mapped sample(s)", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (selected) Text("ACTIVE", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        OutlinedTextField(value = kitName, onValueChange = { kitName = it }, label = { Text("Kit name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { if (viewModel.saveDrumKit(drumTrackId, kitName)) kitName = "Kit ${kits.size + 2}" }) { Text("Save As") }
                            OutlinedButton(onClick = { viewModel.duplicateDrumKit(drumTrackId, drumTrack.activeDrumKit) }) { Text("Duplicate") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { viewModel.renameDrumKit(drumTrackId, drumTrack.activeDrumKit, kitName) }) { Text("Rename") }
                            OutlinedButton(onClick = { viewModel.deleteDrumKit(drumTrackId, drumTrack.activeDrumKit) }, enabled = kits.size > 1) { Text("Delete") }
                        }
                        Text("Kits are stored inside the project JSON and remain offline.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { showDrumKitManager = false }) { Text("Done") } }
            )
        }
    }

    drumSampleEditor?.let { (trackId, sampleId) ->
        val track = currentProject.tracks.firstOrNull { it.id == trackId }
        val sample = track?.drumKit?.samples?.firstOrNull { it.id == sampleId }
        if (sample == null) { drumSampleEditor = null } else {
            AlertDialog(
                onDismissRequest = { drumSampleEditor = null },
                title = { Text("🎛️ Sample Editor • ${sample.name}") },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Non-destructive sampler controls", style = MaterialTheme.typography.bodySmall)
                        Text("Velocity ${sample.velocityMin}–${sample.velocityMax}", style = MaterialTheme.typography.labelSmall)
                        Text("Velocity minimum", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.velocityMin.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(velocityMin = v.toInt().coerceAtMost(sample.velocityMax)) } }, valueRange = 1f..127f)
                        Text("Velocity maximum", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.velocityMax.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(velocityMax = v.toInt().coerceAtLeast(sample.velocityMin)) } }, valueRange = 1f..127f)
                        Text("Gain ${String.format("%.1f", sample.gainDb)} dB", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.gainDb, onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(gainDb = v) } }, valueRange = -24f..12f)
                        Text("Pan ${String.format("%.2f", sample.pan)}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.pan, onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(pan = v) } }, valueRange = -1f..1f)
                        Text("Start trim ${sample.startFrame} frames", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.startFrame.coerceAtMost(50000L).toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(startFrame = v.toLong().coerceAtMost(it.endFrame.takeIf { e -> e > 0 } ?: Long.MAX_VALUE)) } }, valueRange = 0f..50000f)
                        Text("End trim ${sample.endFrame} frames (0 = source end)", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.endFrame.coerceIn(0L, 200000L).toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(endFrame = v.toLong()) } }, valueRange = 0f..200000f)
                        Text("Fade in ${sample.fadeInFrames} • Fade out ${sample.fadeOutFrames} frames", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { viewModel.updateDrumSample(trackId, sampleId) { it.copy(fadeInFrames = (it.fadeInFrames + 1000).coerceAtMost(50000)) } }) { Text("Fade +") }
                            OutlinedButton(onClick = { viewModel.updateDrumSample(trackId, sampleId) { it.copy(fadeOutFrames = (it.fadeOutFrames + 1000).coerceAtMost(50000)) } }) { Text("Fade Out +") }
                            OutlinedButton(onClick = { viewModel.addDrumVelocityLayer(trackId, sampleId) }) { Text("+ Velocity Layer") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = sample.reverse, onClick = { viewModel.updateDrumSample(trackId, sampleId) { it.copy(reverse = !it.reverse) } }, label = { Text("Reverse") })
                            FilterChip(selected = sample.normalize, onClick = { viewModel.updateDrumSample(trackId, sampleId) { it.copy(normalize = !it.normalize) } }, label = { Text("Normalize") })
                            FilterChip(selected = sample.loopEnabled, onClick = { viewModel.updateDrumSample(trackId, sampleId) { it.copy(loopEnabled = !it.loopEnabled) } }, label = { Text("Loop") })
                        }
                        Text("Root note ${sample.rootNote} • Fine tune ${sample.fineTuneCents} cents", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.rootNote.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(rootNote = v.toInt()) } }, valueRange = 0f..127f, steps = 126)
                        Slider(value = sample.fineTuneCents.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(fineTuneCents = v.toInt()) } }, valueRange = -100f..100f, steps = 199)
                        if (sample.loopEnabled) {
                            Text("Loop ${sample.loopStartFrame} → ${sample.loopEndFrame} frames", style = MaterialTheme.typography.labelSmall)
                            Slider(value = sample.loopStartFrame.coerceAtMost(200000L).toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(loopStartFrame = v.toLong().coerceAtMost(it.loopEndFrame.takeIf { e -> e > 0 } ?: Long.MAX_VALUE)) } }, valueRange = 0f..200000f)
                            Slider(value = sample.loopEndFrame.coerceIn(0L, 200000L).toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(loopEndFrame = v.toLong()) } }, valueRange = 0f..200000f)
                        }
                        Text("Sampler ADSR", style = MaterialTheme.typography.titleSmall)
                        Text("Attack ${sample.attackMs} ms • Decay ${sample.decayMs} ms • Sustain ${String.format("%.2f", sample.sustain)} • Release ${sample.releaseMs} ms", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.attackMs.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(attackMs = v.toInt()) } }, valueRange = 0f..1000f)
                        Slider(value = sample.decayMs.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(decayMs = v.toInt()) } }, valueRange = 0f..2000f)
                        Slider(value = sample.sustain, onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(sustain = v) } }, valueRange = 0f..1f)
                        Slider(value = sample.releaseMs.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(releaseMs = v.toInt()) } }, valueRange = 1f..2000f)
                        Text("Choke group ${sample.chokeGroup}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = sample.chokeGroup.toFloat(), onValueChange = { v -> viewModel.updateDrumSample(trackId, sampleId) { it.copy(chokeGroup = v.toInt()) } }, valueRange = 0f..32f, steps = 31)
                        Text("Sample stays offline and edits are non-destructive.", style = MaterialTheme.typography.labelSmall)
                    }
                },
                confirmButton = { TextButton(onClick = { drumSampleEditor = null }) { Text("Done") } }
            )
        }
    }

    if (showMidi) {
        val midiTracks = currentProject.tracks.filter { it.type == com.almus.studio.data.TrackType.MIDI }
        AlertDialog(
            onDismissRequest = { showMidi = false },
            title = { Text("MIDI Engine • Piano Roll") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MIDI clips are stored in the project and edited non-destructively.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.addMidiTrack() }) { Text("Add MIDI Track") }
                        Button(onClick = { viewModel.startMidiPlayback() }) { Text("Preview MIDI") }
                        OutlinedButton(onClick = { viewModel.stopMidiPlayback() }) { Text("Stop") }
                    }
                    Text("MIDI Capture", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    val recTrack = midiTracks.firstOrNull()
                    var overdub by remember { mutableStateOf(false) }
                    var loopRec by remember { mutableStateOf(false) }
                    var countIn by remember { mutableStateOf(1) }
                    var quantize by remember { mutableStateOf(1) }
                    var loopBars by remember { mutableStateOf(1) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = recTrack != null && !midiRecording.isRecording, onClick = {
                            recTrack?.let {
                                val loopTicks = if (loopRec) com.almus.studio.audio.MidiFileCodec.PPQ * 4L * loopBars else null
                                viewModel.configureMidiRecording(overdub = overdub, loopRecording = loopRec, countInBars = countIn, quantizeSubdivision = quantize, loopLengthTicks = loopTicks)
                                viewModel.startMidiRecording(it.id)
                            }
                        }) { Text("Record") }
                        FilterChip(selected = overdub, onClick = { overdub = !overdub }, label = { Text("Overdub") })
                        FilterChip(selected = loopRec, onClick = { loopRec = !loopRec }, label = { Text("Loop") })
                        Button(enabled = midiRecording.isRecording && !midiRecording.isPaused, onClick = { viewModel.pauseMidiRecording() }) { Text("Pause") }
                        Button(enabled = midiRecording.isRecording && midiRecording.isPaused, onClick = { viewModel.resumeMidiRecording() }) { Text("Resume") }
                        OutlinedButton(enabled = midiRecording.isRecording, onClick = { viewModel.stopMidiRecording() }) { Text("Stop Record") }
                    }
                    if (midiRecording.isRecording) {
                        Text("Recording ${midiRecording.trackId ?: "MIDI"} • ${if (midiRecording.isPaused) "Paused" else "Armed"}", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Count-in ${countIn} bar${if (countIn == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = countIn.toFloat(), onValueChange = { countIn = it.toInt().coerceIn(0, 4) }, valueRange = 0f..4f, steps = 3, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quantize ${quantize}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = quantize.toFloat(), onValueChange = { quantize = it.toInt().coerceIn(0, 4) }, valueRange = 0f..4f, steps = 3, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var synthEnabled by remember { mutableStateOf(true) }
                        Checkbox(checked = synthEnabled, onCheckedChange = { synthEnabled = it; viewModel.setInternalMidiSynthEnabled(it) })
                        Text("Built-in Sine Synth")
                    }
                    Text("Phase 7.2: MIDI playback follows the project playhead. External Android MIDI output is supported by the playback engine.", style = MaterialTheme.typography.bodySmall)
                    if (midiTracks.isEmpty()) Text("Create a MIDI track to start drawing notes.")
                    midiTracks.forEach { track ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(track.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                if (track.midiClips.any { it.takeId != null }) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Take lanes", style = MaterialTheme.typography.labelSmall)
                                        track.midiClips.filter { it.takeId != null }.distinctBy { it.takeId }.forEachIndexed { idx, clip ->
                                            FilterChip(selected = track.midiTakeCompingEnabled && clip.takeId == track.activeMidiTakeId, onClick = { viewModel.selectMidiTake(track.id, clip.takeId ?: clip.id) }, label = { Text("T${clip.takeNumber.coerceAtLeast(idx + 1)}") })
                                        }
                                    }
                                }
                                Text("Channel ${track.midiChannel + 1} • Program ${track.midiProgram}", style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { viewModel.addMidiClip(track.id) }) { Text("Add MIDI Clip") }
                                    OutlinedButton(onClick = { instrumentTrackId = track.id }) { Text("Instrument") }
                                    OutlinedButton(onClick = { samplerTrackId = track.id }) { Text("Sampler") }
                                }
                                Text("${track.midiWaveform} • ADSR ${track.midiAttackMs}/${track.midiDecayMs}/${"%.2f".format(track.midiSustain)}/${track.midiReleaseMs} ms", style = MaterialTheme.typography.labelSmall)
                                track.midiClips.forEach { clip ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(clip.name, Modifier.weight(1f))
                                        Text("${clip.notes.size} notes", style = MaterialTheme.typography.labelSmall)
                                        TextButton(onClick = { pianoRollTarget = track.id to clip.id; selectedMidiNoteId = null }) { Text("Open Piano Roll") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMidi = false }) { Text("Close") } }
        )
    }

    instrumentTrackId?.let { trackId ->
        val instrumentTrack = currentProject.tracks.firstOrNull { it.id == trackId }
        if (instrumentTrack != null) {
            var attack by remember(trackId, instrumentTrack.midiAttackMs) { mutableFloatStateOf(instrumentTrack.midiAttackMs.toFloat()) }
            var decay by remember(trackId, instrumentTrack.midiDecayMs) { mutableFloatStateOf(instrumentTrack.midiDecayMs.toFloat()) }
            var sustain by remember(trackId, instrumentTrack.midiSustain) { mutableFloatStateOf(instrumentTrack.midiSustain) }
            var release by remember(trackId, instrumentTrack.midiReleaseMs) { mutableFloatStateOf(instrumentTrack.midiReleaseMs.toFloat()) }
            var octave by remember(trackId, instrumentTrack.midiOctave) { mutableIntStateOf(instrumentTrack.midiOctave) }
            var gain by remember(trackId, instrumentTrack.midiGainDb) { mutableFloatStateOf(instrumentTrack.midiGainDb) }
            var waveform by remember(trackId, instrumentTrack.midiWaveform) { mutableStateOf(instrumentTrack.midiWaveform) }
            AlertDialog(
                onDismissRequest = { instrumentTrackId = null },
                title = { Text("MIDI Instrument • ${instrumentTrack.name}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Waveform", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("SINE", "SQUARE", "SAW", "TRIANGLE").forEach { w ->
                                FilterChip(selected = waveform == w, onClick = { waveform = w }, label = { Text(w) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("SINE", "PLUCK", "BRIGHT", "PAD").forEach { preset ->
                                TextButton(onClick = { viewModel.applyMidiPreset(trackId, preset); instrumentTrackId = null }) { Text(preset) }
                            }
                        }
                        Text("Attack ${attack.toInt()} ms")
                        Slider(value = attack, onValueChange = { attack = it }, valueRange = 1f..1000f)
                        Text("Decay ${decay.toInt()} ms")
                        Slider(value = decay, onValueChange = { decay = it }, valueRange = 1f..1500f)
                        Text("Sustain ${(sustain * 100).toInt()}%")
                        Slider(value = sustain, onValueChange = { sustain = it }, valueRange = 0f..1f)
                        Text("Release ${release.toInt()} ms")
                        Slider(value = release, onValueChange = { release = it }, valueRange = 1f..2000f)
                        Text("Octave $octave")
                        Slider(value = octave.toFloat(), onValueChange = { octave = it.toInt() }, valueRange = -2f..2f, steps = 3)
                        Text("Gain ${"%.1f".format(gain)} dB")
                        Slider(value = gain, onValueChange = { gain = it }, valueRange = -24f..0f)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.setMidiInstrument(trackId, waveform, attack.toInt(), decay.toInt(), sustain, release.toInt(), octave, gain); instrumentTrackId = null }) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { instrumentTrackId = null }) { Text("Cancel") } }
            )
        }
    }

    samplerTrackId?.let { trackId ->
        val track = currentProject.tracks.firstOrNull { it.id == trackId }
        if (track != null) {
            var enabled by remember(trackId, track.samplerEnabled) { mutableStateOf(track.samplerEnabled) }
            var mono by remember(trackId, track.samplerMono) { mutableStateOf(track.samplerMono) }
            var legato by remember(trackId, track.samplerLegato) { mutableStateOf(track.samplerLegato) }
            var glide by remember(trackId, track.samplerGlideMs) { mutableFloatStateOf(track.samplerGlideMs.toFloat()) }
            var poly by remember(trackId, track.samplerPolyphony) { mutableFloatStateOf(track.samplerPolyphony.toFloat()) }
            var sustainPedal by remember(trackId, track.samplerSustainPedal) { mutableStateOf(track.samplerSustainPedal) }
            var gain by remember(trackId, track.samplerGainDb) { mutableFloatStateOf(track.samplerGainDb) }
            var bend by remember(trackId, track.samplerPitchBend) { mutableFloatStateOf(track.samplerPitchBend) }
            var bendRange by remember(trackId, track.samplerBendRangeSemitones) { mutableFloatStateOf(track.samplerBendRangeSemitones.toFloat()) }
            var modulation by remember(trackId, track.samplerModulation) { mutableFloatStateOf(track.samplerModulation) }
            var aftertouch by remember(trackId, track.samplerAftertouch) { mutableFloatStateOf(track.samplerAftertouch) }
            var velocityPitch by remember(trackId, track.samplerVelocityPitchCents) { mutableFloatStateOf(track.samplerVelocityPitchCents.toFloat()) }
            AlertDialog(
                onDismissRequest = { samplerTrackId = null },
                title = { Text("🎹 Sampler Instrument • ${track.name}") },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Chromatic sample instrument • velocity-sensitive • offline", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled, { enabled = it }); Text("Sampler enabled") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(mono, { mono = it }); Text("Mono") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(legato, { legato = it }); Text("Legato") }
                        Text("Polyphony ${poly.toInt()}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = poly, onValueChange = { poly = it }, valueRange = 1f..64f, steps = 62)
                        Text("Glide ${glide.toInt()} ms", style = MaterialTheme.typography.labelSmall)
                        Slider(value = glide, onValueChange = { glide = it }, valueRange = 0f..2000f)
                        Text("Sampler gain ${"%.1f".format(gain)} dB", style = MaterialTheme.typography.labelSmall)
                        Slider(value = gain, onValueChange = { gain = it }, valueRange = -24f..6f)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sustainPedal, { sustainPedal = it }); Text("Sustain pedal") }
                        Text("Performance Controls", style = MaterialTheme.typography.titleSmall)
                        Text("Pitch Bend ${(bend * bendRange).toInt()} semitones", style = MaterialTheme.typography.labelSmall)
                        Slider(value = bend, onValueChange = { bend = it }, valueRange = -1f..1f)
                        Text("Bend range ${bendRange.toInt()} semitones", style = MaterialTheme.typography.labelSmall)
                        Slider(value = bendRange, onValueChange = { bendRange = it }, valueRange = 1f..24f, steps = 22)
                        Text("Modulation ${(modulation * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = modulation, onValueChange = { modulation = it }, valueRange = 0f..1f)
                        Text("Aftertouch ${(aftertouch * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = aftertouch, onValueChange = { aftertouch = it }, valueRange = 0f..1f)
                        Text("Velocity → Pitch ${velocityPitch.toInt()} cents", style = MaterialTheme.typography.labelSmall)
                        Slider(value = velocityPitch, onValueChange = { velocityPitch = it }, valueRange = -1200f..1200f)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { bend = 0f }) { Text("Center Bend") }
                            OutlinedButton(onClick = { modulation = 0f; aftertouch = 0f }) { Text("Clear Expr") }
                        }
                        Text("Keyboard / Pads", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (48..72).forEach { pitch ->
                                OutlinedButton(onClick = { viewModel.previewSamplerNote(trackId, pitch, 100) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("M$pitch") }
                            }
                        }
                        Text("Mapped samples: ${track.drumKit.samples.count { it.enabled }} • velocity layers: ${track.drumKit.samples.groupBy { it.pitch }.values.count { it.size > 1 }}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    viewModel.setSamplerInstrument(trackId, enabled, mono, legato, glide.toInt(), poly.toInt(), sustainPedal, gain)
                    viewModel.setSamplerPerformance(trackId, bend, bendRange.toInt(), modulation, aftertouch, velocityPitch.toInt())
                    samplerTrackId = null
                }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { samplerTrackId = null }) { Text("Cancel") } }
            )
        }
    }

    pianoRollTarget?.let { (trackId, clipId) ->
        val targetTrack = currentProject.tracks.firstOrNull { it.id == trackId }
        val targetClip = targetTrack?.midiClips?.firstOrNull { it.id == clipId }
        if (targetClip != null) {
            AlertDialog(
                onDismissRequest = { pianoRollTarget = null; selectedMidiNoteId = null; selectedMidiNoteIds = emptySet() },
                title = { Text("Piano Roll • ${targetClip.name}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MidiPianoRoll(
                        clip = targetClip,
                        selectedNoteId = selectedMidiNoteId,
                        selectedNoteIds = selectedMidiNoteIds,
                        subdivision = midiSubdivision,
                        zoomX = midiZoomX,
                        zoomY = midiZoomY,
                        onSubdivisionChange = { midiSubdivision = it },
                        onZoomX = { midiZoomX = it },
                        onZoomY = { midiZoomY = it },
                        onAddNote = { pitch, start, duration -> viewModel.addMidiNote(trackId, clipId, pitch, start, duration) },
                        onMoveNote = { id, start, pitch -> viewModel.moveMidiNote(trackId, clipId, id, start, pitch) },
                        onResizeNote = { id, duration -> viewModel.resizeMidiNote(trackId, clipId, id, duration) },
                        onVelocity = { id, velocity -> viewModel.setMidiNoteVelocity(trackId, clipId, id, velocity) },
                        onDeleteNote = { id -> viewModel.deleteMidiNote(trackId, clipId, id) },
                        onSelectNote = { selectedMidiNoteId = it },
                        onToggleNoteSelection = { id -> selectedMidiNoteIds = if (id in selectedMidiNoteIds) selectedMidiNoteIds - id else selectedMidiNoteIds + id; selectedMidiNoteId = id },
                        onSelectAll = { selectedMidiNoteIds = targetClip.notes.map { it.id }.toSet(); selectedMidiNoteId = targetClip.notes.firstOrNull()?.id },
                        onClearSelection = { selectedMidiNoteIds = emptySet(); selectedMidiNoteId = null },
                        onCopy = { midiClipboard = viewModel.midiCopyNotes(trackId, clipId, selectedMidiNoteIds) },
                        onPaste = { tick -> viewModel.midiPasteNotes(trackId, clipId, midiClipboard, tick) },
                        onDuplicate = { viewModel.midiDuplicateNotes(trackId, clipId, selectedMidiNoteIds) },
                        onQuantize = { strength -> viewModel.midiQuantize(trackId, clipId, midiSubdivision, strength) },
                        onSwing = { percent -> viewModel.midiSwing(trackId, clipId, midiSubdivision, percent) },
                        onHumanize = { ticks, velocity -> viewModel.midiHumanize(trackId, clipId, ticks, velocity) },
                        onVelocityScale = { amount -> viewModel.midiVelocityScale(trackId, clipId, amount) },
                        onLegato = { viewModel.midiLegato(trackId, clipId) },
                        onTranspose = { semitones -> viewModel.midiTranspose(trackId, clipId, semitones) },
                        onScaleQuantize = { root, scale -> viewModel.midiScaleQuantize(trackId, clipId, root, scale) },
                        onCleanup = { viewModel.midiCleanup(trackId, clipId) }
                    )
                    MidiAutomationLane(
                        events = targetClip.automation,
                        subdivision = midiSubdivision,
                        zoomX = midiZoomX,
                        onAdd = { viewModel.midiAddAutomationPoint(trackId, clipId, it) },
                        onMove = { old, tick, value -> viewModel.midiMoveAutomationPoint(trackId, clipId, old, tick, value) },
                        onDelete = { viewModel.midiDeleteAutomationPoint(trackId, clipId, it.tick, it.type, it.controller, it.channel) }
                    )
                    }
                },
                confirmButton = { TextButton(onClick = { pianoRollTarget = null; selectedMidiNoteId = null; selectedMidiNoteIds = emptySet() }) { Text("Done") } }
            )
        }
    }

    if (showMixer) {
        val masterVolume by viewModel.masterVolumeDb.collectAsState()
        val masterPeaks by viewModel.masterPeaksDb.collectAsState()
        val masterLoudness by viewModel.masterLoudness.collectAsState()
        val reverbReturn by viewModel.reverbReturnVolumeDb.collectAsState()
        val delayReturn by viewModel.delayReturnVolumeDb.collectAsState()
        val reverbMute by viewModel.reverbReturnMuted.collectAsState()
        val delayMute by viewModel.delayReturnMuted.collectAsState()
        val reverbSolo by viewModel.reverbReturnSolo.collectAsState()
        val delaySolo by viewModel.delayReturnSolo.collectAsState()
        ModalBottomSheet(onDismissRequest = { showMixer = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vocal Mixer", style = MaterialTheme.typography.headlineSmall)
                Text("TRACK → SEND → FX RETURN → MASTER", style = MaterialTheme.typography.labelMedium)
                var snapshotName by remember { mutableStateOf("") }
                var showSnapshotNameDialog by remember { mutableStateOf(false) }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Mix Snapshots", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("Save and recall the current vocal mixer state without changing clips or automation.", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { showSnapshotNameDialog = true }) { Text("Save") }
                        }
                        if (currentProject.vocalMixSnapshots.isEmpty()) {
                            Text("No snapshots yet.", style = MaterialTheme.typography.labelSmall)
                        } else {
                            currentProject.vocalMixSnapshots.forEach { snapshot ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(snapshot.name, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { viewModel.applyVocalMixSnapshot(snapshot.id) }) { Text("Recall") }
                                    TextButton(onClick = { viewModel.deleteVocalMixSnapshot(snapshot.id) }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                if (showSnapshotNameDialog) {
                    AlertDialog(
                        onDismissRequest = { showSnapshotNameDialog = false },
                        title = { Text("Save Mix Snapshot") },
                        text = {
                            OutlinedTextField(
                                value = snapshotName,
                                onValueChange = { snapshotName = it.take(48) },
                                label = { Text("Snapshot name") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (viewModel.saveVocalMixSnapshot(snapshotName)) {
                                    snapshotName = ""
                                    showSnapshotNameDialog = false
                                }
                            }) { Text("Save") }
                        },
                        dismissButton = { TextButton(onClick = { showSnapshotNameDialog = false }) { Text("Cancel") } }
                    )
                }
                Text("Signal flow: vocal channels feed shared Reverb and Delay returns, then both returns feed the master bus.", style = MaterialTheme.typography.bodySmall)
                currentProject.tracks.forEach { track ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(track.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("${track.volumeDb.toInt()} dB  •  Pan ${"%.1f".format(track.pan)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = track.muted, onClick = { viewModel.toggleMute(track.id) }, label = { Text("Mute") })
                                FilterChip(selected = track.solo, onClick = { viewModel.toggleSolo(track.id) }, label = { Text("Solo") })
                            }
                            Text("Rev Send ${track.reverbSendDb.toInt()} dB  →  REV RETURN", style = MaterialTheme.typography.labelSmall)
                            Text("Dly Send ${track.delaySendDb.toInt()} dB  →  DLY RETURN", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Reverb Return", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Slider(value = reverbReturn, onValueChange = { viewModel.setReverbReturnVolume(it) }, valueRange = -60f..6f)
                        Text("${reverbReturn.toInt()} dB", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = reverbMute, onClick = { viewModel.setReverbReturnMuted(!reverbMute) }, label = { Text("Mute") })
                            FilterChip(selected = reverbSolo, onClick = { viewModel.setReverbReturnSolo(!reverbSolo) }, label = { Text("Solo") })
                        }
                    }
                }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Delay Return", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Slider(value = delayReturn, onValueChange = { viewModel.setDelayReturnVolume(it) }, valueRange = -60f..6f)
                        Text("${delayReturn.toInt()} dB", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = delayMute, onClick = { viewModel.setDelayReturnMuted(!delayMute) }, label = { Text("Mute") })
                            FilterChip(selected = delaySolo, onClick = { viewModel.setDelayReturnSolo(!delaySolo) }, label = { Text("Solo") })
                        }
                    }
                }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Master", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("${masterVolume.toInt()} dB")
                        Slider(value = masterVolume, onValueChange = viewModel::previewMasterVolume, onValueChangeFinished = { viewModel.setMasterVolume(masterVolume) }, valueRange = -60f..6f)
                        Text("L ${"%.1f".format(masterPeaks.getOrElse(0) { -96f })} dBFS   R ${"%.1f".format(masterPeaks.getOrElse(1) { -96f })} dBFS", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = ((masterPeaks.maxOrNull() ?: -96f) + 60f).coerceIn(0f, 60f) / 60f, modifier = Modifier.fillMaxWidth())
                        Text("Loudness: M ${"%.1f".format(masterLoudness.getOrElse(0) { -96f })} dBFS  •  S ${"%.1f".format(masterLoudness.getOrElse(1) { -96f })} dBFS", style = MaterialTheme.typography.labelSmall)
                        Text("Peak ${"%.1f".format(masterLoudness.getOrElse(2) { -96f })} dBFS", style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Master Limiter", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            FilterChip(selected = currentProject.masterLimiterEnabled, onClick = { viewModel.setMasterLimiterEnabled(!currentProject.masterLimiterEnabled) }, label = { Text(if (currentProject.masterLimiterEnabled) "ON" else "OFF") })
                        }
                        Text("Ceiling ${"%.1f".format(currentProject.masterLimiterCeilingDb)} dBFS")
                        Slider(value = currentProject.masterLimiterCeilingDb, onValueChange = viewModel::setMasterLimiterCeilingDb, valueRange = -12f..-0.1f)
                        Text("Release ${currentProject.masterLimiterReleaseMs.toInt()} ms")
                        Slider(value = currentProject.masterLimiterReleaseMs, onValueChange = viewModel::setMasterLimiterReleaseMs, valueRange = 5f..1000f)
                    }
                }
            }
        }
    }

    automationTrackId?.let { trackId ->
        val track = currentProject.tracks.find { it.id == trackId }
        if (track != null) {
            AutomationDialog(
                trackName = track.name,
                playheadFrame = playheadFrame,
                lanes = track.automation,
                onAddPoint = { parameter, value -> viewModel.setTrackAutomationPoint(track.id, parameter, playheadFrame, value) },
                onClearLane = { parameter -> viewModel.clearTrackAutomation(track.id, parameter) },
                onDismiss = { automationTrackId = null }
            )
        }
    }

    effectsDialogTrackId?.let { trackId ->
        val track = currentProject.tracks.find { it.id == trackId }
        if (track != null) {
            EffectsDialog(
                trackName = track.name,
                initial = viewModel.effectsFor(trackId),
                onDismiss = { effectsDialogTrackId = null },
                onApply = { params ->
                    viewModel.updateTrackEffects(trackId, params)
                    effectsDialogTrackId = null
                }
            )
        }
    }

    var pitchCorrectionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pitchEditorTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // trackId to clipId

    selectedClip?.let { (trackId, clipId) ->
        val clip = currentProject.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId }
        if (clip != null) {
            ClipActionsDialog(
                clip = clip,
                sampleRate = currentProject.sampleRate,
                onDismiss = { selectedClip = null },
                onSplitAtPlayhead = {
                    viewModel.splitClip(trackId, clipId, playheadFrame)
                    selectedClip = null
                },
                onCopy = {
                    viewModel.copyClip(trackId, clipId)
                    selectedClip = null
                },
                onDuplicate = {
                    viewModel.duplicateClip(trackId, clipId)
                    selectedClip = null
                },
                onSlip = { deltaFrames -> viewModel.slipClip(trackId, clipId, deltaFrames) },
                onCrossfadePrevious = { viewModel.crossfadeWithPrevious(trackId, clipId, (currentProject.sampleRate * 0.08f).toLong()) },
                onRippleDelete = { viewModel.rippleDeleteClip(trackId, clipId); selectedClip = null },
                onCleanupSilence = { viewModel.cleanupVocalSilence(trackId, clipId); selectedClip = null },
                onCut = {
                    viewModel.cutClip(trackId, clipId)
                    selectedClip = null
                },
                onPitchCorrect = {
                    pitchCorrectionTarget = trackId to clipId
                    selectedClip = null
                },
                onPitchEditor = {
                    pitchEditorTarget = trackId to clipId
                    selectedClip = null
                },
                onDelete = {
                    viewModel.deleteClip(trackId, clipId)
                    selectedClip = null
                },
                onFadeChange = { fadeInFrames, fadeOutFrames ->
                    viewModel.setClipFades(trackId, clipId, fadeInFrames, fadeOutFrames)
                },
                onGainChange = { gainDb ->
                    viewModel.setClipGain(trackId, clipId, gainDb)
                },
                onSelectTake = if (clip.takeGroupId != null) { { viewModel.selectTake(trackId, clipId) } } else null
            )
        }
    }

    pitchCorrectionTarget?.let { (trackId, clipId) ->
        val inProgress by viewModel.pitchCorrectionInProgress.collectAsState()
        PitchCorrectionDialog(
            inProgress = inProgress,
            initial = null,
            onDismiss = { pitchCorrectionTarget = null },
            onApply = { settings ->
                viewModel.correctClipPitch(trackId, clipId, settings)
                pitchCorrectionTarget = null
            }
        )
    }

    pitchEditorTarget?.let { (trackId, clipId) ->
        val editorTrack = currentProject.tracks.firstOrNull { it.id == trackId }
        val editorClip = editorTrack?.clips?.firstOrNull { it.id == clipId }
        var analysis by remember(pitchEditorTarget, editorClip?.fileName) { mutableStateOf<PitchCurveAnalyzer.Result?>(null) }
        LaunchedEffect(editorClip?.fileName) {
            analysis = null
            editorClip?.let { clip ->
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    analysis = PitchCurveAnalyzer.analyze(File(repository.audioDir(currentProject.id), clip.fileName))
                }
            }
        }
        AlertDialog(
            onDismissRequest = { pitchEditorTarget = null },
            title = { Text("Vocal Pitch Editor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        editorClip == null -> Text("Clip not found.")
                        analysis == null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Analyzing vocal pitch…") }
                        else -> PitchCurveEditor(analysis!!, Modifier.fillMaxWidth())
                    }
                    Text("The blue curve is the detected vocal pitch. Dots show the nearest musical target; drag a point to audition a manual target. Audio rendering of per-note edits will be connected in the next DSP stage.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { pitchEditorTarget = null }) { Text("Done") } }
        )
    }

    if (showAudioSettings) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.filter {
            it.isSource
        } ?: emptyList()
        var selectedDeviceId by remember { mutableIntStateOf(AudioEngine.getInputDeviceId()) }
        var bufferFrames by remember { mutableIntStateOf(AudioEngine.getInputBufferSizeFrames().takeIf { it > 0 } ?: 0) }
        AlertDialog(
            onDismissRequest = { showAudioSettings = false },
            title = { Text("Audio I/O") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Input device", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { selectedDeviceId = -1; AudioEngine.setInputDeviceId(-1) }) {
                        Text(if (selectedDeviceId < 0) "✓ Default microphone" else "Default microphone")
                    }
                    inputDevices.forEach { device ->
                        TextButton(onClick = { selectedDeviceId = device.id; AudioEngine.setInputDeviceId(device.id) }) {
                            Text(if (selectedDeviceId == device.id) "✓ ${device.productName}" else device.productName.toString())
                        }
                    }
                    Divider()
                    Text("Native input", style = MaterialTheme.typography.titleSmall)
                    Text("${AudioEngine.getInputSampleRate()} Hz • ${AudioEngine.getInputChannelCount().coerceAtLeast(1)} ch • burst ${AudioEngine.getInputFramesPerBurst()} frames")
                    Text("Buffer: ${bufferFrames.takeIf { it > 0 } ?: 0} frames")
                    if (AudioEngine.getInputFramesPerBurst() > 0) {
                        val burst = AudioEngine.getInputFramesPerBurst()
                        val min = burst
                        val max = burst * 8
                        Slider(
                            value = bufferFrames.coerceIn(min, max).toFloat(),
                            onValueChange = { bufferFrames = (it / burst).roundToInt().coerceIn(1, 8) * burst; AudioEngine.setInputBufferSizeFrames(bufferFrames) },
                            valueRange = min.toFloat()..max.toFloat(),
                            steps = 7,
                            enabled = recordingState.isRecording
                        )
                        Text(if (recordingState.isRecording) "Buffer changes apply to the active input stream." else "Start recording to expose the active input buffer.")
                    }
                    Text("Use headphones for direct monitoring to avoid acoustic feedback.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showAudioSettings = false }) { Text("Done") } }
        )
    }

    if (showMonitoringSettings) {
        PitchCorrectionDialog(
            inProgress = false,
            initial = monitoringSettings,
            title = "Live pitch monitor",
            description = "Applied live while you record, through headphones only — see the mic icon in the transport bar to turn it on/off.",
            onDismiss = { showMonitoringSettings = false },
            onApply = { settings ->
                viewModel.setMonitoringSettings(settings)
                viewModel.setAutoPitchCaptureMode(settings.captureMode)
                showMonitoringSettings = false
            }
        )
    }

    exportState?.let { state ->
        AlertDialog(
            onDismissRequest = { if (state.complete) viewModel.dismissExportResult() },
            title = { Text(if (state.complete) "Export finished" else "Exporting…") },
            text = {
                Column {
                    when {
                        state.error != null -> Text("Export failed: ${state.error}")
                        state.complete -> Column {
                            Text("Saved to ${state.outputFile.absolutePath}")
                            Spacer(Modifier.height(6.dp))
                            Text("WAV • 32-bit float • stereo • project sample rate", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(state.progress * 100).toInt()}%")
                        }
                    }
                }
            },
            confirmButton = {
                if (state.complete) {
                    TextButton(onClick = { viewModel.dismissExportResult() }) { Text("OK") }
                } else {
                    TextButton(onClick = { viewModel.cancelExport() }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun ClipActionsDialog(
    clip: AudioClip,
    sampleRate: Int,
    onDismiss: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onCopy: () -> Unit,
    onSlip: (deltaFrames: Long) -> Unit,
    onCrossfadePrevious: () -> Unit,
    onRippleDelete: () -> Unit,
    onCleanupSilence: () -> Unit,
    onDuplicate: () -> Unit,
    onCut: () -> Unit,
    onPitchCorrect: () -> Unit,
    onPitchEditor: () -> Unit,
    onDelete: () -> Unit,
    onFadeChange: (fadeInFrames: Long, fadeOutFrames: Long) -> Unit,
    onGainChange: (gainDb: Float) -> Unit,
    onSelectTake: (() -> Unit)? = null
) {
    val fadeStepFrames = (sampleRate * 0.1).toLong() // 100ms per tap
    var fadeIn by remember(clip.id) { mutableStateOf(clip.fadeInFrames) }
    var fadeOut by remember(clip.id) { mutableStateOf(clip.fadeOutFrames) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSplitAtPlayhead, modifier = Modifier.fillMaxWidth()) {
                    Text("Split at playhead")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy") }
                    OutlinedButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) { Text("Duplicate") }
                }
                OutlinedButton(onClick = onCut, modifier = Modifier.fillMaxWidth()) { Text("Cut") }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onSlip((sampleRate * 0.05f).toLong()) }, modifier = Modifier.weight(1f)) { Text("Slip +50ms") }
                    OutlinedButton(onClick = { onSlip(-(sampleRate * 0.05f).toLong()) }, modifier = Modifier.weight(1f)) { Text("Slip -50ms") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCrossfadePrevious, modifier = Modifier.weight(1f)) { Text("Crossfade") }
                    OutlinedButton(onClick = onRippleDelete, modifier = Modifier.weight(1f)) { Text("Ripple delete") }
                }
                OutlinedButton(onClick = onCleanupSilence, modifier = Modifier.fillMaxWidth()) { Text("Clean vocal silence") }

                Button(onClick = onPitchCorrect, modifier = Modifier.fillMaxWidth()) {
                    Text("Pitch correction…")
                }
                OutlinedButton(onClick = onPitchEditor, modifier = Modifier.fillMaxWidth()) {
                    Text("Visual pitch editor…")
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Fade in: ${fadeIn * 1000 / sampleRate} ms")
                    Row {
                        TextButton(onClick = {
                            fadeIn = (fadeIn - fadeStepFrames).coerceAtLeast(0)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("-") }
                        TextButton(onClick = {
                            fadeIn = (fadeIn + fadeStepFrames).coerceAtMost(clip.lengthFrames)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("+") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Fade out: ${fadeOut * 1000 / sampleRate} ms")
                    Row {
                        TextButton(onClick = {
                            fadeOut = (fadeOut - fadeStepFrames).coerceAtLeast(0)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("-") }
                        TextButton(onClick = {
                            fadeOut = (fadeOut + fadeStepFrames).coerceAtMost(clip.lengthFrames)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("+") }
                    }
                }

                clip.takeGroupId?.let {
                    Text("Take ${clip.takeNumber}", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = { onSelectTake?.invoke() },
                        enabled = onSelectTake != null && !clip.takeSelected,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (clip.takeSelected) "Selected take" else "Select this take") }
                }

                Text("Clip gain: ${"%.1f".format(clip.gainDb)} dB", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = clip.gainDb,
                    onValueChange = { onGainChange(it) },
                    valueRange = -24f..12f,
                    steps = 35
                )

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete clip")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PitchCorrectionDialog(
    inProgress: Boolean,
    initial: com.almus.studio.audio.PitchCorrectionSettings? = null,
    title: String = "AutoPitch Pro",
    description: String = "Real-time vocal pitch correction for a monophonic lead. Use headphones for monitoring.",
    onDismiss: () -> Unit,
    onApply: (com.almus.studio.audio.PitchCorrectionSettings) -> Unit
) {
    var settings by remember { mutableStateOf(initial ?: com.almus.studio.audio.PitchCorrectionSettings()) }
    var category by remember { mutableStateOf(AutoPitchCategory.ESSENTIALS) }

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(description, style = MaterialTheme.typography.bodySmall)

                // BandLab-inspired visual hierarchy: intensity dial + preset row,
                // but every control is wired to the actual native correction params.
                val dialTrackColor = MaterialTheme.colorScheme.surfaceVariant
                val dialProgressColor = MaterialTheme.colorScheme.primary
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val progress = settings.strength.coerceIn(0f, 1f)
                    Box(Modifier.size(188.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 9.dp.toPx()
                            val diameter = size.minDimension - stroke * 2
                            val topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke)
                            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                            drawArc(
                                color = dialTrackColor,
                                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                                topLeft = topLeft, size = arcSize,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = dialProgressColor,
                                startAngle = 135f, sweepAngle = 270f * progress, useCenter = false,
                                topLeft = topLeft, size = arcSize,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(settings.strength * 100).roundToInt()}%", style = MaterialTheme.typography.headlineMedium)
                            Text(if (settings.hardMode) "Heaviest" else "AutoPitch", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Text("Recording mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    AutoPitchCaptureMode.values().forEach { mode ->
                        FilterChip(
                            selected = settings.captureMode == mode,
                            onClick = { settings = settings.copy(captureMode = mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(AutoPitchCaptureMode.values().first { it == settings.captureMode }.description, style = MaterialTheme.typography.bodySmall)

                Text("Style", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    AutoPitchCategory.values().forEach { tab ->
                        FilterChip(selected = category == tab, onClick = { category = tab }, label = { Text(tab.label) })
                    }
                }

                val visiblePresets = AutoPitchPresetEngine.forCategory(category)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    visiblePresets.forEach { preset ->
                        FilterChip(
                            selected = settings.preset == preset,
                            onClick = { settings = AutoPitchPresetEngine.apply(preset, settings) },
                            label = { Text(preset.label) }
                        )
                    }
                }

                Text("Key", style = MaterialTheme.typography.labelLarge)
                LazyRowChips(
                    options = com.almus.studio.audio.RootNote.values().toList(),
                    label = { it.label }, selected = settings.root,
                    onSelect = { settings = settings.copy(root = it) }
                )

                Text("Scale", style = MaterialTheme.typography.labelLarge)
                LazyRowChips(
                    options = com.almus.studio.audio.MusicalScale.values().toList(),
                    label = { it.label }, selected = settings.scale,
                    onSelect = { settings = settings.copy(scale = it) }
                )

                Text("Harmony", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    com.almus.studio.audio.HarmonyMode.values().forEach { mode ->
                        FilterChip(
                            selected = settings.harmony == mode,
                            onClick = { settings = settings.copy(harmony = mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text("Harmony mix: ${(settings.harmonyMix * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = settings.harmonyMix, onValueChange = { settings = settings.copy(harmonyMix = it) }, valueRange = 0f..0.8f)
                Text("Harmony width: ${(settings.harmonyPan * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = settings.harmonyPan, onValueChange = { settings = settings.copy(harmonyPan = it) }, valueRange = 0f..1f)

                Text("Correction intensity: ${(settings.strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = settings.strength, onValueChange = { settings = settings.copy(strength = it) }, valueRange = 0f..1f)

                Text("Retune speed: ${settings.speedMs.roundToInt()} ms", style = MaterialTheme.typography.labelSmall)
                Slider(value = settings.speedMs, onValueChange = { settings = settings.copy(speedMs = it, hardMode = false) }, valueRange = 5f..200f)

                Text("Formant preservation: ${(settings.formantCompensation * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                Text("Keeps the vocal character more stable while pitch is shifted.", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = settings.formantCompensation,
                    onValueChange = { settings = settings.copy(formantCompensation = it) },
                    valueRange = 0f..1f
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Hard / instant correction")
                        Text("Fast note locking; more synthetic when pushed hard.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.hardMode, onCheckedChange = { settings = settings.copy(hardMode = it) })
                }

                if (inProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Processing…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(settings) }, enabled = !inProgress) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !inProgress) { Text("Cancel") } }
    )
}

@Composable
private fun <T> LazyRowChips(options: List<T>, label: (T) -> String, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

@Composable
private fun TrackTimelineLane(
    projectId: String,
    track: com.almus.studio.data.Track,
    pixelsPerFrame: Float,
    canPaste: Boolean,
    onImportRequested: () -> Unit,
    onClipTapped: (clipId: String) -> Unit,
    onPasteRequested: () -> Unit,
    onClipMoved: (clipId: String, newStartFrame: Long) -> Unit,
    onClipTrimStart: (clipId: String, deltaFrames: Long) -> Unit,
    onClipTrimEnd: (clipId: String, deltaFrames: Long) -> Unit,
    onAutomationPointMoved: (AutomationParameter, Long, Long, Float) -> Unit,
    onAutomationPointDeleted: (AutomationParameter, Long) -> Unit,
    selectedClipId: String?
) {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context) }
    val trackColor = remember(track.colorHex) {
        runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(track.colorHex)) }
            .getOrDefault(com.almus.studio.ui.theme.StudioWaveform)
    }

    Box(Modifier.fillMaxSize()) {
        track.clips.forEach { clip ->
            val file = remember(clip.id) { File(repository.audioDir(projectId), clip.fileName) }
            val peaks by produceState<WaveformAnalyzer.Peaks?>(initialValue = null, clip.id) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    WaveformAnalyzer.analyze(file, bucketCount = 900)
                }
            }

            // Live drag offsets in raw pixels, reset once the corresponding
            // gesture commits via the on*Commit callbacks (see moveClip/
            // trimClipStart/trimClipEnd in StudioViewModel -- each drag is
            // one undo step, not one per pixel of movement).
            var moveDragPx by remember(clip.id) { mutableFloatStateOf(0f) }
            var trimStartDragPx by remember(clip.id) { mutableFloatStateOf(0f) }
            var trimEndDragPx by remember(clip.id) { mutableFloatStateOf(0f) }

            val density = androidx.compose.ui.platform.LocalDensity.current
            val displayedLengthFrames = (clip.lengthFrames
                - (trimStartDragPx / pixelsPerFrame).toLong()
                + (trimEndDragPx / pixelsPerFrame).toLong()).coerceAtLeast(1L)
            val widthDp = with(density) { (displayedLengthFrames * pixelsPerFrame).coerceAtLeast(40f).toDp() }
            val offsetDp = with(density) { (clip.startFrame * pixelsPerFrame + moveDragPx + trimStartDragPx).toDp() }

            Box(Modifier.offset(x = offsetDp).width(widthDp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(clip.id) {
                            detectDragGestures(
                                onDrag = { change, dragAmount -> change.consume(); moveDragPx += dragAmount.x },
                                onDragEnd = {
                                    val deltaFrames = (moveDragPx / pixelsPerFrame).toLong()
                                    moveDragPx = 0f
                                    if (deltaFrames != 0L) onClipMoved(clip.id, clip.startFrame + deltaFrames)
                                }
                            )
                        }
                        .clickable { onClipTapped(clip.id) }
                ) {
                    ClipWaveform(
                        peaks = peaks, widthDp = widthDp, heightDp = 92.dp, color = trackColor,
                        selected = selectedClipId == clip.id,
                        fadeInProgress = if (clip.lengthFrames > 0) clip.fadeInFrames.toFloat() / clip.lengthFrames else 0f,
                        fadeOutProgress = if (clip.lengthFrames > 0) clip.fadeOutFrames.toFloat() / clip.lengthFrames else 0f
                    )
                }

                Text(
                    text = if (clip.gainDb >= 0f) "+${"%.1f".format(clip.gainDb)} dB" else "${"%.1f".format(clip.gainDb)} dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )

                // Trim handles: thin draggable strips at each edge.
                Box(
                    Modifier
                        .width(14.dp)
                        .fillMaxHeight()
                        .align(androidx.compose.ui.Alignment.CenterStart)
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f))
                        .pointerInput(clip.id) {
                            detectDragGestures(
                                onDrag = { change, dragAmount -> change.consume(); trimStartDragPx += dragAmount.x },
                                onDragEnd = {
                                    val deltaFrames = (trimStartDragPx / pixelsPerFrame).toLong()
                                    trimStartDragPx = 0f
                                    if (deltaFrames != 0L) onClipTrimStart(clip.id, deltaFrames)
                                }
                            )
                        }
                )
                Box(
                    Modifier
                        .width(14.dp)
                        .fillMaxHeight()
                        .align(androidx.compose.ui.Alignment.CenterEnd)
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f))
                        .pointerInput(clip.id) {
                            detectDragGestures(
                                onDrag = { change, dragAmount -> change.consume(); trimEndDragPx += dragAmount.x },
                                onDragEnd = {
                                    val deltaFrames = (trimEndDragPx / pixelsPerFrame).toLong()
                                    trimEndDragPx = 0f
                                    if (deltaFrames != 0L) onClipTrimEnd(clip.id, deltaFrames)
                                }
                            )
                        }
                )
            }
        }

        AutomationLaneEditor(
            track = track,
            pixelsPerFrame = pixelsPerFrame,
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .align(Alignment.BottomStart)
                .padding(horizontal = 4.dp),
            onPointMoved = onAutomationPointMoved,
            onPointDeleted = onAutomationPointDeleted
        )

        Row(
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (canPaste) {
                androidx.compose.material3.IconButton(onClick = onPasteRequested) {
                    androidx.compose.material3.Icon(Icons.Filled.ContentPaste, contentDescription = "Paste clip")
                }
            }
            androidx.compose.material3.IconButton(onClick = onImportRequested) {
                androidx.compose.material3.Icon(Icons.Filled.FileUpload, contentDescription = "Import audio")
            }
        }
    }
}


@Composable
private fun AutomationLaneEditor(
    track: com.almus.studio.data.Track,
    pixelsPerFrame: Float,
    modifier: Modifier = Modifier,
    onPointMoved: (AutomationParameter, Long, Long, Float) -> Unit,
    onPointDeleted: (AutomationParameter, Long) -> Unit
) {
    val parameters = listOf(
        AutomationParameter.VOLUME_DB,
        AutomationParameter.PAN,
        AutomationParameter.REVERB_SEND_DB,
        AutomationParameter.DELAY_SEND_DB
    )
    val laneHeight = 86.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowHeightDp = laneHeight / parameters.size.toFloat()
    val volumeColor = StudioAccent
    val panColor = MaterialTheme.colorScheme.primary
    val reverbColor = MaterialTheme.colorScheme.secondary
    val delayColor = StudioWarning

    Box(modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            parameters.forEachIndexed { index, parameter ->
                val points = track.automation[parameter.name].orEmpty().sortedBy { it.frame }
                if (points.isEmpty()) return@forEachIndexed
                val color = when (parameter) {
                    AutomationParameter.VOLUME_DB -> volumeColor
                    AutomationParameter.PAN -> panColor
                    AutomationParameter.REVERB_SEND_DB -> reverbColor
                    AutomationParameter.DELAY_SEND_DB -> delayColor
                }
                val baseY = (index + 0.5f) * (size.height / parameters.size)
                val amplitude = (size.height / parameters.size) * 0.34f
                val path = Path()
                points.forEachIndexed { pointIndex, point ->
                    val normalized = when (parameter) {
                        AutomationParameter.VOLUME_DB -> ((point.value + 60f) / 72f).coerceIn(0f, 1f)
                        AutomationParameter.PAN -> ((point.value + 1f) / 2f).coerceIn(0f, 1f)
                        AutomationParameter.REVERB_SEND_DB, AutomationParameter.DELAY_SEND_DB -> ((point.value + 60f) / 60f).coerceIn(0f, 1f)
                    }
                    val x = point.frame * pixelsPerFrame
                    val y = baseY + amplitude - normalized * amplitude * 2f
                    if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(color.copy(alpha = 0.95f), 5f, Offset(x, y))
                }
                drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 3f))
            }
        }

        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val row = size.height / parameters.size
            for (i in 1 until parameters.size) {
                drawLine(
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
                    start = Offset(0f, i * row),
                    end = Offset(size.width, i * row),
                    strokeWidth = 1f
                )
            }
        }

        Text(
            "AUTOMATION  •  snap: timeline grid  •  drag  •  long-press = delete",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
        )

        parameters.forEachIndexed { index, parameter ->
            val range = when (parameter) {
                AutomationParameter.VOLUME_DB -> -60f..12f
                AutomationParameter.PAN -> -1f..1f
                AutomationParameter.REVERB_SEND_DB, AutomationParameter.DELAY_SEND_DB -> -60f..0f
            }
            track.automation[parameter.name].orEmpty().forEach { point ->
                val normalized = ((point.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val x = point.frame * pixelsPerFrame
                val rowHeightPx = with(density) { rowHeightDp.toPx() }
                val y = (index + 0.5f) * rowHeightPx + (0.5f - normalized) * rowHeightPx * 0.68f
                Box(
                    Modifier
                        .size(24.dp)
                        .offset(x = with(density) { x.toDp() } - 12.dp, y = with(density) { y.toDp() } - 12.dp)
                        .pointerInput(parameter, point.frame, point.value) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val frameDelta = (dragAmount.x / pixelsPerFrame).toLong()
                                    val valueDelta = -(dragAmount.y / with(density) { rowHeightDp.toPx() * 0.68f }) * (range.endInclusive - range.start)
                                    val newFrame = (point.frame + frameDelta).coerceAtLeast(0L)
                                    val newValue = (point.value + valueDelta).coerceIn(range.start, range.endInclusive)
                                    if (newFrame != point.frame || newValue != point.value) {
                                        onPointMoved(parameter, point.frame, newFrame, newValue)
                                    }
                                }
                            )
                        }
                        .pointerInput(parameter, point.frame) {
                            detectTapGestures(onLongPress = { onPointDeleted(parameter, point.frame) })
                        }
                )
            }
        }
    }
}
