package com.almus.studio.viewmodel

import com.almus.studio.audio.MidiEditorTools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.studio.audio.AudioDecoder
import com.almus.studio.audio.AudioEngine
import com.almus.studio.audio.MidiPlaybackEngine
import com.almus.studio.audio.AutoPitchCaptureMode
import com.almus.studio.audio.MidiRecorder
import com.almus.studio.data.MidiAutomationEvent
import com.almus.studio.audio.EffectChainParams
import com.almus.studio.audio.EffectChainParams.Companion.toEffectSettings
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.audio.VocalCleanupAnalyzer
import com.almus.studio.audio.toMask
import com.almus.studio.data.AudioClip
import com.almus.studio.data.AutomationParameter
import com.almus.studio.data.AutomationPoint
import com.almus.studio.data.Project
import com.almus.studio.data.ProjectRepository
import com.almus.studio.data.RecordingRecoverySession
import com.almus.studio.data.Track
import com.almus.studio.data.TrackType
import com.almus.studio.data.MidiClip
import com.almus.studio.data.MidiNote
import com.almus.studio.data.DrumSoundPattern
import com.almus.studio.data.DrumKit
import com.almus.studio.data.DrumSample
import com.almus.studio.data.TrackMixSnapshot
import com.almus.studio.data.VocalMixSnapshot
import com.almus.studio.data.TrackColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import android.net.Uri
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong
import kotlin.random.Random

sealed interface TransportState { data object Stopped : TransportState; data object Playing : TransportState; data object Paused : TransportState }

data class RecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val armedTrackId: String? = null,
    val inputLevelDb: Float = -96f,
    val isClipping: Boolean = false,
    val inputGainDb: Float = 0f,
    val peakHistoryDb: FloatArray = FloatArray(128) { -96f },
    val startFrame: Long = 0L
)

/** Non-null only while an offline export is running; see exportProject()/cancelExport(). */
data class MidiTakeInfo(
    val takeId: String,
    val takeNumber: Int,
    val clipId: String,
    val selected: Boolean = false
)

data class MidiRecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val trackId: String? = null,
    val overdub: Boolean = false,
    val loopRecording: Boolean = false,
    val punchInTick: Long? = null,
    val punchOutTick: Long? = null,
    val countInBars: Int = 0,
    val quantizeSubdivision: Int = 1,
    val takeNumber: Int = 1,
    val loopLengthTicks: Long? = null,
    val takeInfos: List<MidiTakeInfo> = emptyList()
)

data class ExportState(
    val outputFile: File,
    val temporaryFile: File? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val complete: Boolean = false
)

/** Maps stable [Track.id]/[AudioClip.id] strings to native integer handles. */
private class HandleTable {
    val trackHandles = mutableMapOf<String, Int>()
    val clipHandles = mutableMapOf<String, Int>()
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val handles = HandleTable()
    private val midiPlayback = MidiPlaybackEngine(application)
    private val midiRecorder = MidiRecorder()
    private val _midiRecording = MutableStateFlow(MidiRecordingState())
    val midiRecording: StateFlow<MidiRecordingState> = _midiRecording.asStateFlow()
    private val sampleRate = 48000

    private val _recentProjects = MutableStateFlow<List<Project>>(emptyList())
    val recentProjects: StateFlow<List<Project>> = _recentProjects.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val _transportState = MutableStateFlow<TransportState>(TransportState.Stopped)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _playheadFrame = MutableStateFlow(0L)
    val playheadFrame: StateFlow<Long> = _playheadFrame.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _masterPeaksDb = MutableStateFlow(floatArrayOf(-96f, -96f))
    val masterPeaksDb: StateFlow<FloatArray> = _masterPeaksDb.asStateFlow()
    private val _masterLoudness = MutableStateFlow(floatArrayOf(-96f, -96f, -96f))
    val masterLoudness: StateFlow<FloatArray> = _masterLoudness.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState?>(null)
    val exportState: StateFlow<ExportState?> = _exportState.asStateFlow()

    /** One-shot user-facing error messages (failed import, failed recording, etc). */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun dismissError() { _errorMessage.value = null }

    init {
        midiPlayback.setLiveMidiEventListener { event -> handleRecordedMidiEvent(event) }
        AudioEngine.nativeInit(sampleRate, 256)
        val recovered = repository.scanAndRepairInterruptedRecordings()
        if (recovered.isNotEmpty()) {
            _errorMessage.value = "Recovered ${recovered.size} interrupted recording${if (recovered.size == 1) "" else "s"}."
        }
        refreshRecentProjects()
        startUiPollingLoop()
    }

    private fun startUiPollingLoop() {
        viewModelScope.launch {
            while (coroutineContext.isActive) {
                _playheadFrame.value = AudioEngine.getPlayheadFrame()
                if (_transportState.value == TransportState.Playing) midiPlayback.update(_playheadFrame.value)
                _masterPeaksDb.value = AudioEngine.getMasterPeaksDb()
                _masterLoudness.value = AudioEngine.getMasterLoudness()
                if (_recordingState.value.isRecording || _monitoringEnabled.value) {
                    _recordingState.value = _recordingState.value.copy(
                        inputLevelDb = AudioEngine.getInputLevelDb(),
                        isClipping = AudioEngine.isInputClipping(),
                        peakHistoryDb = AudioEngine.getRecordingPeakHistory(128)
                    )
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    fun refreshRecentProjects() {
        _recentProjects.value = repository.listProjects()
    }

    fun createProject(name: String, bpm: Int) {
        val project = repository.createProject(name.ifBlank { "Untitled Project" }, bpm)
        openProject(project.id)
        refreshRecentProjects()
    }

    fun openProject(projectId: String) {
        val project = repository.load(projectId) ?: return
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
        attachProjectToEngine(project)
    }

    /** Tears down any currently-attached native track state and rebuilds it
     * to exactly match [project]. Used both for opening a project and for
     * undo/redo, where reverting to a snapshot is simplest done as a full
     * resync rather than trying to compute and reverse individual deltas. */
    private fun attachProjectToEngine(project: Project) {
        handles.trackHandles.values.forEach { AudioEngine.removeTrack(it) }
        handles.trackHandles.clear()
        handles.clipHandles.clear()
        _currentProject.value = project
        AudioEngine.setTempo(project.bpm.toFloat(), project.timeSignatureNumerator)
        _masterVolumeDb.value = project.masterVolumeDb
        _reverbReturnVolumeDb.value = project.reverbReturnVolumeDb
        _delayReturnVolumeDb.value = project.delayReturnVolumeDb
        _reverbReturnMuted.value = project.reverbReturnMuted
        _delayReturnMuted.value = project.delayReturnMuted
        _reverbReturnSolo.value = project.reverbReturnSolo
        _delayReturnSolo.value = project.delayReturnSolo
        AudioEngine.setMasterVolumeDb(project.masterVolumeDb)
        AudioEngine.setMasterLimiterEnabled(project.masterLimiterEnabled)
        AudioEngine.setMasterLimiterCeilingDb(project.masterLimiterCeilingDb)
        AudioEngine.setMasterLimiterReleaseMs(project.masterLimiterReleaseMs)
        AudioEngine.setReverbReturnVolumeDb(project.reverbReturnVolumeDb)
        AudioEngine.setDelayReturnVolumeDb(project.delayReturnVolumeDb)
        AudioEngine.setReverbReturnMuted(project.reverbReturnMuted)
        AudioEngine.setDelayReturnMuted(project.delayReturnMuted)
        AudioEngine.setReverbReturnSolo(project.reverbReturnSolo)
        AudioEngine.setDelayReturnSolo(project.delayReturnSolo)
        project.masterAutomation.forEach { AudioEngine.setMasterAutomationPoint(it.frame, it.value) }
        project.reverbReturnAutomation.forEach { AudioEngine.setReverbReturnAutomationPoint(it.frame, it.value) }
        project.delayReturnAutomation.forEach { AudioEngine.setDelayReturnAutomationPoint(it.frame, it.value) }
        project.tracks.forEach { track ->
            val handle = AudioEngine.addTrack(track.id)
            handles.trackHandles[track.id] = handle
            AudioEngine.setTrackVolumeDb(handle, track.volumeDb)
            AudioEngine.setTrackPan(handle, track.pan)
            AudioEngine.setTrackMuted(handle, track.muted)
            AudioEngine.setTrackSolo(handle, track.solo)
            AudioEngine.setTrackReverbSendDb(handle, track.reverbSendDb)
            AudioEngine.setTrackDelaySendDb(handle, track.delaySendDb)
            track.automation.forEach { (parameterName, points) ->
                val parameter = runCatching { AutomationParameter.valueOf(parameterName) }.getOrNull() ?: return@forEach
                val p = automationIndex(parameter)
                points.forEach { AudioEngine.setTrackAutomationPoint(handle, p, it.frame, it.value) }
            }
            EffectChainParams.fromEffectSettings(track.effects).pushTo(handle)
            track.clips.forEach { clip -> scheduleClipOnEngine(track.id, clip, project) }
        }
    }

    private fun scheduleClipOnEngine(trackId: String, clip: AudioClip, project: Project) {
        val trackHandle = handles.trackHandles[trackId] ?: return
        val file = File(repository.audioDir(project.id), clip.fileName)
        if (!file.exists()) return
        val clipHandle = AudioEngine.scheduleClip(
            trackHandle, file.absolutePath, clip.startFrame, clip.sourceOffsetFrames,
            clip.lengthFrames, clip.gainDb, clip.looping, clip.fadeInFrames, clip.fadeOutFrames
        )
        if (clipHandle >= 0) handles.clipHandles[clip.id] = clipHandle
    }

    fun closeProject() {
        midiPlayback.stop()
        handles.trackHandles.values.forEach { AudioEngine.removeTrack(it) }
        handles.trackHandles.clear()
        handles.clipHandles.clear()
        clipboard = null
        _clipboardAvailable.value = false
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
        if (_metronomeEnabled.value) { _metronomeEnabled.value = false; AudioEngine.setMetronomeEnabled(false) }
        if (_monitoringEnabled.value) { _monitoringEnabled.value = false; AudioEngine.setMonitoringEnabled(false) }
        setLoopRegion(null)
        setSelectionRange(null)
        _currentProject.value = null
        _transportState.value = TransportState.Stopped
    }

    // --- Undo / redo (Phase 3) --------------------------------------------------
    // Whole-project-snapshot based rather than per-field command objects: a
    // DAW's "one edit" already spans a model change (Project) plus native
    // engine state (scheduled clips, track params) that has to move together,
    // so reverting to a full prior snapshot and re-attaching it to the engine
    // (see attachProjectToEngine) is simpler and more robust than hand-writing
    // an inverse for every operation. Depth-capped so it can't grow forever.
    // Continuous drags (volume/pan sliders) intentionally do NOT push a
    // snapshot on every intermediate value -- see previewTrackVolume/Pan --
    // only the final committed value does, or one slider drag would eat the
    // whole undo history in a fraction of a second.

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndoSnapshot(project: Project) {
        undoStack.addLast(project)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    fun undo() {
        val current = _currentProject.value ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        attachProjectToEngine(previous)
        persist()
    }

    fun redo() {
        val current = _currentProject.value ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        attachProjectToEngine(next)
        persist()
    }

    // --- Transport ----------------------------------------------------------
    // Play/record are disabled while an export is running -- the offline
    // export worker and the live playback thread would otherwise process the
    // same per-track effect state concurrently (see audio_engine.h's note on
    // why the mixing scratch buffer is thread_local: the effect chain state
    // has the same hazard and isn't given the same protection, so the UI is
    // what keeps these two mutually exclusive).

    fun play() {
        if (_exportState.value != null) return
        AudioEngine.play(); startMidiPlayback(); _transportState.value = TransportState.Playing
    }
    fun pause() { AudioEngine.pause(); midiPlayback.stop(); _transportState.value = TransportState.Paused }
    fun stop() { AudioEngine.stop(); midiPlayback.stop(); _transportState.value = TransportState.Stopped }
    fun seekTo(frame: Long) { AudioEngine.seekToFrame(frame); midiPlayback.stop() }

    // --- Metronome (Phase 4) -----------------------------------------------------

    private val _metronomeEnabled = MutableStateFlow(false)
    val metronomeEnabled: StateFlow<Boolean> = _metronomeEnabled.asStateFlow()

    fun toggleMetronome() {
        val newValue = !_metronomeEnabled.value
        _metronomeEnabled.value = newValue
        AudioEngine.setMetronomeEnabled(newValue)
    }

    // --- Live pitch-corrected monitoring (Phase 4) --------------------------------
    // See AudioEngine.kt / RealtimePitchMonitor for the honest scope: headphones
    // only, ~20ms+ latency, monitoring-only (never baked into recordings).

    private val _monitoringEnabled = MutableStateFlow(false)
    val monitoringEnabled: StateFlow<Boolean> = _monitoringEnabled.asStateFlow()

    private val _monitoringSettings = MutableStateFlow(com.almus.studio.audio.PitchCorrectionSettings())
    val monitoringSettings: StateFlow<com.almus.studio.audio.PitchCorrectionSettings> = _monitoringSettings.asStateFlow()

    private val _autoPitchCaptureMode = MutableStateFlow(AutoPitchCaptureMode.DRY_RECORD)
    val autoPitchCaptureMode: StateFlow<AutoPitchCaptureMode> = _autoPitchCaptureMode.asStateFlow()

    fun setAutoPitchCaptureMode(mode: AutoPitchCaptureMode) { _autoPitchCaptureMode.value = mode }

    fun setMonitoringSettings(settings: com.almus.studio.audio.PitchCorrectionSettings) {
        _monitoringSettings.value = settings
        AudioEngine.setMonitorPitchParams(settings.root.pitchClass, settings.scale.toMask(), settings.strength, settings.speedMs, settings.hardMode, settings.harmony.ordinal, settings.harmonyMix, settings.harmonyPan, settings.formantCompensation)
    }

    fun setInputGain(db: Float) {
        val clamped = db.coerceIn(-24f, 24f)
        AudioEngine.setInputGainDb(clamped)
        _recordingState.value = _recordingState.value.copy(inputGainDb = clamped)
    }

    fun toggleMonitoring() {
        val newValue = !_monitoringEnabled.value
        if (newValue) {
            setMonitoringSettings(_monitoringSettings.value)
            // Phase 6.8: monitoring no longer depends on an active recording.
            // The native input stream is opened without a WAV writer.
            if (!AudioEngine.startInputMonitoring()) {
                _errorMessage.value = AudioEngine.getLastRecordingError().ifBlank { "Microphone monitoring could not be started." }
                return
            }
        } else {
            AudioEngine.setMonitoringEnabled(false)
            AudioEngine.stopInputMonitoring()
        }
        _monitoringEnabled.value = newValue
        AudioEngine.setMonitoringEnabled(newValue)
    }

    // --- A-B loop (Phase 4) --------------------------------------------------------

    private val _loopRegion = MutableStateFlow<LongRange?>(null)
    val loopRegion: StateFlow<LongRange?> = _loopRegion.asStateFlow()

    fun setLoopRegion(range: LongRange?) {
        _loopRegion.value = range
        if (range != null) {
            AudioEngine.setLoopRegion(range.first, range.last, true)
        } else {
            AudioEngine.setLoopRegion(0, 0, false)
        }
    }

    // --- Timeline selection (Phase 4) -----------------------------------------------
    // Purely a UI concept for now -- used to set the A-B loop range. Not persisted.

    private val _selectionRange = MutableStateFlow<LongRange?>(null)
    val selectionRange: StateFlow<LongRange?> = _selectionRange.asStateFlow()

    fun setSelectionRange(range: LongRange?) { _selectionRange.value = range }

    fun setLoopToSelection() {
        _selectionRange.value?.let { setLoopRegion(it) }
    }

    // --- Pro timeline editing (Phase 5) --------------------------------------------
    private val _snapEnabled = MutableStateFlow(true)
    val snapEnabled: StateFlow<Boolean> = _snapEnabled.asStateFlow()

    fun toggleSnap() { _snapEnabled.value = !_snapEnabled.value }

    private fun snapFrame(frame: Long): Long {
        if (!_snapEnabled.value) return frame.coerceAtLeast(0)
        val project = _currentProject.value ?: return frame.coerceAtLeast(0)
        // Phase 5.1 uses quarter-beat grid by default: beat/bar snapping remains
        // exact at the larger boundaries while edits feel much more precise.
        return com.almus.studio.audio.TimeConversion.snapFrames(
            frame, subdivision = 4, bpm = project.bpm, sampleRate = project.sampleRate
        )
    }

    // --- Drag editing on the waveform (Phase 4) -------------------------------------

    /** Moves a clip to a new timeline position (drag-to-move). Cheap: uses the
     * native moveClip command directly rather than remove+reschedule, since
     * only the position changes. */
    fun moveClip(trackId: String, clipId: String, newStartFrame: Long) {
        val clamped = snapFrame(newStartFrame)
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(startFrame = clamped) else it }) }
        handles.clipHandles[clipId]?.let { AudioEngine.moveClip(it, clamped) }
    }

    /** Drags the clip's left edge: shortens/lengthens from the start, keeping
     * the clip's end position fixed on the timeline. */
    fun trimClipStart(trackId: String, clipId: String, deltaFrames: Long) {
        val project = _currentProject.value ?: return
        val clip = project.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        val newLength = (clip.lengthFrames - deltaFrames).coerceAtLeast(1)
        val actualDelta = clip.lengthFrames - newLength
        val newSourceOffset = (clip.sourceOffsetFrames + actualDelta).coerceAtLeast(0)
        val newStart = (clip.startFrame + actualDelta).coerceAtLeast(0)
        val updated = clip.copy(sourceOffsetFrames = newSourceOffset, startFrame = newStart, lengthFrames = newLength)
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
        scheduleClipOnEngine(trackId, updated, project)
    }

    /** Drags the clip's right edge: shortens/lengthens from the end. */
    fun trimClipEnd(trackId: String, clipId: String, deltaFrames: Long) {
        val project = _currentProject.value ?: return
        val clip = project.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        val newLength = (clip.lengthFrames + deltaFrames).coerceAtLeast(1)
        val updated = clip.copy(lengthFrames = newLength)
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
        scheduleClipOnEngine(trackId, updated, project)
    }

    // --- Automation (Phase 6.12) --------------------------------------------
    private fun automationIndex(parameter: AutomationParameter): Int = when (parameter) {
        AutomationParameter.VOLUME_DB -> 0
        AutomationParameter.PAN -> 1
        AutomationParameter.REVERB_SEND_DB -> 2
        AutomationParameter.DELAY_SEND_DB -> 3
    }

    fun setTrackAutomationPoint(trackId: String, parameter: AutomationParameter, frame: Long, value: Float) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clamped = when (parameter) {
            AutomationParameter.VOLUME_DB -> value.coerceIn(-60f, 12f)
            AutomationParameter.PAN -> value.coerceIn(-1f, 1f)
            AutomationParameter.REVERB_SEND_DB, AutomationParameter.DELAY_SEND_DB -> value.coerceIn(-60f, 0f)
        }
        pushUndoSnapshot(project)
        val key = parameter.name
        val old = track.automation[key].orEmpty()
        val points = (old.filterNot { it.frame == frame } + AutomationPoint(frame.coerceAtLeast(0), clamped)).sortedBy { it.frame }
        val updated = project.copy(tracks = project.tracks.map { if (it.id == trackId) it.copy(automation = it.automation + (key to points)) else it })
        _currentProject.value = updated
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackAutomationPoint(it, automationIndex(parameter), frame, clamped) }
        persist()
    }

    /** Move an automation point as one atomic edit. The destination frame uses the same
     * quarter-beat snap grid as clip editing when Snap is enabled, preventing
     * accidental micro-offsets while still allowing free positioning when Snap is off.
     */
    fun moveTrackAutomationPoint(trackId: String, parameter: AutomationParameter, oldFrame: Long, requestedFrame: Long, value: Float) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val key = parameter.name
        val old = track.automation[key].orEmpty()
        if (old.none { it.frame == oldFrame }) return
        val snappedFrame = snapFrame(requestedFrame)
        val clamped = when (parameter) {
            AutomationParameter.VOLUME_DB -> value.coerceIn(-60f, 12f)
            AutomationParameter.PAN -> value.coerceIn(-1f, 1f)
            AutomationParameter.REVERB_SEND_DB, AutomationParameter.DELAY_SEND_DB -> value.coerceIn(-60f, 0f)
        }
        if (snappedFrame == oldFrame && old.first { it.frame == oldFrame }.value == clamped) return
        pushUndoSnapshot(project)
        val moved = old.filterNot { it.frame == oldFrame || it.frame == snappedFrame } +
            AutomationPoint(snappedFrame, clamped)
        val points = moved.sortedBy { it.frame }
        _currentProject.value = project.copy(
            tracks = project.tracks.map {
                if (it.id == trackId) it.copy(automation = it.automation + (key to points)) else it
            }
        )
        handles.trackHandles[trackId]?.let { handle ->
            AudioEngine.clearTrackAutomation(handle, automationIndex(parameter))
            points.forEach { point ->
                AudioEngine.setTrackAutomationPoint(handle, automationIndex(parameter), point.frame, point.value)
            }
        }
        persist()
    }

    fun removeTrackAutomationPoint(trackId: String, parameter: AutomationParameter, frame: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val key = parameter.name
        val old = track.automation[key].orEmpty()
        if (old.none { it.frame == frame }) return
        pushUndoSnapshot(project)
        val remaining = old.filterNot { it.frame == frame }
        val updatedTracks = project.tracks.map {
            if (it.id != trackId) it
            else if (remaining.isEmpty()) it.copy(automation = it.automation - key)
            else it.copy(automation = it.automation + (key to remaining))
        }
        _currentProject.value = project.copy(tracks = updatedTracks)
        handles.trackHandles[trackId]?.let { AudioEngine.clearTrackAutomation(it, automationIndex(parameter)); remaining.forEach { point -> AudioEngine.setTrackAutomationPoint(it, automationIndex(parameter), point.frame, point.value) } }
        persist()
    }

    fun clearTrackAutomation(trackId: String, parameter: AutomationParameter) {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) it.copy(automation = it.automation - parameter.name) else it })
        handles.trackHandles[trackId]?.let { AudioEngine.clearTrackAutomation(it, automationIndex(parameter)) }
        persist()
    }

    fun setMasterAutomationPoint(frame: Long, value: Float) {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        val points = (project.masterAutomation.filterNot { it.frame == frame } + AutomationPoint(frame.coerceAtLeast(0), value.coerceIn(-60f, 6f))).sortedBy { it.frame }
        _currentProject.value = project.copy(masterAutomation = points)
        AudioEngine.setMasterAutomationPoint(frame, value.coerceIn(-60f, 6f)); persist()
    }

    fun clearMasterAutomation() {
        val project = _currentProject.value ?: return; pushUndoSnapshot(project)
        _currentProject.value = project.copy(masterAutomation = emptyList()); AudioEngine.clearMasterAutomation(); persist()
    }

    fun setReverbReturnAutomationPoint(frame: Long, value: Float) {
        val project = _currentProject.value ?: return; pushUndoSnapshot(project)
        val points = (project.reverbReturnAutomation.filterNot { it.frame == frame } + AutomationPoint(frame.coerceAtLeast(0), value.coerceIn(-60f, 6f))).sortedBy { it.frame }
        _currentProject.value = project.copy(reverbReturnAutomation = points); AudioEngine.setReverbReturnAutomationPoint(frame, value.coerceIn(-60f, 6f)); persist()
    }

    fun clearReverbReturnAutomation() {
        val project = _currentProject.value ?: return; pushUndoSnapshot(project)
        _currentProject.value = project.copy(reverbReturnAutomation = emptyList()); AudioEngine.clearReverbReturnAutomation(); persist()
    }

    fun setDelayReturnAutomationPoint(frame: Long, value: Float) {
        val project = _currentProject.value ?: return; pushUndoSnapshot(project)
        val points = (project.delayReturnAutomation.filterNot { it.frame == frame } + AutomationPoint(frame.coerceAtLeast(0), value.coerceIn(-60f, 6f))).sortedBy { it.frame }
        _currentProject.value = project.copy(delayReturnAutomation = points); AudioEngine.setDelayReturnAutomationPoint(frame, value.coerceIn(-60f, 6f)); persist()
    }

    fun clearDelayReturnAutomation() {
        val project = _currentProject.value ?: return; pushUndoSnapshot(project)
        _currentProject.value = project.copy(delayReturnAutomation = emptyList()); AudioEngine.clearDelayReturnAutomation(); persist()
    }

    // --- Master limiter / loudness (Phase 6.16) -------------------------------
    fun setMasterLimiterEnabled(enabled: Boolean) {
        val p = _currentProject.value ?: return
        _currentProject.value = p.copy(masterLimiterEnabled = enabled)
        AudioEngine.setMasterLimiterEnabled(enabled)
        persist()
    }

    fun setMasterLimiterCeilingDb(db: Float) {
        val value = db.coerceIn(-12f, -0.1f)
        val p = _currentProject.value ?: return
        _currentProject.value = p.copy(masterLimiterCeilingDb = value)
        AudioEngine.setMasterLimiterCeilingDb(value)
        persist()
    }

    fun setMasterLimiterReleaseMs(ms: Float) {
        val value = ms.coerceIn(5f, 1000f)
        val p = _currentProject.value ?: return
        _currentProject.value = p.copy(masterLimiterReleaseMs = value)
        AudioEngine.setMasterLimiterReleaseMs(value)
        persist()
    }

    // --- Vocal mix snapshots (Phase 6.15) -----------------------------------
    fun saveVocalMixSnapshot(name: String): Boolean {
        val project = _currentProject.value ?: return false
        val cleanName = name.trim().take(48)
        if (cleanName.isEmpty()) return false
        pushUndoSnapshot(project)
        val snapshot = VocalMixSnapshot(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAtEpochMs = System.currentTimeMillis(),
            masterVolumeDb = _masterVolumeDb.value,
            reverbReturnVolumeDb = _reverbReturnVolumeDb.value,
            delayReturnVolumeDb = _delayReturnVolumeDb.value,
            reverbReturnMuted = _reverbReturnMuted.value,
            reverbReturnSolo = _reverbReturnSolo.value,
            delayReturnMuted = _delayReturnMuted.value,
            delayReturnSolo = _delayReturnSolo.value,
            tracks = project.tracks.map { t ->
                TrackMixSnapshot(t.id, t.volumeDb, t.pan, t.muted, t.solo, t.reverbSendDb, t.delaySendDb)
            }
        )
        _currentProject.value = project.copy(vocalMixSnapshots = project.vocalMixSnapshots + snapshot)
        persist()
        return true
    }

    fun deleteVocalMixSnapshot(snapshotId: String) {
        val project = _currentProject.value ?: return
        if (project.vocalMixSnapshots.none { it.id == snapshotId }) return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(vocalMixSnapshots = project.vocalMixSnapshots.filterNot { it.id == snapshotId })
        persist()
    }

    fun applyVocalMixSnapshot(snapshotId: String) {
        val project = _currentProject.value ?: return
        val snapshot = project.vocalMixSnapshots.find { it.id == snapshotId } ?: return
        pushUndoSnapshot(project)
        val byId = snapshot.tracks.associateBy { it.trackId }
        val updatedTracks = project.tracks.map { t ->
            val m = byId[t.id] ?: return@map t
            t.copy(volumeDb = m.volumeDb, pan = m.pan, muted = m.muted, solo = m.solo, reverbSendDb = m.reverbSendDb, delaySendDb = m.delaySendDb)
        }
        _masterVolumeDb.value = snapshot.masterVolumeDb
        _reverbReturnVolumeDb.value = snapshot.reverbReturnVolumeDb
        _delayReturnVolumeDb.value = snapshot.delayReturnVolumeDb
        _reverbReturnMuted.value = snapshot.reverbReturnMuted
        _reverbReturnSolo.value = snapshot.reverbReturnSolo
        _delayReturnMuted.value = snapshot.delayReturnMuted
        _delayReturnSolo.value = snapshot.delayReturnSolo
        _currentProject.value = project.copy(
            tracks = updatedTracks,
            masterVolumeDb = snapshot.masterVolumeDb,
            reverbReturnVolumeDb = snapshot.reverbReturnVolumeDb,
            delayReturnVolumeDb = snapshot.delayReturnVolumeDb,
            reverbReturnMuted = snapshot.reverbReturnMuted,
            reverbReturnSolo = snapshot.reverbReturnSolo,
            delayReturnMuted = snapshot.delayReturnMuted,
            delayReturnSolo = snapshot.delayReturnSolo
        )
        AudioEngine.setMasterVolumeDb(snapshot.masterVolumeDb)
        AudioEngine.setReverbReturnVolumeDb(snapshot.reverbReturnVolumeDb)
        AudioEngine.setDelayReturnVolumeDb(snapshot.delayReturnVolumeDb)
        AudioEngine.setReverbReturnMuted(snapshot.reverbReturnMuted)
        AudioEngine.setReverbReturnSolo(snapshot.reverbReturnSolo)
        AudioEngine.setDelayReturnMuted(snapshot.delayReturnMuted)
        AudioEngine.setDelayReturnSolo(snapshot.delayReturnSolo)
        snapshot.tracks.forEach { m ->
            handles.trackHandles[m.trackId]?.let { h ->
                AudioEngine.setTrackVolumeDb(h, m.volumeDb)
                AudioEngine.setTrackPan(h, m.pan)
                AudioEngine.setTrackMuted(h, m.muted)
                AudioEngine.setTrackSolo(h, m.solo)
                AudioEngine.setTrackReverbSendDb(h, m.reverbSendDb)
                AudioEngine.setTrackDelaySendDb(h, m.delaySendDb)
            }
        }
        persist()
    }

    // --- Track editing --------------------------------------------------------

    fun setTrackVolume(trackId: String, volumeDb: Float) =
        updateTrack(trackId) { it.copy(volumeDb = volumeDb) }.also {
            handles.trackHandles[trackId]?.let { h -> AudioEngine.setTrackVolumeDb(h, volumeDb) }
        }

    fun setTrackPan(trackId: String, pan: Float) =
        updateTrack(trackId) { it.copy(pan = pan) }.also {
            handles.trackHandles[trackId]?.let { h -> AudioEngine.setTrackPan(h, pan) }
        }

    fun setTrackReverbSend(trackId: String, sendDb: Float) {
        val value = sendDb.coerceIn(-60f, 0f)
        updateTrack(trackId) { it.copy(reverbSendDb = value) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackReverbSendDb(it, value) }
    }

    fun setTrackDelaySend(trackId: String, sendDb: Float) {
        val value = sendDb.coerceIn(-60f, 0f)
        updateTrack(trackId) { it.copy(delaySendDb = value) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackDelaySendDb(it, value) }
    }

    private val _masterVolumeDb = MutableStateFlow(0f)
    val masterVolumeDb: StateFlow<Float> = _masterVolumeDb.asStateFlow()
    private val _reverbReturnVolumeDb = MutableStateFlow(0f)
    val reverbReturnVolumeDb: StateFlow<Float> = _reverbReturnVolumeDb.asStateFlow()
    private val _delayReturnVolumeDb = MutableStateFlow(0f)
    val delayReturnVolumeDb: StateFlow<Float> = _delayReturnVolumeDb.asStateFlow()
    private val _reverbReturnMuted = MutableStateFlow(false)
    val reverbReturnMuted: StateFlow<Boolean> = _reverbReturnMuted.asStateFlow()
    private val _delayReturnMuted = MutableStateFlow(false)
    val delayReturnMuted: StateFlow<Boolean> = _delayReturnMuted.asStateFlow()
    private val _reverbReturnSolo = MutableStateFlow(false)
    val reverbReturnSolo: StateFlow<Boolean> = _reverbReturnSolo.asStateFlow()
    private val _delayReturnSolo = MutableStateFlow(false)
    val delayReturnSolo: StateFlow<Boolean> = _delayReturnSolo.asStateFlow()

    fun previewMasterVolume(volumeDb: Float) {
        val value = volumeDb.coerceIn(-60f, 6f)
        AudioEngine.setMasterVolumeDb(value)
        _masterVolumeDb.value = value
    }

    fun setMasterVolume(volumeDb: Float) {
        val project = _currentProject.value ?: return
        val value = volumeDb.coerceIn(-60f, 6f)
        pushUndoSnapshot(project)
        _masterVolumeDb.value = value
        _currentProject.value = project.copy(masterVolumeDb = value)
        AudioEngine.setMasterVolumeDb(value)
        persist()
    }

    /** Live audio feedback while dragging, with no model/undo change -- see setTrackVolume for the commit. */
    fun previewTrackVolume(trackId: String, volumeDb: Float) {
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackVolumeDb(it, volumeDb) }
    }

    /** Live audio feedback while dragging, with no model/undo change -- see setTrackPan for the commit. */
    fun previewTrackPan(trackId: String, pan: Float) {
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackPan(it, pan) }
    }

    fun toggleMute(trackId: String) {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return
        val newValue = !track.muted
        updateTrack(trackId) { it.copy(muted = newValue) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackMuted(it, newValue) }
    }

    fun toggleSolo(trackId: String) {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return
        val newValue = !track.solo
        updateTrack(trackId) { it.copy(solo = newValue) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackSolo(it, newValue) }
    }

    fun toggleArm(trackId: String) {
        val current = _recordingState.value
        _recordingState.value = current.copy(
            armedTrackId = if (current.armedTrackId == trackId) null else trackId
        )
    }

    fun startMidiPlayback() {
        val project = _currentProject.value ?: return
        midiPlayback.start(project, project.sampleRate)
        midiPlayback.update(_playheadFrame.value)
    }

    fun stopMidiPlayback() {
        midiPlayback.stop()
    }

    fun setInternalMidiSynthEnabled(enabled: Boolean) {
        midiPlayback.setInternalSynthEnabled(enabled)
    }

    fun setMidiInstrument(trackId: String, waveform: String, attackMs: Int, decayMs: Int, sustain: Float, releaseMs: Int, octave: Int, gainDb: Float) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        pushUndoSnapshot(project)
        val updatedTrack = track.copy(
            midiWaveform = waveform.uppercase().let { if (it in setOf("SINE", "SQUARE", "SAW", "TRIANGLE")) it else "SINE" },
            midiAttackMs = attackMs.coerceIn(0, 5000),
            midiDecayMs = decayMs.coerceIn(0, 5000),
            midiSustain = sustain.coerceIn(0f, 1f),
            midiReleaseMs = releaseMs.coerceIn(1, 5000),
            midiOctave = octave.coerceIn(-2, 2),
            midiGainDb = gainDb.coerceIn(-48f, 6f)
        )
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updatedTrack else it })
        persist()
    }

    fun setSamplerInstrument(trackId: String, enabled: Boolean, mono: Boolean, legato: Boolean, glideMs: Int, polyphony: Int, sustainPedal: Boolean, gainDb: Float) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        pushUndoSnapshot(project)
        val updated = track.copy(
            samplerEnabled = enabled,
            samplerMono = mono,
            samplerLegato = legato,
            samplerGlideMs = glideMs.coerceIn(0, 2000),
            samplerPolyphony = polyphony.coerceIn(1, 64),
            samplerSustainPedal = sustainPedal,
            samplerGainDb = gainDb.coerceIn(-48f, 6f)
        )
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
    }

    fun setSamplerPerformance(
        trackId: String,
        pitchBend: Float,
        bendRangeSemitones: Int,
        modulation: Float,
        aftertouch: Float,
        velocityPitchCents: Int
    ) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        pushUndoSnapshot(project)
        val updated = track.copy(
            samplerPitchBend = pitchBend.coerceIn(-1f, 1f),
            samplerBendRangeSemitones = bendRangeSemitones.coerceIn(1, 24),
            samplerModulation = modulation.coerceIn(0f, 1f),
            samplerAftertouch = aftertouch.coerceIn(0f, 1f),
            samplerVelocityPitchCents = velocityPitchCents.coerceIn(-1200, 1200)
        )
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
    }

    fun previewSamplerNote(trackId: String, pitch: Int, velocity: Int = 100) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        midiPlayback.previewSamplerNote(track, pitch.coerceIn(0, 127), velocity.coerceIn(1, 127), project)
    }

    fun applyMidiPreset(trackId: String, preset: String) {
        when (preset.uppercase()) {
            "BRIGHT" -> setMidiInstrument(trackId, "SAW", 4, 70, 0.62f, 110, 0, -9f)
            "PAD" -> setMidiInstrument(trackId, "TRIANGLE", 260, 500, 0.82f, 900, 0, -12f)
            "PLUCK" -> setMidiInstrument(trackId, "SQUARE", 2, 90, 0.22f, 180, 0, -10f)
            else -> setMidiInstrument(trackId, "SINE", 8, 80, 0.78f, 140, 0, -6f)
        }
    }

    fun ensureDrumTrack(): String? {
        val project = _currentProject.value ?: return null
        project.tracks.firstOrNull { it.type == TrackType.MIDI && it.drumPattern != null }?.let { return it.id }
        pushUndoSnapshot(project)
        val track = Track(
            id = UUID.randomUUID().toString(),
            name = "Drums ${project.tracks.count { it.type == TrackType.MIDI && it.drumPattern != null } + 1}",
            colorHex = TrackColors.forIndex(project.tracks.size),
            type = TrackType.MIDI, midiChannel = 9, midiProgram = 0,
            drumPattern = defaultDrumPattern(),
            drumPatterns = listOf(defaultDrumPattern()),
            drumKit = DrumKit(),
            drumKits = listOf(DrumKit())
        )
        _currentProject.value = project.copy(tracks = project.tracks + track)
        persist()
        return track.id
    }

    private fun drumKitBank(track: Track): List<DrumKit> =
        if (track.drumKits.isEmpty()) listOf(track.drumKit.normalized()) else track.drumKits.map { it.normalized() }

    /** Saves the current kit as a named kit in the project's offline kit bank. */
    fun saveDrumKit(trackId: String, name: String = "Kit ${System.currentTimeMillis()}"): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val bank = drumKitBank(track)
        val clean = name.trim().ifBlank { "Kit ${bank.size + 1}" }
        val saved = track.drumKit.normalized().copy(id = UUID.randomUUID().toString(), name = clean)
        pushUndoSnapshot(project)
        val updated = track.copy(drumKit = saved, drumKits = bank + saved, activeDrumKit = bank.size)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
        return true
    }

    /** Selects and loads a kit from the persistent kit bank. */
    fun selectDrumKit(trackId: String, index: Int): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val bank = drumKitBank(track)
        if (index !in bank.indices) return false
        pushUndoSnapshot(project)
        val kit = bank[index]
        val updated = track.copy(drumKit = kit, drumKits = bank, activeDrumKit = index)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
        return true
    }

    /** Renames a saved kit without changing its sample mappings. */
    fun renameDrumKit(trackId: String, index: Int, name: String): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val bank = drumKitBank(track).toMutableList()
        if (index !in bank.indices) return false
        val clean = name.trim()
        if (clean.isBlank()) return false
        pushUndoSnapshot(project)
        val renamed = bank[index].copy(name = clean)
        bank[index] = renamed
        val updated = track.copy(drumKit = renamed, drumKits = bank, activeDrumKit = index)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
        return true
    }

    /** Duplicates a kit, keeping all sample mappings and non-destructive controls. */
    fun duplicateDrumKit(trackId: String, index: Int): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val bank = drumKitBank(track)
        if (index !in bank.indices) return false
        val copy = bank[index].copy(id = UUID.randomUUID().toString(), name = "${bank[index].name} Copy")
        pushUndoSnapshot(project)
        val updated = track.copy(drumKit = copy, drumKits = bank + copy, activeDrumKit = bank.size)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
        return true
    }

    /** Deletes a kit from the bank; at least one kit always remains. */
    fun deleteDrumKit(trackId: String, index: Int): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val bank = drumKitBank(track).toMutableList()
        if (bank.size <= 1 || index !in bank.indices) return false
        val nextIndex = (index - 1).coerceAtLeast(0).coerceAtMost(bank.lastIndex - 1)
        bank.removeAt(index)
        val kit = bank[nextIndex]
        pushUndoSnapshot(project)
        val updated = track.copy(drumKit = kit, drumKits = bank, activeDrumKit = nextIndex)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
        return true
    }

    /** Imports a local audio file into the project's offline drum sample library. */
    fun importDrumSample(trackId: String, pitch: Int, uri: Uri, displayName: String = "Sample"): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val dir = repository.samplesDir(project.id)
        val id = UUID.randomUUID().toString()
        val safe = displayName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sample.wav" }
        val target = File(dir, "${id}_${safe.substringBeforeLast('.', safe)}.wav")
        val ok = if (AudioDecoder.looksLikeWavFromUri(getApplication(), uri)) {
            runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }; true }.getOrDefault(false)
        } else AudioDecoder.decodeToWav(getApplication(), uri, target)
        if (!ok || !target.exists() || target.length() < 44) { target.delete(); return false }
        pushUndoSnapshot(project)
        val sample = DrumSample(id, pitch, displayName.ifBlank { drumName(pitch) }, target.name)
        val kit = track.drumKit.normalized()
        val updatedKit = kit.copy(samples = kit.samples.filterNot { it.pitch == pitch && it.velocityMin == 1 } + sample).normalized()
        val bank = drumKitBank(track).toMutableList()
        val active = track.activeDrumKit.coerceIn(0, bank.lastIndex)
        bank[active] = updatedKit
        val updatedTrack = track.copy(drumKit = updatedKit, drumKits = bank, activeDrumKit = active)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updatedTrack else it })
        persist()
        return true
    }

    /** Updates one non-destructive sampler mapping. Changes are undoable and persist offline. */
    fun updateDrumSample(trackId: String, sampleId: String, transform: (DrumSample) -> DrumSample) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val old = track.drumKit.samples.firstOrNull { it.id == sampleId } ?: return
        val updatedSample = transform(old).normalized()
        pushUndoSnapshot(project)
        val samples = track.drumKit.samples.map { if (it.id == sampleId) updatedSample else it }
        val updatedKit = track.drumKit.copy(samples = samples).normalized()
        val bank = drumKitBank(track).toMutableList()
        val active = track.activeDrumKit.coerceIn(0, bank.lastIndex)
        bank[active] = updatedKit
        val updatedTrack = track.copy(drumKit = updatedKit, drumKits = bank, activeDrumKit = active)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updatedTrack else it })
        persist()
    }

    /** Adds another velocity layer for the same MIDI pitch. */
    fun addDrumVelocityLayer(trackId: String, sourceSampleId: String): Boolean {
        val project = _currentProject.value ?: return false
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return false
        val source = track.drumKit.samples.firstOrNull { it.id == sourceSampleId } ?: return false
        val existing = track.drumKit.samples.filter { it.pitch == source.pitch }
        val nextMin = (existing.maxOfOrNull { it.velocityMax } ?: 0) + 1
        if (nextMin > 127) return false
        val layer = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} • Layer $nextMin",
            velocityMin = nextMin,
            velocityMax = 127
        ).normalized()
        pushUndoSnapshot(project)
        val updatedKit = track.drumKit.copy(samples = track.drumKit.samples + layer).normalized()
        val bank = drumKitBank(track).toMutableList()
        val active = track.activeDrumKit.coerceIn(0, bank.lastIndex)
        bank[active] = updatedKit
        val updatedTrack = track.copy(drumKit = updatedKit, drumKits = bank, activeDrumKit = active)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updatedTrack else it })
        persist()
        return true
    }

    fun removeDrumSamplesForPitch(trackId: String, pitch: Int) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId } ?: return
        pushUndoSnapshot(project)
        val updatedKit = track.drumKit.copy(samples = track.drumKit.samples.filterNot { it.pitch == pitch }).normalized()
        val bank = drumKitBank(track).toMutableList()
        val active = track.activeDrumKit.coerceIn(0, bank.lastIndex)
        bank[active] = updatedKit
        val updated = track.copy(drumKit = updatedKit, drumKits = bank, activeDrumKit = active)
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
    }

    fun setDrumStep(trackId: String, soundPitch: Int, step: Int, velocity: Int) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val bank = if (track.drumPatterns.isEmpty()) listOf(track.drumPattern ?: defaultDrumPattern()) else track.drumPatterns
        val active = track.activeDrumPattern.coerceIn(0, bank.lastIndex)
        val pattern = bank[active].normalized(); val total = pattern.steps * pattern.bars
        if (step !in 0 until total) return
        val base = pattern.sounds.firstOrNull { it.pitch == soundPitch } ?: DrumSoundPattern(soundPitch, drumName(soundPitch), List(total) { 0 })
        val v = base.velocities.toMutableList(); v[step] = velocity.coerceIn(0,127)
        val updated = base.copy(velocities=v)
        updateActiveDrumPattern(project, track, pattern.copy(sounds=(pattern.sounds.filterNot { it.pitch==soundPitch }+updated).sortedBy{it.pitch}))
    }

    fun setDrumPatternLength(trackId: String, steps: Int) {
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return
        val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns
        val active=track.activeDrumPattern.coerceIn(0,bank.lastIndex); val cur=bank[active].normalized(); val target=if(steps>=24)32 else 16
        updateActiveDrumPattern(project,track,cur.copy(steps=target,sounds=cur.sounds.map{it.normalized(target*cur.bars)}))
    }

    fun setDrumSwing(trackId:String, percent:Int){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return
        val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; val cur=bank[track.activeDrumPattern.coerceIn(0,bank.lastIndex)].normalized()
        updateActiveDrumPattern(project,track,cur.copy(swingPercent=percent.coerceIn(0,75)))
    }

    fun setDrumStepProbability(trackId:String,pitch:Int,step:Int,probability:Int){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return
        val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; val cur=bank[track.activeDrumPattern.coerceIn(0,bank.lastIndex)].normalized(); val total=cur.steps*cur.bars
        if(step !in 0 until total)return; val base=cur.sounds.firstOrNull{it.pitch==pitch}?:DrumSoundPattern(pitch,drumName(pitch),List(total){0}); val pr=base.probabilities.toMutableList(); pr[step]=probability.coerceIn(0,100)
        updateActiveDrumPattern(project,track,cur.copy(sounds=(cur.sounds.filterNot{it.pitch==pitch}+base.copy(probabilities=pr)).sortedBy{it.pitch}))
    }

    fun toggleDrumAccent(trackId:String,pitch:Int,step:Int){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; val cur=bank[track.activeDrumPattern.coerceIn(0,bank.lastIndex)].normalized(); val total=cur.steps*cur.bars
        if(step !in 0 until total)return; val base=cur.sounds.firstOrNull{it.pitch==pitch}?:DrumSoundPattern(pitch,drumName(pitch),List(total){0}); val ac=base.accents.toMutableList(); ac[step]=!ac[step]
        updateActiveDrumPattern(project,track,cur.copy(sounds=(cur.sounds.filterNot{it.pitch==pitch}+base.copy(accents=ac)).sortedBy{it.pitch}))
    }

    fun addDrumPattern(trackId:String){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; pushUndoSnapshot(project)
        val next=defaultDrumPattern().copy(id="pattern-${bank.size+1}",name="Pattern ${bank.size+1}"); val updated=track.copy(drumPatterns=bank+next,drumPattern=next,activeDrumPattern=bank.size)
        _currentProject.value=project.copy(tracks=project.tracks.map{if(it.id==trackId)updated else it}); persist()
    }

    fun copyDrumPattern(trackId:String){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; val src=bank[track.activeDrumPattern.coerceIn(0,bank.lastIndex)]; pushUndoSnapshot(project)
        val copy=src.copy(id="pattern-${bank.size+1}",name="Pattern ${bank.size+1}"); val updated=track.copy(drumPatterns=bank+copy,drumPattern=copy,activeDrumPattern=bank.size); _currentProject.value=project.copy(tracks=project.tracks.map{if(it.id==trackId)updated else it}); persist()
    }

    fun selectDrumPattern(trackId:String,index:Int){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; if(index !in bank.indices)return; pushUndoSnapshot(project); val pat=bank[index]; val updated=track.copy(drumPatterns=bank,drumPattern=pat,activeDrumPattern=index); _currentProject.value=project.copy(tracks=project.tracks.map{if(it.id==trackId)updated else it}); persist()
    }

    fun setDrumChain(trackId:String,chain:List<Int>){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; val active=track.activeDrumPattern.coerceIn(0,bank.lastIndex); updateActiveDrumPattern(project,track,bank[active].copy(chain=chain.filter{it in bank.indices}.take(16)))
    }

    fun randomizeDrumPattern(trackId:String){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; val bank=if(track.drumPatterns.isEmpty()) listOf(track.drumPattern?:defaultDrumPattern()) else track.drumPatterns; val cur=bank[track.activeDrumPattern.coerceIn(0,bank.lastIndex)].normalized(); val total=cur.steps*cur.bars
        val specs=listOf(36 to "Kick" to .22,38 to "Snare" to .12,39 to "Clap" to .07,42 to "Closed Hat" to .38,46 to "Open Hat" to .04,45 to "Tom" to .04,49 to "Crash" to .03,44 to "Perc" to .06)
        val sounds=specs.map{(pair,d)->val(pitch,name)=pair; DrumSoundPattern(pitch,name,List(total){if(Random.nextDouble()<d)Random.nextInt(75,121)else 0})}; updateActiveDrumPattern(project,track,cur.copy(sounds=sounds))
    }

    private fun updateActiveDrumPattern(project:Project,track:Track,pattern:com.almus.studio.data.DrumPattern){
        pushUndoSnapshot(project); val bank=if(track.drumPatterns.isEmpty()) listOf(pattern) else track.drumPatterns.toMutableList().also{it[track.activeDrumPattern.coerceIn(0,it.lastIndex)]=pattern}; val active=track.activeDrumPattern.coerceIn(0,bank.lastIndex); val updated=track.copy(drumPattern=pattern,drumPatterns=bank,activeDrumPattern=active); _currentProject.value=project.copy(tracks=project.tracks.map{if(it.id==track.id)updated else it}); persist()
    }

    fun clearDrumPattern(trackId:String){
        val project=_currentProject.value?:return; val track=project.tracks.firstOrNull{it.id==trackId}?:return; updateActiveDrumPattern(project,track,defaultDrumPattern())
    }

    private fun defaultDrumPattern()=com.almus.studio.data.DrumPattern()

    private fun drumName(pitch:Int)=when(pitch){36->"Kick";38->"Snare";39->"Clap";42->"Closed Hat";46->"Open Hat";45->"Tom";49->"Crash";44->"Perc";else->"Drum $pitch"}

    fun connectMidiOutputDevice(deviceId: Int, portIndex: Int = 0, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        midiPlayback.connectOutputDevice(deviceId, portIndex, onResult)
    }

    fun connectMidiInputDevice(deviceId: Int, portIndex: Int = 0, trackId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        midiPlayback.connectInputDevice(deviceId, portIndex, trackId, onResult)
    }

    fun disconnectMidiInputDevice() = midiPlayback.disconnectInputDevice()

    fun setLiveMidiTrack(trackId: String?) = midiPlayback.setLiveTrack(trackId)

    fun panicMidi() = midiPlayback.panicLiveMidi()

    private fun frameToTick(frame: Long, project: Project): Long {
        val framesPerTick = project.sampleRate.toDouble() * 60.0 / project.bpm.toDouble() / com.almus.studio.audio.MidiFileCodec.PPQ.toDouble()
        return (frame / framesPerTick).roundToLong().coerceAtLeast(0)
    }

    private fun handleRecordedMidiEvent(event: MidiPlaybackEngine.LiveMidiEvent) {
        if (!_midiRecording.value.isRecording || _midiRecording.value.isPaused) return
        val project = _currentProject.value ?: return
        val tick = frameToTick(event.frame, project)
        when (event.type) {
            "90" -> if (event.b > 0) midiRecorder.noteOn(event.channel, event.a, event.b, tick) else midiRecorder.noteOff(event.channel, event.a, tick)
            "80" -> midiRecorder.noteOff(event.channel, event.a, tick)
            "b0" -> midiRecorder.controlChange(event.channel, event.a, event.b, tick)
            "e0" -> midiRecorder.pitchBend(event.channel, (event.b shl 7) + event.a, tick)
            "d0" -> midiRecorder.aftertouch(event.channel, event.a, tick)
        }
    }

    fun configureMidiRecording(overdub: Boolean = false, loopRecording: Boolean = false, countInBars: Int = 0, punchInTick: Long? = null, punchOutTick: Long? = null, quantizeSubdivision: Int = 1, loopLengthTicks: Long? = null) {
        _midiRecording.value = _midiRecording.value.copy(overdub = overdub, loopRecording = loopRecording, countInBars = countInBars, punchInTick = punchInTick, punchOutTick = punchOutTick, quantizeSubdivision = quantizeSubdivision.coerceIn(0, 16), loopLengthTicks = loopLengthTicks?.coerceAtLeast(1))
        midiRecorder.configure(MidiRecorder.CaptureSettings(countInBars = countInBars, overdub = overdub, loopRecording = loopRecording, punchInTick = punchInTick, punchOutTick = punchOutTick, quantizeSubdivision = quantizeSubdivision.coerceIn(0, 16)))
    }

    fun startMidiRecording(trackId: String) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        if (track.midiClips.isEmpty()) addMidiClip(trackId)
        val now = frameToTick(_playheadFrame.value, project)
        midiRecorder.start(now)
        _midiRecording.value = _midiRecording.value.copy(isRecording = true, isPaused = false, trackId = trackId)
    }

    fun pauseMidiRecording() {
        if (_midiRecording.value.isRecording) _midiRecording.value = _midiRecording.value.copy(isPaused = true)
    }

    fun resumeMidiRecording() {
        if (_midiRecording.value.isRecording) _midiRecording.value = _midiRecording.value.copy(isPaused = false)
    }

    fun stopMidiRecording() {
        val state = _midiRecording.value
        if (!state.isRecording) return
        val project = _currentProject.value ?: return
        val endTick = frameToTick(_playheadFrame.value, project)
        val result = midiRecorder.stop(endTick)
        val track = project.tracks.firstOrNull { it.id == state.trackId && it.type == TrackType.MIDI }
        if (track != null && result.notes.isNotEmpty()) {
            pushUndoSnapshot(project)
            val target = track.midiClips.lastOrNull()
            val clip = if (state.overdub && target != null) target.copy(notes = (target.notes + result.notes).sortedBy { it.startTick }, automation = (target.automation + result.automation).sortedBy { it.tick })
                else MidiClip(UUID.randomUUID().toString(), _playheadFrame.value, (result.durationTicks.toDouble() * project.sampleRate * 60.0 / project.bpm / com.almus.studio.audio.MidiFileCodec.PPQ).toLong().coerceAtLeast(1), result.notes, "Recorded MIDI", result.automation, UUID.randomUUID().toString())
            val takeGroup = if (state.loopRecording || !state.overdub) UUID.randomUUID().toString() else clip.takeId
            val loopTicks = state.loopLengthTicks
            val baseClip = clip.copy(takeId = takeGroup, takeNumber = state.takeNumber, takeSelected = true)
            val generatedTakes = if (state.loopRecording && loopTicks != null && loopTicks > 0) {
                val maxTake = ((result.durationTicks + loopTicks - 1) / loopTicks).coerceAtMost(64)
                (0 until maxTake.toInt()).map { i ->
                    val start = i * loopTicks
                    val end = start + loopTicks
                    val shiftedNotes = result.notes.filter { it.startTick < end && it.startTick + it.durationTicks > start }.map { n ->
                        n.copy(id = UUID.randomUUID().toString(), startTick = (n.startTick - start).coerceAtLeast(0))
                    }
                    val shiftedAuto = result.automation.filter { it.tick in start until end }.map { a -> a.copy(tick = (a.tick - start).coerceAtLeast(0)) }
                    baseClip.copy(id = UUID.randomUUID().toString(), name = "Take ${state.takeNumber + i}", startFrame = _playheadFrame.value + (i * loopTicks * project.sampleRate.toDouble() * 60.0 / project.bpm / com.almus.studio.audio.MidiFileCodec.PPQ).toLong(), lengthFrames = (loopTicks.toDouble() * project.sampleRate * 60.0 / project.bpm / com.almus.studio.audio.MidiFileCodec.PPQ).toLong().coerceAtLeast(1), notes = shiftedNotes, automation = shiftedAuto, takeNumber = state.takeNumber + i)
                }.filter { it.notes.isNotEmpty() }
            } else listOf(baseClip)
            val clips = if (state.overdub && target != null) track.midiClips.map { if (it.id == target.id) clip.copy(notes = (target.notes + result.notes).sortedBy { n -> n.startTick }, automation = (target.automation + result.automation).sortedBy { a -> a.tick }) else it } else track.midiClips + generatedTakes
            val selectedId = generatedTakes.lastOrNull()?.takeId
            _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == track.id) it.copy(midiClips = clips, midiTakeCompingEnabled = state.loopRecording, activeMidiTakeId = selectedId) else it })
            _midiRecording.value = _midiRecording.value.copy(takeNumber = state.takeNumber + generatedTakes.size, takeInfos = generatedTakes.mapIndexed { idx, c -> MidiTakeInfo(c.takeId ?: c.id, state.takeNumber + idx, c.id, idx == generatedTakes.lastIndex) })
            persist()
        }
        _midiRecording.value = MidiRecordingState()
    }

    fun selectMidiTake(trackId: String, takeId: String) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        if (track.midiClips.none { it.takeId == takeId }) return
        pushUndoSnapshot(project)
        val updated = track.copy(activeMidiTakeId = takeId, midiTakeCompingEnabled = true, midiClips = track.midiClips.map { it.copy(takeSelected = it.takeId == takeId) })
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
    }

    fun setMidiTakeComping(trackId: String, enabled: Boolean) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        pushUndoSnapshot(project)
        val selected = track.activeMidiTakeId ?: track.midiClips.firstOrNull { it.takeId != null }?.takeId
        val updated = track.copy(midiTakeCompingEnabled = enabled, activeMidiTakeId = if (enabled) selected else null, midiClips = track.midiClips.map { it.copy(takeSelected = if (enabled) it.takeId == selected else true) })
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) updated else it })
        persist()
    }

    fun addMidiTrack() {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        val newTrack = Track(
            id = UUID.randomUUID().toString(),
            name = "MIDI ${project.tracks.count { it.type == TrackType.MIDI } + 1}",
            colorHex = TrackColors.forIndex(project.tracks.size),
            type = TrackType.MIDI,
            midiChannel = 0,
            midiProgram = 0
        )
        _currentProject.value = project.copy(tracks = project.tracks + newTrack)
        persist()
    }

    fun addMidiClip(trackId: String, lengthTicks: Long = 1920L) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        pushUndoSnapshot(project)
        val framesPerBeat = project.sampleRate.toDouble() * 60.0 / project.bpm.toDouble()
        val lengthFrames = (lengthTicks.toDouble() / com.almus.studio.audio.MidiFileCodec.PPQ * framesPerBeat).toLong().coerceAtLeast(1)
        val clip = MidiClip(UUID.randomUUID().toString(), _playheadFrame.value, lengthFrames, emptyList(), "MIDI Clip ${track.midiClips.size + 1}")
        _currentProject.value = project.copy(tracks = project.tracks.map { if (it.id == trackId) it.copy(midiClips = it.midiClips + clip) else it })
        persist()
    }

    fun addMidiNote(trackId: String, clipId: String, pitch: Int, startTick: Long, durationTicks: Long = 480L, velocity: Int = 100) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val clip = track.midiClips.find { it.id == clipId } ?: return
        pushUndoSnapshot(project)
        val note = MidiNote(UUID.randomUUID().toString(), startTick.coerceAtLeast(0), durationTicks.coerceAtLeast(1), pitch.coerceIn(0,127), velocity.coerceIn(1,127), track.midiChannel).normalized()
        val updated = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(notes = (c.notes + note).sortedBy { it.startTick }) else c }) })
        _currentProject.value = updated
        persist()
    }

    fun deleteMidiNote(trackId: String, clipId: String, noteId: String) {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(notes = c.notes.filterNot { it.id == noteId }) else c }) })
        persist()
    }

    fun moveMidiNote(trackId: String, clipId: String, noteId: String, startTick: Long, pitch: Int) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        if (track.midiClips.none { it.id == clipId }) return
        pushUndoSnapshot(project)
        val updated = project.copy(tracks = project.tracks.map { t ->
            if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c ->
                if (c.id != clipId) c else c.copy(notes = c.notes.map { n ->
                    if (n.id != noteId) n else n.copy(startTick = startTick.coerceAtLeast(0), pitch = pitch.coerceIn(0, 127)).normalized()
                }.sortedBy { it.startTick })
            })
        })
        _currentProject.value = updated
        persist()
    }

    fun resizeMidiNote(trackId: String, clipId: String, noteId: String, durationTicks: Long) {
        val project = _currentProject.value ?: return
        if (project.tracks.none { it.id == trackId && it.midiClips.any { c -> c.id == clipId } }) return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id != clipId) c else c.copy(notes = c.notes.map { n -> if (n.id == noteId) n.copy(durationTicks = durationTicks.coerceAtLeast(1)) else n }) }) })
        persist()
    }

    fun setMidiNoteVelocity(trackId: String, clipId: String, noteId: String, velocity: Int) {
        val project = _currentProject.value ?: return
        if (project.tracks.none { it.id == trackId && it.midiClips.any { c -> c.id == clipId } }) return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id != clipId) c else c.copy(notes = c.notes.map { n -> if (n.id == noteId) n.copy(velocity = velocity.coerceIn(1, 127)) else n }) }) })
        persist()
    }

    fun transformMidiNotes(trackId: String, clipId: String, transform: (List<MidiNote>) -> List<MidiNote>) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        if (track.midiClips.none { it.id == clipId }) return
        pushUndoSnapshot(project)
        val updated = project.copy(tracks = project.tracks.map { t ->
            if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c ->
                if (c.id != clipId) c else c.copy(notes = transform(c.notes).map { it.normalized() }.sortedBy { it.startTick })
            })
        })
        _currentProject.value = updated
        persist()
    }

    fun midiQuantize(trackId: String, clipId: String, subdivision: Int, strength: Int) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.quantize(it, subdivision, strength) }

    fun midiSwing(trackId: String, clipId: String, subdivision: Int, percent: Int) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.swing(it, subdivision, percent) }

    fun midiHumanize(trackId: String, clipId: String, ticks: Int, velocity: Int) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.humanize(it, ticks, velocity) }

    fun midiVelocityScale(trackId: String, clipId: String, amount: Int) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.velocityScale(it, 100, amount) }

    fun midiLegato(trackId: String, clipId: String) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.legato(it) }

    fun midiTranspose(trackId: String, clipId: String, semitones: Int) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.transpose(it, semitones) }

    fun midiScaleQuantize(trackId: String, clipId: String, root: Int, scale: Int) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.scaleQuantize(it, root, scale) }

    fun midiCleanup(trackId: String, clipId: String) =
        transformMidiNotes(trackId, clipId) { MidiEditorTools.cleanup(it) }

    fun midiDuplicateNotes(trackId: String, clipId: String, noteIds: Set<String>) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val clip = track.midiClips.firstOrNull { it.id == clipId } ?: return
        val selected = clip.notes.filter { it.id in noteIds }
        if (selected.isEmpty()) return
        val span = (selected.maxOf { it.startTick + it.durationTicks } - selected.minOf { it.startTick }).coerceAtLeast(1)
        pushUndoSnapshot(project)
        val copies = MidiEditorTools.duplicate(selected, span)
        _currentProject.value = project.copy(tracks = project.tracks.map { t ->
            if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(notes = (c.notes + copies).sortedBy { it.startTick }) else c })
        })
        persist()
    }

    fun midiCopyNotes(trackId: String, clipId: String, noteIds: Set<String>): List<MidiNote> {
        val project = _currentProject.value ?: return emptyList()
        return project.tracks.firstOrNull { it.id == trackId }?.midiClips?.firstOrNull { it.id == clipId }?.notes?.filter { it.id in noteIds } ?: emptyList()
    }

    fun midiPasteNotes(trackId: String, clipId: String, notes: List<MidiNote>, atTick: Long) {
        if (notes.isEmpty()) return
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val clip = track.midiClips.firstOrNull { it.id == clipId } ?: return
        val base = notes.minOf { it.startTick }
        val copies = notes.map { it.copy(id = java.util.UUID.randomUUID().toString(), startTick = (atTick + it.startTick - base).coerceAtLeast(0)) }
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(notes = (c.notes + copies).sortedBy { it.startTick }) else c }) })
        persist()
    }

    fun midiAddAutomationPoint(trackId: String, clipId: String, event: com.almus.studio.data.MidiAutomationEvent) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val clip = track.midiClips.firstOrNull { it.id == clipId } ?: return
        val e = event.normalized()
        pushUndoSnapshot(project)
        val updated = clip.automation.filterNot { it.tick == e.tick && it.type == e.type && it.controller == e.controller && it.channel == e.channel } + e
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(automation = updated.sortedBy { it.tick }) else c }) })
        persist()
    }

    fun midiDeleteAutomationPoint(trackId: String, clipId: String, tick: Long, type: String, controller: Int, channel: Int) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val clip = track.midiClips.firstOrNull { it.id == clipId } ?: return
        val remaining = clip.automation.filterNot { it.tick == tick && it.type == type && it.controller == controller && it.channel == channel }
        if (remaining.size == clip.automation.size) return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(automation = remaining) else c }) })
        persist()
    }

    fun midiMoveAutomationPoint(trackId: String, clipId: String, old: com.almus.studio.data.MidiAutomationEvent, newTick: Long, newValue: Int) {
        val project = _currentProject.value ?: return
        val track = project.tracks.firstOrNull { it.id == trackId && it.type == TrackType.MIDI } ?: return
        val clip = track.midiClips.firstOrNull { it.id == clipId } ?: return
        val remaining = clip.automation.filterNot { it == old }
        val moved = old.copy(tick = newTick.coerceAtLeast(0), value = newValue).normalized()
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(automation = (remaining + moved).sortedBy { it.tick }) else c }) })
        persist()
    }

    fun midiSetAutomation(trackId: String, clipId: String, events: List<com.almus.studio.data.MidiAutomationEvent>) {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        _currentProject.value = project.copy(tracks = project.tracks.map { t -> if (t.id != trackId) t else t.copy(midiClips = t.midiClips.map { c -> if (c.id == clipId) c.copy(automation = events.map { it.normalized() }.sortedBy { it.tick }) else c }) })
        persist()
    }

    fun addTrack() {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        val newTrack = Track(
            id = UUID.randomUUID().toString(),
            name = "Track ${project.tracks.size + 1}",
            colorHex = TrackColors.forIndex(project.tracks.size)
        )
        val updated = project.copy(tracks = project.tracks + newTrack)
        _currentProject.value = updated
        val handle = AudioEngine.addTrack(newTrack.id)
        handles.trackHandles[newTrack.id] = handle
        persist()
    }

    private fun updateTrack(trackId: String, transform: (Track) -> Track) {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        val updated = project.copy(tracks = project.tracks.map { if (it.id == trackId) transform(it) else it })
        _currentProject.value = updated
        persist()
    }

    private fun persist() {
        _currentProject.value?.let { repository.save(it) }
    }

    override fun onCleared() {
        // If Android destroys the ViewModel while recording, leave the recovery
        // sidecar in place. On the next launch it will repair the WAV header and
        // restore the take instead of silently losing the recording.
        if (_recordingState.value.isRecording) {
            runCatching { AudioEngine.stopRecording() }
        }
        AudioEngine.nativeShutdown()
        super.onCleared()
    }

    // --- Recording ------------------------------------------------------------

    private var recordingStartedPlayback = false
    private var recordingStartJob: kotlinx.coroutines.Job? = null

    private val _countInBeats = MutableStateFlow(0)
    val countInBeats: StateFlow<Int> = _countInBeats.asStateFlow()

    private val _punchEnabled = MutableStateFlow(false)
    val punchEnabled: StateFlow<Boolean> = _punchEnabled.asStateFlow()

    private val _preRollBeats = MutableStateFlow(0)
    val preRollBeats: StateFlow<Int> = _preRollBeats.asStateFlow()

    fun togglePunchMode() { _punchEnabled.value = !_punchEnabled.value }
    fun cyclePreRoll() {
        _preRollBeats.value = when (_preRollBeats.value) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 0 }
    }

    /** 0, 1, 2, or 4 beat count-in. Count-in is monitoring-only and is never written to the take. */
    fun cycleCountIn() {
        _countInBeats.value = when (_countInBeats.value) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 0 }
    }

    fun startRecording() {
        val project = _currentProject.value ?: return
        val captureMode = _autoPitchCaptureMode.value
        if (captureMode == AutoPitchCaptureMode.MONITOR_ONLY) {
            if (!_monitoringEnabled.value) toggleMonitoring()
            return
        }
        val armedTrackId = _recordingState.value.armedTrackId ?: project.tracks.firstOrNull()?.id ?: return
        val trackHandle = handles.trackHandles[armedTrackId] ?: return
        if (recordingStartJob?.isActive == true || _recordingState.value.isRecording) return

        val punch = if (_punchEnabled.value) _selectionRange.value else null
        if (_punchEnabled.value && (punch == null || punch.last <= punch.first)) {
            _errorMessage.value = "Select a timeline range first to use Punch Recording."
            return
        }
        val outFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")
        val countIn = _countInBeats.value
        val preRoll = _preRollBeats.value
        val beatMs = (60000.0 / project.bpm.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
        val beatFrames = (project.sampleRate * 60.0 / project.bpm.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)

        recordingStartJob = viewModelScope.launch {
            val targetStart = punch?.first ?: AudioEngine.getPlayheadFrame().coerceAtLeast(0L)
            val preRollStart = (targetStart - preRoll * beatFrames).coerceAtLeast(0L)
            if (punch != null) AudioEngine.seekToFrame(preRollStart)

            val wasPlaying = _transportState.value == TransportState.Playing
            recordingStartedPlayback = !wasPlaying
            if (!wasPlaying) {
                AudioEngine.play()
                _transportState.value = TransportState.Playing
            }

            if (countIn > 0) {
                if (!_metronomeEnabled.value) AudioEngine.setMetronomeEnabled(true)
                repeat(countIn) { kotlinx.coroutines.delay(beatMs) }
            }

            if (punch != null) {
                while (coroutineContext.isActive && AudioEngine.getPlayheadFrame() < targetStart) {
                    kotlinx.coroutines.delay(8)
                }
            }

            val startFrame = if (punch != null) targetStart else AudioEngine.getPlayheadFrame().coerceAtLeast(0L)
            val started = AudioEngine.startRecording(trackHandle, outFile.absolutePath)
            if (started) {
                lastRecordedFile = outFile
                val recoveryGroup = if (punch != null) {
                    activeTakeGroupId ?: UUID.randomUUID().toString().also { activeTakeGroupId = it }
                } else null
                repository.writeRecoverySession(RecordingRecoverySession(
                    projectId = project.id,
                    trackId = armedTrackId,
                    clipFileName = outFile.name,
                    startFrame = startFrame,
                    createdAtEpochMs = System.currentTimeMillis(),
                    sampleRate = project.sampleRate,
                    channelCount = AudioEngine.getInputChannelCount().coerceAtLeast(1),
                    takeGroupId = recoveryGroup,
                    takeNumber = if (recoveryGroup != null) {
                        (project.tracks.find { it.id == armedTrackId }?.clips?.filter { it.takeGroupId == recoveryGroup }?.maxOfOrNull { it.takeNumber } ?: 0) + 1
                    } else 0
                ))
                _recordingState.value = _recordingState.value.copy(
                    isRecording = true,
                    isPaused = false,
                    inputGainDb = AudioEngine.getInputGainDb(),
                    startFrame = startFrame
                )

                if (punch != null) {
                    val durationFrames = (punch.last - punch.first).coerceAtLeast(1L)
                    viewModelScope.launch {
                        while (coroutineContext.isActive && _recordingState.value.isRecording) {
                            if (AudioEngine.getPlayheadFrame() >= startFrame + durationFrames) {
                                stopRecording()
                                break
                            }
                            kotlinx.coroutines.delay(10)
                        }
                    }
                }
            } else {
                if (recordingStartedPlayback) { AudioEngine.pause(); _transportState.value = TransportState.Paused }
                recordingStartedPlayback = false
                val reason = AudioEngine.getLastRecordingError()
                _errorMessage.value = "Couldn't start recording" + if (reason.isNotBlank()) ": $reason" else " on this device."
            }
        }
    }

    fun cancelPendingRecordingStart() { recordingStartJob?.cancel(); recordingStartJob = null; if (recordingStartedPlayback) { AudioEngine.pause(); _transportState.value = TransportState.Paused; recordingStartedPlayback = false } }

    fun pauseRecording() {
        AudioEngine.pauseRecording()
        _recordingState.value = _recordingState.value.copy(isPaused = true)
    }

    fun resumeRecording() {
        AudioEngine.resumeRecording()
        _recordingState.value = _recordingState.value.copy(isPaused = false)
    }

    fun stopRecording() {
        val framesWritten = AudioEngine.stopRecording()
        val project = _currentProject.value
        val armedTrackId = _recordingState.value.armedTrackId
        val startFrame = _recordingState.value.startFrame
        _recordingState.value = _recordingState.value.copy(isRecording = false, isPaused = false, inputLevelDb = -96f, isClipping = false)
        if (recordingStartedPlayback) {
            AudioEngine.pause()
            _transportState.value = TransportState.Paused
            recordingStartedPlayback = false
        }

        if (project != null && armedTrackId != null && framesWritten > 0) {
            val track = project.tracks.find { it.id == armedTrackId } ?: return
            lastRecordedFile?.let { file ->
                val printWet = _autoPitchCaptureMode.value == AutoPitchCaptureMode.PRINT_EFFECT
                if (printWet) {
                    val printed = File(repository.audioDir(project.id), "${UUID.randomUUID()}_autopitch.wav")
                    val settings = _monitoringSettings.value
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = AudioEngine.correctPitch(
                            file.absolutePath, printed.absolutePath, settings.root.pitchClass, settings.scale.toMask(),
                            settings.strength, settings.speedMs, settings.hardMode, settings.harmony.ordinal,
                            settings.harmonyMix, settings.harmonyPan, settings.formantCompensation
                        )
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (ok) {
                                runCatching { file.delete() }
                                addRecordedClipFromFile(project, armedTrackId, printed, startFrame, framesWritten)
                            } else {
                                addRecordedClipFromFile(project, armedTrackId, file, startFrame, framesWritten)
                                _errorMessage.value = "AutoPitch print failed; kept the dry recording."
                            }
                            repository.clearRecoverySession(project.id)
                        }
                    }
                    lastRecordedFile = null
                    return@let
                }
                val takeGroupId = if (_punchEnabled.value) {
                    activeTakeGroupId ?: UUID.randomUUID().toString().also { activeTakeGroupId = it }
                } else null
                val nextTakeNumber = if (takeGroupId != null) {
                    (track.clips.filter { it.takeGroupId == takeGroupId }.maxOfOrNull { it.takeNumber } ?: 0) + 1
                } else 0
                val clip = AudioClip(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    startFrame = startFrame,
                    sourceOffsetFrames = 0L,
                    lengthFrames = framesWritten,
                    takeGroupId = takeGroupId,
                    takeNumber = nextTakeNumber,
                    takeSelected = true
                )
                if (takeGroupId != null) {
                    track.clips.filter { it.takeGroupId == takeGroupId }.forEach { oldTake ->
                        handles.clipHandles.remove(oldTake.id)?.let { AudioEngine.removeClip(it) }
                    }
                }
                updateTrack(armedTrackId) { t ->
                    t.copy(clips = t.clips.map { old ->
                        if (takeGroupId != null && old.takeGroupId == takeGroupId) old.copy(takeSelected = false) else old
                    } + clip)
                }
                val updatedProject = _currentProject.value ?: project
                scheduleClipOnEngine(armedTrackId, clip, updatedProject)
                repository.clearRecoverySession(project.id)
                lastRecordedFile = null
            }
        }
    }

    private fun addRecordedClipFromFile(project: Project, trackId: String, file: File, startFrame: Long, framesWritten: Long) {
        val track = project.tracks.find { it.id == trackId } ?: return
        val takeGroupId = if (_punchEnabled.value) activeTakeGroupId ?: UUID.randomUUID().toString().also { activeTakeGroupId = it } else null
        val nextTakeNumber = if (takeGroupId != null) (track.clips.filter { it.takeGroupId == takeGroupId }.maxOfOrNull { it.takeNumber } ?: 0) + 1 else 0
        val clip = AudioClip(
            id = UUID.randomUUID().toString(), fileName = file.name, startFrame = startFrame,
            sourceOffsetFrames = 0L, lengthFrames = framesWritten, takeGroupId = takeGroupId,
            takeNumber = nextTakeNumber, takeSelected = true
        )
        if (takeGroupId != null) track.clips.filter { it.takeGroupId == takeGroupId }.forEach { old -> handles.clipHandles.remove(old.id)?.let { AudioEngine.removeClip(it) } }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { old -> if (takeGroupId != null && old.takeGroupId == takeGroupId) old.copy(takeSelected = false) else old } + clip) }
        scheduleClipOnEngine(trackId, clip, _currentProject.value ?: project)
    }

    fun selectTake(trackId: String, clipId: String) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val target = track.clips.find { it.id == clipId } ?: return
        val group = target.takeGroupId ?: return
        val selected = track.clips.filter { it.takeGroupId == group }
        selected.forEach { clip ->
            if (clip.id != clipId) handles.clipHandles.remove(clip.id)?.let { AudioEngine.removeClip(it) }
        }
        updateTrack(trackId) { t ->
            t.copy(clips = t.clips.map { clip ->
                if (clip.takeGroupId == group) clip.copy(takeSelected = clip.id == clipId) else clip
            })
        }
        val updated = _currentProject.value ?: return
        scheduleClipOnEngine(trackId, target.copy(takeSelected = true), updated)
    }

    private var lastRecordedFile: File? = null
    private var activeTakeGroupId: String? = null

    // --- Import -----------------------------------------------------------

    /** Copies an externally-picked audio file into the project and schedules it on [trackId]. */
    fun importAudioFile(trackId: String, sourceUri: android.net.Uri, displayName: String) {
        val project = _currentProject.value ?: return
        val context = getApplication<Application>()
        val destFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Fast path: copy as-is and check whether it's already a real WAV
            // (common if the person picked a file they recorded elsewhere).
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            val isRealWav = destFile.exists() && AudioDecoder.looksLikeWav(destFile)
            if (!isRealWav) {
                // Most audio on a phone is MP3/M4A/OGG, not WAV -- the native
                // engine only reads WAV (see wav_file.h), so without this the
                // import would silently do nothing. Decode via MediaCodec
                // instead of just rejecting the file.
                destFile.delete()
                val decoded = AudioDecoder.decodeToWav(context, sourceUri, destFile)
                if (!decoded) {
                    _errorMessage.value = "Couldn't import \"$displayName\" — this file's audio format isn't supported on this device."
                    return@launch
                }
            }

            val trackHandle = handles.trackHandles[trackId] ?: return@launch
            val clipHandle = AudioEngine.scheduleClip(trackHandle, destFile.absolutePath, 0L, 0L, -1L, 0f, false)
            if (clipHandle < 0) {
                destFile.delete()
                _errorMessage.value = "Couldn't import \"$displayName\" — the decoded audio couldn't be read."
                return@launch
            }
            val realFrameCount = WaveformAnalyzer.frameCount(destFile) ?: 0L
            val clip = AudioClip(
                id = UUID.randomUUID().toString(), fileName = destFile.name,
                startFrame = 0L, sourceOffsetFrames = 0L, lengthFrames = realFrameCount
            )
            handles.clipHandles[clip.id] = clipHandle
            updateTrack(trackId) { it.copy(clips = it.clips + clip) }
        }
    }

    // --- Clip editing (Phase 2) ------------------------------------------------

    /** Removes the clip both from the native engine and the persisted project. */
    fun deleteClip(trackId: String, clipId: String) {
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { it.copy(clips = it.clips.filterNot { c -> c.id == clipId }) }
    }

    // --- Clipboard: copy / cut / paste (Phase 3) --------------------------------
    // The clipboard holds an AudioClip's data plus which project it came from,
    // since the fileName is only meaningful relative to that project's audio/
    // folder. Pasting into a different project isn't supported (the source
    // file lives in the other project's private folder) -- paste is a no-op
    // across projects, since only one project can be open at a time anyway.

    private var clipboard: AudioClip? = null
    private val _clipboardAvailable = MutableStateFlow(false)
    val clipboardAvailable: StateFlow<Boolean> = _clipboardAvailable.asStateFlow()

    fun copyClip(trackId: String, clipId: String) {
        val clip = _currentProject.value?.tracks?.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        clipboard = clip
        _clipboardAvailable.value = true
    }

    fun duplicateClip(trackId: String, clipId: String) {
        val project = _currentProject.value ?: return
        val clip = project.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        val at = snapFrame(clip.startFrame + clip.lengthFrames)
        val duplicate = clip.copy(id = UUID.randomUUID().toString(), startFrame = at)
        updateTrack(trackId) { it.copy(clips = it.clips + duplicate) }
        scheduleClipOnEngine(trackId, duplicate, project)
    }

    fun cutClip(trackId: String, clipId: String) {
        copyClip(trackId, clipId)
        deleteClip(trackId, clipId)
    }

    /** Pastes the clipboard clip onto [trackId] starting at [atFrame]. */
    fun pasteClip(trackId: String, atFrame: Long) {
        val project = _currentProject.value ?: return
        val source = clipboard ?: return
        val pasted = source.copy(id = UUID.randomUUID().toString(), startFrame = atFrame.coerceAtLeast(0))
        updateTrack(trackId) { it.copy(clips = it.clips + pasted) }
        scheduleClipOnEngine(trackId, pasted, project)
    }

    /**
     * Splits [clipId] at [atFrame] (an absolute timeline frame) into two
     * clips that together reproduce the original exactly -- both reference
     * the same underlying audio file, just with different source offsets.
     * No-op if [atFrame] isn't strictly inside the clip's span.
     */
    fun splitClip(trackId: String, clipId: String, atFrame: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val clipEnd = clip.startFrame + clip.lengthFrames
        if (atFrame <= clip.startFrame || atFrame >= clipEnd) return

        val firstLength = atFrame - clip.startFrame
        val secondLength = clip.lengthFrames - firstLength

        val first = clip.copy(
            id = UUID.randomUUID().toString(),
            lengthFrames = firstLength,
            fadeOutFrames = 0L // the cut point shouldn't inherit the original's fade-out
        )
        val second = clip.copy(
            id = UUID.randomUUID().toString(),
            startFrame = atFrame,
            sourceOffsetFrames = clip.sourceOffsetFrames + firstLength,
            lengthFrames = secondLength,
            fadeInFrames = 0L
        )

        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId } + first + second) }
        scheduleClipOnEngine(trackId, first, project)
        scheduleClipOnEngine(trackId, second, project)
    }

    /**
     * Slip-edits a clip without moving its timeline boundaries. The source
     * window moves inside the same audio file, which is useful for cleaning
     * breaths/noise at the start of a vocal phrase without re-recording.
     */
    fun slipClip(trackId: String, clipId: String, deltaFrames: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val sourceFrames = WaveformAnalyzer.frameCount(File(repository.audioDir(project.id), clip.fileName)) ?: return
        val maxOffset = (sourceFrames - clip.lengthFrames).coerceAtLeast(0L)
        val updated = clip.copy(sourceOffsetFrames = (clip.sourceOffsetFrames + deltaFrames).coerceIn(0L, maxOffset))
        if (updated.sourceOffsetFrames == clip.sourceOffsetFrames) return
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
        scheduleClipOnEngine(trackId, updated, project)
    }

    /**
     * Creates a non-destructive crossfade with the immediately preceding clip
     * on the same track. The clips overlap by [durationFrames]; the previous
     * clip fades out while this clip fades in.
     */
    fun crossfadeWithPrevious(trackId: String, clipId: String, durationFrames: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val previous = track.clips
            .filter { it.id != clipId && it.startFrame < clip.startFrame }
            .maxByOrNull { it.startFrame + it.lengthFrames } ?: return
        val previousEnd = previous.startFrame + previous.lengthFrames
        val maxDuration = minOf(durationFrames.coerceAtLeast(1L), previous.lengthFrames, clip.lengthFrames, clip.startFrame - previous.startFrame)
        if (maxDuration <= 0L) return
        val overlapStart = clip.startFrame - maxDuration
        val previousUpdated = previous.copy(fadeOutFrames = maxDuration)
        val clipUpdated = clip.copy(startFrame = overlapStart, fadeInFrames = maxDuration)
        handles.clipHandles.remove(previous.id)?.let { AudioEngine.removeClip(it) }
        handles.clipHandles.remove(clip.id)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t ->
            t.copy(clips = t.clips.map {
                when (it.id) {
                    previous.id -> previousUpdated
                    clip.id -> clipUpdated
                    else -> it
                }
            })
        }
        scheduleClipOnEngine(trackId, previousUpdated, project)
        scheduleClipOnEngine(trackId, clipUpdated, project)
    }

    /** Deletes a clip and closes the resulting timeline gap for later clips. */
    fun rippleDeleteClip(trackId: String, clipId: String) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val end = clip.startFrame + clip.lengthFrames
        val updatedClips = track.clips.filterNot { it.id == clipId }.map { other ->
            if (other.startFrame >= end) other.copy(startFrame = (other.startFrame - clip.lengthFrames).coerceAtLeast(0L)) else other
        }
        handles.clipHandles.values.forEach { AudioEngine.removeClip(it) }
        handles.clipHandles.clear()
        updateTrack(trackId) { t -> t.copy(clips = updatedClips) }
        val updatedProject = _currentProject.value ?: return
        updatedProject.tracks.forEach { t -> t.clips.forEach { c -> scheduleClipOnEngine(t.id, c, updatedProject) } }
    }

    /** Sets a vocal-friendly linear fade pair in one edit operation. */
    fun setVocalFadePreset(trackId: String, clipId: String, milliseconds: Long) {
        val project = _currentProject.value ?: return
        val clip = project.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        val frames = ((project.sampleRate.toLong() * milliseconds) / 1000L).coerceIn(0L, clip.lengthFrames / 2L)
        setClipFades(trackId, clipId, frames, frames)
    }

    /** Sets clip gain in dB and reschedules the native clip so playback matches the model. */
    fun setClipGain(trackId: String, clipId: String, gainDb: Float) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(gainDb = gainDb.coerceIn(-24f, 12f))
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
        scheduleClipOnEngine(trackId, updated, project)
    }

    /** Sets fade lengths (in frames) on an existing clip and reschedules it on the engine. */
    fun setClipFades(trackId: String, clipId: String, fadeInFrames: Long, fadeOutFrames: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(
            fadeInFrames = fadeInFrames.coerceIn(0L, clip.lengthFrames),
            fadeOutFrames = fadeOutFrames.coerceIn(0L, clip.lengthFrames)
        )
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
        scheduleClipOnEngine(trackId, updated, project)
    }

    // --- Vocal cleanup (Phase 6.6) ----------------------------------------------
    private val _cleanupInProgress = MutableStateFlow(false)
    val cleanupInProgress: StateFlow<Boolean> = _cleanupInProgress.asStateFlow()

    /** Finds audible regions without modifying the source WAV. Threshold and minimum
     * silence duration are intentionally conservative for spoken/sung vocals. */
    fun analyzeVocalCleanup(trackId: String, clipId: String, thresholdDb: Float = -45f, minSilenceMs: Long = 120L) {
        val project = _currentProject.value ?: return
        val clip = project.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        val file = File(repository.audioDir(project.id), clip.fileName)
        if (!file.exists()) return
        _cleanupInProgress.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val regions = VocalCleanupAnalyzer.detectAudibleRegions(file, thresholdDb, minSilenceMs)
            _cleanupInProgress.value = false
            if (regions.isEmpty()) {
                _errorMessage.value = "No audible vocal region was detected with the current threshold."
                return@launch
            }
            // Expose a safe, immediately useful operation: replace the selected clip
            // with packed non-silent windows. The original WAV remains untouched.
            val current = _currentProject.value ?: return@launch
            val currentTrack = current.tracks.find { it.id == trackId } ?: return@launch
            val currentClip = currentTrack.clips.find { it.id == clipId } ?: return@launch
            val newClips = regions.mapIndexed { index, region ->
                currentClip.copy(
                    id = UUID.randomUUID().toString(),
                    startFrame = currentClip.startFrame + regions.take(index).sumOf { it.lengthFrames },
                    sourceOffsetFrames = currentClip.sourceOffsetFrames + region.startFrame,
                    lengthFrames = region.lengthFrames,
                    fadeInFrames = minOf(currentClip.fadeInFrames, region.lengthFrames / 2),
                    fadeOutFrames = minOf(currentClip.fadeOutFrames, region.lengthFrames / 2)
                )
            }
            handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
            updateTrack(trackId) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId } + newClips) }
            val updated = _currentProject.value ?: return@launch
            newClips.forEach { scheduleClipOnEngine(trackId, it, updated) }
        }
    }

    /** Applies the conservative default vocal silence cleanup directly. */
    fun cleanupVocalSilence(trackId: String, clipId: String) =
        analyzeVocalCleanup(trackId, clipId, thresholdDb = -45f, minSilenceMs = 120L)

    // --- Offline vocal pitch correction (Phase 3) -------------------------------
    // Not "Auto-Tune": autocorrelation pitch detection + granular pitch shift,
    // no formant preservation. See ROADMAP.md/README.md for the honest scope.

    private val _pitchCorrectionInProgress = MutableStateFlow(false)
    val pitchCorrectionInProgress: StateFlow<Boolean> = _pitchCorrectionInProgress.asStateFlow()

    fun correctClipPitch(trackId: String, clipId: String, settings: com.almus.studio.audio.PitchCorrectionSettings) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val inputFile = File(repository.audioDir(project.id), clip.fileName)
        if (!inputFile.exists()) return

        _pitchCorrectionInProgress.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outputFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")
            val success = AudioEngine.correctPitch(
                inputFile.absolutePath, outputFile.absolutePath,
                settings.root.pitchClass, settings.scale.toMask(),
                settings.strength, settings.speedMs, settings.hardMode,
                settings.harmony.ordinal, settings.harmonyMix, settings.harmonyPan, settings.formantCompensation
            )
            _pitchCorrectionInProgress.value = false
            if (!success) {
                outputFile.delete()
                _errorMessage.value = "Pitch correction failed — the clip's audio couldn't be processed."
                return@launch
            }
            // Correction preserves duration and alignment exactly, so the
            // clip's frame fields stay the same; only the file changes.
            val updated = clip.copy(fileName = outputFile.name)
            handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
            updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
            scheduleClipOnEngine(trackId, updated, project)
        }
    }

    // --- Effects (Phase 2) -----------------------------------------------------
    // Ships EQ, high/low pass, compressor, delay, and reverb. Limiter, noise
    // gate, chorus, flanger, distortion, and pitch correction are not
    // implemented -- see ROADMAP.md.

    fun updateTrackEffects(trackId: String, params: EffectChainParams) {
        updateTrack(trackId) { it.copy(effects = params.toEffectSettings()) }
        handles.trackHandles[trackId]?.let { params.pushTo(it) }
    }

    fun effectsFor(trackId: String): EffectChainParams {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return EffectChainParams()
        return EffectChainParams.fromEffectSettings(track.effects)
    }

    // --- Offline export (Phase 2) -----------------------------------------------

    /** End of the furthest clip across all tracks, or a short default for an empty project. */
    private fun computeProjectLengthFrames(project: Project): Long {
        val furthest = project.tracks.flatMap { it.clips }
            .maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
        return if (furthest > 0L) furthest else project.sampleRate.toLong() * 4 // 4 seconds of silence, minimum
    }

    fun setReverbReturnVolume(volumeDb: Float) {
        val v = volumeDb.coerceIn(-60f, 6f); _reverbReturnVolumeDb.value = v; AudioEngine.setReverbReturnVolumeDb(v)
        _currentProject.value = _currentProject.value?.copy(reverbReturnVolumeDb = v, modifiedAtEpochMs = System.currentTimeMillis())
    }
    fun setDelayReturnVolume(volumeDb: Float) {
        val v = volumeDb.coerceIn(-60f, 6f); _delayReturnVolumeDb.value = v; AudioEngine.setDelayReturnVolumeDb(v)
        _currentProject.value = _currentProject.value?.copy(delayReturnVolumeDb = v, modifiedAtEpochMs = System.currentTimeMillis())
    }
    fun setReverbReturnMuted(v: Boolean) { _reverbReturnMuted.value=v; AudioEngine.setReverbReturnMuted(v); _currentProject.value=_currentProject.value?.copy(reverbReturnMuted=v, modifiedAtEpochMs=System.currentTimeMillis()) }
    fun setDelayReturnMuted(v: Boolean) { _delayReturnMuted.value=v; AudioEngine.setDelayReturnMuted(v); _currentProject.value=_currentProject.value?.copy(delayReturnMuted=v, modifiedAtEpochMs=System.currentTimeMillis()) }
    fun setReverbReturnSolo(v: Boolean) { _reverbReturnSolo.value=v; AudioEngine.setReverbReturnSolo(v); _currentProject.value=_currentProject.value?.copy(reverbReturnSolo=v, modifiedAtEpochMs=System.currentTimeMillis()) }
    fun setDelayReturnSolo(v: Boolean) { _delayReturnSolo.value=v; AudioEngine.setDelayReturnSolo(v); _currentProject.value=_currentProject.value?.copy(delayReturnSolo=v, modifiedAtEpochMs=System.currentTimeMillis()) }

    fun exportProject() {
        val project = _currentProject.value ?: return
        if (_exportState.value != null) return // an export is already running
        val outputDir = getApplication<Application>().getExternalFilesDir("Exports") ?: return
        outputDir.mkdirs()
        if (project.sampleRate !in setOf(44100, 48000, 96000)) {
            _errorMessage.value = "Unsupported export sample rate: ${project.sampleRate} Hz"
            return
        }
        val base = project.name.ifBlank { "export" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_').ifBlank { "export" }
        val stamp = System.currentTimeMillis()
        val outputFile = File(outputDir, "$base-$stamp.wav")
        val temporaryFile = File(outputDir, "$base-$stamp.wav.part")
        val totalFrames = computeProjectLengthFrames(project)
        if (totalFrames <= 0L) {
            _errorMessage.value = "Nothing to export in this project."
            return
        }
        temporaryFile.delete()
        _exportState.value = ExportState(outputFile = outputFile, temporaryFile = temporaryFile)
        AudioEngine.startOfflineExport(temporaryFile.absolutePath, totalFrames, object : AudioEngine.ExportProgressListener {
            override fun onProgress(fractionComplete: Float) {
                _exportState.value = _exportState.value?.copy(progress = fractionComplete)
            }
            override fun onComplete(outputFilePath: String) {
                val part = File(outputFilePath)
                val metadata = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".txt")
                runCatching {
                    if (outputFile.exists()) outputFile.delete()
                    if (!part.renameTo(outputFile)) throw IllegalStateException("Could not finalize export file")
                    metadata.writeText(
                        "Almus Studio export\n" +
                        "Project: ${project.name}\n" +
                        "Sample rate: ${project.sampleRate} Hz\n" +
                        "Channels: 2\n" +
                        "Encoding: 32-bit IEEE float WAV\n" +
                        "Frames: $totalFrames\n"
                    )
                    _exportState.value = _exportState.value?.copy(progress = 1f, complete = true)
                }.onFailure { e ->
                    part.delete()
                    _exportState.value = _exportState.value?.copy(error = e.message ?: "Could not finalize export", complete = true)
                }
            }
            override fun onError(message: String) {
                temporaryFile.delete()
                _exportState.value = _exportState.value?.copy(error = message, complete = true)
            }
        })
    }

    fun cancelExport() {
        AudioEngine.cancelOfflineExport()
        _exportState.value?.temporaryFile?.delete()
    }

    fun dismissExportResult() {
        _exportState.value = null
    }

}
