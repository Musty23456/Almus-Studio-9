package com.almus.studio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiInputPort
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import com.almus.studio.data.MidiNote
import com.almus.studio.data.DrumSample
import com.almus.studio.data.Project
import com.almus.studio.data.TrackType
import com.almus.studio.audio.AudioEngine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.min
import kotlin.random.Random

/**
 * Phase 7.2 MIDI playback foundation.
 *
 * - Schedules project MIDI notes against the existing AudioEngine playhead.
 * - Sends Note On/Off + Program Change to an Android MIDI output device when connected.
 * - Includes a small offline/local sine-wave poly synth so MIDI tracks can be auditioned
 *   without external hardware. This is intentionally a foundation, not a finished sampler.
 */
class MidiPlaybackEngine(private val context: Context) {
    data class LiveMidiEvent(val type: String, val channel: Int, val a: Int, val b: Int, val frame: Long)
    companion object {
        private const val PPQ = 480L
        private const val DEFAULT_SAMPLE_RATE = 48_000
    }

    private data class SynthPatch(
        val waveform: String = "SINE",
        val attackMs: Int = 8,
        val decayMs: Int = 80,
        val sustain: Float = 0.78f,
        val releaseMs: Int = 140,
        val octave: Int = 0,
        val gainDb: Float = -6f
    ) {
        fun normalized() = copy(
            waveform = waveform.uppercase().let { if (it in setOf("SINE", "SQUARE", "SAW", "TRIANGLE")) it else "SINE" },
            attackMs = attackMs.coerceIn(0, 5000),
            decayMs = decayMs.coerceIn(0, 5000),
            sustain = sustain.coerceIn(0f, 1f),
            releaseMs = releaseMs.coerceIn(1, 5000),
            octave = octave.coerceIn(-2, 2),
            gainDb = gainDb.coerceIn(-48f, 6f)
        )
    }

    private data class ScheduledNote(
        val key: String,
        val channel: Int,
        val pitch: Int,
        val velocity: Int,
        val startFrame: Long,
        val endFrame: Long,
        val patch: SynthPatch,
        val sample: DrumSample? = null,
        val sampleFile: java.io.File? = null,
        val samplerMono: Boolean = false,
        val samplerPolyphony: Int = 64,
        val pitchBend: Float = 0f,
        val bendRangeSemitones: Int = 2,
        val modulation: Float = 0f,
        val aftertouch: Float = 0f,
        val velocityPitchCents: Int = 0
    )

    private data class Voice(
        val key: String,
        val channel: Int,
        val pitch: Int,
        val velocity: Float,
        val patch: SynthPatch,
        var phase: Double = 0.0,
        var ageFrames: Long = 0,
        var releasing: Boolean = false,
        var releaseAgeFrames: Long = 0,
        var sampleFrame: Double = 0.0,
        var sampleEnvelopeAge: Long = 0,
        val sample: DrumSample? = null,
        val sampleFile: java.io.File? = null,
        val samplerMono: Boolean = false,
        val samplerPolyphony: Int = 64,
        var pitchBend: Float = 0f,
        val bendRangeSemitones: Int = 2,
        var modulation: Float = 0f,
        var aftertouch: Float = 0f,
        val velocityPitchCents: Int = 0
    )

    private val lock = Any()
    private val activeNotes = LinkedHashMap<String, ScheduledNote>()
    private val voices = LinkedHashMap<String, Voice>()
    private var project: Project? = null
    private var lastAutomationFrame: Long = Long.MIN_VALUE
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var internalSynthEnabled = true
    private val sampleCache = WavSampleCache()

    private var midiDevice: MidiDevice? = null
    private var midiOutput: MidiInputPort? = null
    private var midiInputOutputPort: MidiOutputPort? = null
    private var liveInputDevice: MidiDevice? = null
    private var liveReceiver: MidiReceiver? = null
    private var liveTrackId: String? = null
    private val liveHeldNotes = mutableSetOf<String>()
    private val liveSustainChannels = BooleanArray(16)
    private val livePitchBend = FloatArray(16)
    private val liveModulation = FloatArray(16)
    private val liveAftertouch = FloatArray(16)
    private var liveMidiEventListener: ((LiveMidiEvent) -> Unit)? = null

    private var audioTrack: AudioTrack? = null
    private var synthThread: Thread? = null
    @Volatile private var synthRunning = false

    fun setInternalSynthEnabled(enabled: Boolean) {
        internalSynthEnabled = enabled
        if (!enabled) synchronized(lock) { voices.clear() }
    }

    fun isInternalSynthEnabled(): Boolean = internalSynthEnabled

    fun start(project: Project, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        stop()
        this.project = project
        this.sampleRate = sampleRate
        lastAutomationFrame = Long.MIN_VALUE
        if (internalSynthEnabled) startSynth()
        sendProgramChanges(project)
    }

    fun stop() {
        synchronized(lock) {
            activeNotes.clear()
            voices.clear()
        }
        project = null
        lastAutomationFrame = Long.MIN_VALUE
        closeMidiDevice()
        stopSynth()
    }

    /** Call periodically (20-50ms is sufficient) with the native audio playhead frame. */
    fun update(playheadFrame: Long) {
        val p = project ?: return
        val notes = flatten(p)
        emitAutomation(p, playheadFrame)
        val shouldBeActive = notes.filter { playheadFrame >= it.startFrame && playheadFrame < it.endFrame }
            .associateBy { it.key }

        synchronized(lock) {
            val currentlyActive = activeNotes.keys.toSet()
            val toStart = shouldBeActive.keys - currentlyActive
            val toStop = currentlyActive - shouldBeActive.keys

            toStart.forEach { key ->
                val note = shouldBeActive.getValue(key)
                activeNotes[key] = note
                sendNoteOn(note.channel, note.pitch, note.velocity)
                if (internalSynthEnabled) {
                    val choke = note.sample?.chokeGroup ?: 0
                    if (choke > 0) {
                        voices.values.filter { it.sample?.chokeGroup == choke }.forEach { it.releasing = true }
                    }
                    if (note.samplerMono) voices.values.forEach { it.releasing = true }
                    val maxVoices = note.samplerPolyphony.coerceIn(1, 64)
                    while (voices.size >= maxVoices) {
                        val oldest = voices.values.firstOrNull() ?: break
                        voices.remove(oldest.key)
                    }
                    voices[key] = Voice(
                        key, note.channel, note.pitch, note.velocity / 127f, note.patch, sampleEnvelopeAge = 0, sample = note.sample, sampleFile = note.sampleFile,
                        samplerMono = note.samplerMono, samplerPolyphony = note.samplerPolyphony, pitchBend = note.pitchBend,
                        bendRangeSemitones = note.bendRangeSemitones, modulation = note.modulation, aftertouch = note.aftertouch,
                        velocityPitchCents = note.velocityPitchCents
                    )
                }
            }
            toStop.forEach { key ->
                val note = activeNotes.remove(key) ?: return@forEach
                sendNoteOff(note.channel, note.pitch)
                voices[key]?.releasing = true
            }
        }
    }

    fun previewSamplerNote(track: com.almus.studio.data.Track, pitch: Int, velocity: Int, project: Project) {
        if (!track.samplerEnabled) return
        val sample = track.drumKit.normalized().samples
            .filter { it.enabled && velocity in it.velocityMin..it.velocityMax }
            .minByOrNull { kotlin.math.abs(it.pitch - pitch) } ?: return
        val file = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects/${project.id}/samples/${sample.fileName}")
        synchronized(lock) {
            if (!synthRunning) startSynth()
            val key = "preview:${System.nanoTime()}"
            voices[key] = Voice(
                key, track.midiChannel.coerceIn(0, 15), pitch.coerceIn(0, 127), velocity / 127f,
                SynthPatch("SINE", sample.attackMs, sample.decayMs, sample.sustain, sample.releaseMs, 0, (track.samplerGainDb + sample.gainDb).coerceIn(-48f, 6f)).normalized(),
                sampleEnvelopeAge = 0, sample = sample, sampleFile = file, samplerMono = track.samplerMono, samplerPolyphony = track.samplerPolyphony,
                pitchBend = track.samplerPitchBend, bendRangeSemitones = track.samplerBendRangeSemitones,
                modulation = track.samplerModulation, aftertouch = track.samplerAftertouch, velocityPitchCents = track.samplerVelocityPitchCents
            )
            Thread({
                Thread.sleep((sample.releaseMs + 500L).coerceAtMost(2500L))
                synchronized(lock) { voices[key]?.releasing = true }
            }, "Almus-Sampler-Preview").start()
        }
    }

    fun connectOutputDevice(deviceId: Int, portIndex: Int = 0, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        closeMidiDevice()
        val manager = context.getSystemService(MidiManager::class.java)
        if (manager == null) {
            onResult(false, "Android MIDI service is unavailable")
            return
        }
        val info = manager.devices.firstOrNull { it.id == deviceId }
        if (info == null || info.outputPortCount <= portIndex) {
            onResult(false, "MIDI output port not found")
            return
        }
        manager.openDevice(info, object : MidiManager.OnDeviceOpenedListener {
            override fun onDeviceOpened(device: MidiDevice?) {
                if (device == null) {
                    onResult(false, "Could not open MIDI device")
                    return
                }
                val output = device.openInputPort(portIndex)
                if (output == null) {
                    device.close()
                    onResult(false, "Could not open MIDI output port")
                    return
                }
                midiDevice = device
                midiOutput = output
                onResult(true, "Connected to ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"}")
            }
        }, null)
    }

    fun disconnectOutputDevice() = closeMidiDevice()

    /** Connects an Android MIDI controller output port as a live input source. */
    fun connectInputDevice(deviceId: Int, portIndex: Int = 0, trackId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        disconnectInputDevice()
        val manager = context.getSystemService(MidiManager::class.java)
        if (manager == null) { onResult(false, "Android MIDI service is unavailable"); return }
        val info = manager.devices.firstOrNull { it.id == deviceId }
        if (info == null || info.outputPortCount <= portIndex) { onResult(false, "MIDI input/output port not found"); return }
        manager.openDevice(info, object : MidiManager.OnDeviceOpenedListener {
            override fun onDeviceOpened(device: MidiDevice?) {
                if (device == null) { onResult(false, "Could not open MIDI input device"); return }
                val port = device.openOutputPort(portIndex)
                if (port == null) { device.close(); onResult(false, "Could not open MIDI controller port"); return }
                liveInputDevice = device
                midiInputOutputPort = port
                liveTrackId = trackId
                val receiver = object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        handleLiveMidi(msg, offset, count)
                    }
                }
                runCatching { port.connect(receiver); liveReceiver = receiver }.onFailure {
                    runCatching { port.close() }; runCatching { device.close() }
                    midiInputOutputPort = null; liveInputDevice = null; liveTrackId = null
                    onResult(false, "Could not attach MIDI input receiver: ${it.message ?: "unknown error"}")
                    return
                }
                onResult(true, "Live MIDI input connected to ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"}")
            }
        }, null)
    }

    fun disconnectInputDevice() {
        synchronized(lock) {
            liveHeldNotes.toList().forEach { key -> voices[key]?.releasing = true }
            liveHeldNotes.clear()
        }
        runCatching { liveReceiver?.let { midiInputOutputPort?.disconnect(it) } }
        runCatching { midiInputOutputPort?.close() }
        runCatching { liveInputDevice?.close() }
        midiInputOutputPort = null
        liveReceiver = null
        liveInputDevice = null
        liveTrackId = null
    }

    fun setLiveTrack(trackId: String?) { liveTrackId = trackId }

    fun setLiveMidiEventListener(listener: ((LiveMidiEvent) -> Unit)?) { liveMidiEventListener = listener }

    fun panicLiveMidi() {
        synchronized(lock) {
            voices.values.forEach { it.releasing = true }
            liveHeldNotes.clear()
            for (i in 0 until 16) liveSustainChannels[i] = false
        }
    }

    private fun handleLiveMidi(bytes: ByteArray, offset: Int, count: Int) {
        if (count <= 0 || offset < 0 || offset >= bytes.size) return
        var i = offset
        val end = minOf(bytes.size, offset + count)
        while (i < end) {
            val status = bytes[i].toInt() and 0xFF
            if (status >= 0xF8) { i++; continue }
            val type = status and 0xF0
            val channel = status and 0x0F
            val dataLen = when (type) { 0xC0, 0xD0 -> 2; else -> 3 }
            if (i + dataLen > end) break
            val a = bytes[i + 1].toInt() and 0x7F
            val b = if (dataLen == 3) bytes[i + 2].toInt() and 0x7F else 0
            liveMidiEventListener?.invoke(LiveMidiEvent(type.toString(16), channel, a, b, runCatching { AudioEngine.getPlayheadFrame() }.getOrDefault(0L)))
            when (type) {
                0x80 -> liveNoteOff(channel, a)
                0x90 -> if (b == 0) liveNoteOff(channel, a) else liveNoteOn(channel, a, b)
                0xB0 -> when (a) {
                    1 -> {
                        liveModulation[channel] = b / 127f
                        synchronized(lock) { voices.filterValues { it.channel == channel }.values.forEach { it.modulation = liveModulation[channel] } }
                    }
                    11 -> updateLiveExpression(channel, expression = b / 127f)
                    64 -> {
                        liveSustainChannels[channel] = b >= 64
                        if (!liveSustainChannels[channel]) releaseSustained(channel)
                    }
                    120, 123 -> releaseChannel(channel)
                }
                0xD0 -> {
                    liveAftertouch[channel] = a / 127f
                    synchronized(lock) { voices.filterValues { it.channel == channel }.values.forEach { it.aftertouch = liveAftertouch[channel] } }
                }
                0xE0 -> {
                    livePitchBend[channel] = ((b shl 7) + a - 8192) / 8192f
                    synchronized(lock) { voices.filterValues { it.channel == channel }.values.forEach { it.pitchBend = livePitchBend[channel] } }
                }
            }
            i += dataLen
        }
    }

    private fun liveNoteOn(channel: Int, pitch: Int, velocity: Int) {
        val p = project ?: return
        val track = p.tracks.firstOrNull { it.id == liveTrackId && it.type == TrackType.MIDI && !it.muted } ?: return
        if (!track.samplerEnabled) return
        val sample = track.drumKit.normalized().samples.filter { it.enabled && velocity in it.velocityMin..it.velocityMax }
            .minByOrNull { kotlin.math.abs(it.pitch - pitch) } ?: return
        val file = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects/${p.id}/samples/${sample.fileName}")
        synchronized(lock) {
            if (!synthRunning) startSynth()
            if (track.samplerMono) voices.values.filter { it.channel == channel }.forEach { it.releasing = true }
            val maxVoices = track.samplerPolyphony.coerceIn(1, 64)
            while (voices.size >= maxVoices) voices.remove(voices.values.firstOrNull()?.key ?: break)
            val key = "live:$channel:$pitch"
            voices[key]?.releasing = true
            voices[key] = Voice(
                key, channel, pitch, velocity / 127f,
                SynthPatch("SINE", sample.attackMs, sample.decayMs, sample.sustain, sample.releaseMs, 0, (track.samplerGainDb + sample.gainDb).coerceIn(-48f, 6f)).normalized(),
                sampleEnvelopeAge = 0, sample = sample, sampleFile = file, samplerMono = track.samplerMono, samplerPolyphony = track.samplerPolyphony,
                pitchBend = livePitchBend[channel], bendRangeSemitones = track.samplerBendRangeSemitones, modulation = liveModulation[channel],
                aftertouch = liveAftertouch[channel], velocityPitchCents = track.samplerVelocityPitchCents
            )
            liveHeldNotes.add(key)
        }
    }

    private fun liveNoteOff(channel: Int, pitch: Int) {
        val key = "live:$channel:$pitch"
        if (liveSustainChannels[channel]) return
        synchronized(lock) { voices[key]?.releasing = true; liveHeldNotes.remove(key) }
    }

    private fun releaseSustained(channel: Int) {
        synchronized(lock) { liveHeldNotes.filter { it.startsWith("live:$channel:") }.forEach { key -> voices[key]?.releasing = true; liveHeldNotes.remove(key) } }
    }

    private fun releaseChannel(channel: Int) {
        synchronized(lock) { voices.filterValues { it.channel == channel }.values.forEach { it.releasing = true }; liveHeldNotes.removeAll { it.startsWith("live:$channel:") } }
    }

    private fun updateLiveExpression(channel: Int, expression: Float) {
        synchronized(lock) {
            voices.filterValues { it.channel == channel }.values.forEach { it.aftertouch = expression.coerceIn(0f, 1f) }
        }
    }

    private fun emitAutomation(project: Project, playheadFrame: Long) {
        val framesPerTick = project.sampleRate.toDouble() * 60.0 / project.bpm.toDouble() / PPQ.toDouble()
        if (lastAutomationFrame == Long.MIN_VALUE || playheadFrame < lastAutomationFrame) {
            lastAutomationFrame = playheadFrame
            return
        }
        project.tracks.filter { it.type == TrackType.MIDI && !it.muted }.forEach { track ->
            track.midiClips.filter { !track.midiTakeCompingEnabled || it.takeSelected }.forEach { clip ->
                val clipStart = clip.startFrame
                clip.automation.filter { event ->
                    val frame = clipStart + (event.tick * framesPerTick).toLong()
                    frame > lastAutomationFrame && frame <= playheadFrame
                }.forEach { event ->
                    when (event.type.uppercase()) {
                        "CC" -> sendControlChange(event.channel, event.controller, event.value.coerceIn(0,127))
                        "PB", "PITCH_BEND" -> sendPitchBend(event.channel, event.value.coerceIn(0,16383))
                        "AT", "AFTERTOUCH" -> sendAftertouch(event.channel, event.value.coerceIn(0,127))
                    }
                }
            }
        }
        lastAutomationFrame = playheadFrame
    }

    private fun flatten(project: Project): List<ScheduledNote> {
        val result = ArrayList<ScheduledNote>()
        val framesPerTick = project.sampleRate.toDouble() * 60.0 /
            project.bpm.toDouble() / PPQ.toDouble()
        project.tracks.filter { it.type == TrackType.MIDI && !it.muted }.forEach { track ->
            val bank = if (track.drumPatterns.isNotEmpty()) track.drumPatterns else listOfNotNull(track.drumPattern)
            val pattern = bank.getOrNull(track.activeDrumPattern.coerceIn(0,(bank.size-1).coerceAtLeast(0)))?.normalized()
            pattern?.let { pat ->
                val sequence = if (pat.chain.isEmpty()) listOf(track.activeDrumPattern) else pat.chain
                var chainOffsetTicks = 0L
                sequence.forEach { patternIndex ->
                    val cp = bank.getOrNull(patternIndex)?.normalized() ?: pat
                    val stepTicks = PPQ / 4L; val totalSteps=cp.steps*cp.bars; val patternTicks=totalSteps*stepTicks
                    val swingTicks=(stepTicks*cp.swingPercent/100.0).toLong()
                    val soloExists=cp.sounds.any{it.solo&&!it.muted}
                    cp.sounds.forEach { sound ->
                        if(sound.muted || (soloExists&&!sound.solo)) return@forEach
                        sound.velocities.forEachIndexed { index,velocity ->
                            if(velocity>0 && index<totalSteps){
                                val probability=sound.probabilities.getOrNull(index)?:100
                                if(Random.nextInt(100)>=probability)return@forEachIndexed
                                val offset=if(index%2==1)swingTicks else 0L
                                val start=((chainOffsetTicks+index*stepTicks+offset)*framesPerTick).toLong()
                                val duration=(stepTicks*framesPerTick).toLong().coerceAtLeast(1L)
                                val accent=if(sound.accents.getOrNull(index)==true)1.18 else 1.0
                                val vel=(velocity*accent).toInt().coerceIn(1,127)
                                val sample = track.drumKit.normalized().samples.firstOrNull { it.enabled && it.pitch == sound.pitch && vel in it.velocityMin..it.velocityMax }
                                val sampleFile = sample?.let { java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects/${project.id}/samples/${it.fileName}") }
                                val sampleEnd = if (sample != null && sampleFile != null) {
                                    sampleCache.get(sampleFile)?.let { decoded ->
                                        val totalFrames = (decoded.pcm.size / decoded.channels).toLong()
                                        val from = sample.startFrame.coerceAtMost(totalFrames)
                                        val to = if (sample.endFrame > from) sample.endFrame.coerceAtMost(totalFrames) else totalFrames
                                        val sourceFrames = (to - from).coerceAtLeast(1L)
                                        val pitchRatio = Math.pow(2.0, ((sound.pitch - sample.rootNote) + sample.fineTuneCents / 100.0) / 12.0)
                                        val outputFrames = (sourceFrames / pitchRatio * sampleRate.toDouble() / decoded.sampleRate.toDouble()).toLong().coerceAtLeast(1L)
                                        start + if (sample.loopEnabled) maxOf(duration, outputFrames) else outputFrames
                                    } ?: (start + duration)
                                } else start + duration
                                result += ScheduledNote("${track.id}:drum:${cp.id}:$index:$chainOffsetTicks",9,sound.pitch.coerceIn(0,127),vel,start,sampleEnd,SynthPatch("SINE",1,30,0f,120,0,(-10f+sound.volumeDb + (sample?.gainDb ?: 0f)).coerceIn(-60f,6f)).normalized(), sample, sampleFile, false, 64)
                            }
                        }
                    }
                    chainOffsetTicks += patternTicks
                }
            }
            track.midiClips.filter { !track.midiTakeCompingEnabled || it.takeSelected }.forEach { clip ->
                clip.notes.forEach { raw ->
                    val n = raw.normalized()
                    val start = clip.startFrame + (n.startTick * framesPerTick).toLong()
                    val duration = (n.durationTicks * framesPerTick).toLong().coerceAtLeast(1L)
                    val samplerSample = if (track.samplerEnabled) {
                        track.drumKit.normalized().samples
                            .filter { it.enabled && n.velocity in it.velocityMin..it.velocityMax }
                            .minByOrNull { kotlin.math.abs(it.pitch - n.pitch) }
                    } else null
                    val samplerFile = samplerSample?.let { java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects/${project.id}/samples/${it.fileName}") }
                    val samplerEnd = if (samplerSample != null && samplerFile != null) {
                        sampleCache.get(samplerFile)?.let { decoded ->
                            val total = (decoded.pcm.size / decoded.channels).toLong()
                            val from = samplerSample.startFrame.coerceAtMost(total)
                            val to = if (samplerSample.endFrame > from) samplerSample.endFrame.coerceAtMost(total) else total
                            val sourceFrames = (to - from).coerceAtLeast(1L)
                            val ratio = Math.pow(2.0, ((n.pitch - samplerSample.rootNote) + samplerSample.fineTuneCents / 100.0) / 12.0)
                            val out = (sourceFrames / ratio * sampleRate.toDouble() / decoded.sampleRate.toDouble()).toLong().coerceAtLeast(1L)
                            start + if (samplerSample.loopEnabled) maxOf(duration, out) else minOf(duration, out)
                        } ?: (start + duration)
                    } else start + duration
                    result += ScheduledNote(
                        key = "${track.id}:${clip.id}:${n.id}",
                        channel = n.channel.coerceIn(0, 15),
                        pitch = n.pitch.coerceIn(0, 127),
                        velocity = n.velocity.coerceIn(1, 127),
                        startFrame = start,
                        endFrame = samplerEnd,
                        patch = if (samplerSample != null) SynthPatch("SINE", samplerSample.attackMs, samplerSample.decayMs, samplerSample.sustain, samplerSample.releaseMs, 0, (track.samplerGainDb + samplerSample.gainDb).coerceIn(-48f, 6f)).normalized() else SynthPatch(track.midiWaveform, track.midiAttackMs, track.midiDecayMs, track.midiSustain, track.midiReleaseMs, track.midiOctave, track.midiGainDb).normalized(),
                        sample = samplerSample,
                        sampleFile = samplerFile,
                        samplerMono = track.samplerMono,
                        samplerPolyphony = track.samplerPolyphony,
                        pitchBend = track.samplerPitchBend,
                        bendRangeSemitones = track.samplerBendRangeSemitones,
                        modulation = track.samplerModulation,
                        aftertouch = track.samplerAftertouch,
                        velocityPitchCents = track.samplerVelocityPitchCents
                    )
                }
            }
        }
        return result
    }

    private fun sendProgramChanges(project: Project) {
        project.tracks.filter { it.type == TrackType.MIDI && !it.muted }.forEach { track ->
            val channel = track.midiChannel.coerceIn(0, 15)
            val program = track.midiProgram.coerceIn(0, 127)
            sendMidi(byteArrayOf((0xC0 or channel).toByte(), program.toByte()))
        }
    }

    private fun sendNoteOn(channel: Int, pitch: Int, velocity: Int) =
        sendMidi(byteArrayOf((0x90 or channel).toByte(), pitch.toByte(), velocity.toByte()))

    private fun sendNoteOff(channel: Int, pitch: Int) =
        sendMidi(byteArrayOf((0x80 or channel).toByte(), pitch.toByte(), 0))

    private fun sendControlChange(channel: Int, controller: Int, value: Int) {
        sendMidi(byteArrayOf((0xB0 or channel.coerceIn(0,15)).toByte(), controller.coerceIn(0,127).toByte(), value.coerceIn(0,127).toByte()))
    }

    private fun sendPitchBend(channel: Int, value: Int) {
        val v = value.coerceIn(0,16383)
        sendMidi(byteArrayOf((0xE0 or channel.coerceIn(0,15)).toByte(), (v and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte()))
    }

    private fun sendAftertouch(channel: Int, value: Int) {
        sendMidi(byteArrayOf((0xD0 or channel.coerceIn(0,15)).toByte(), value.coerceIn(0,127).toByte()))
    }

    private fun sendMidi(bytes: ByteArray) {
        runCatching { midiOutput?.send(bytes, 0, bytes.size) }
    }

    private fun closeMidiDevice() {
        runCatching { midiOutput?.close() }
        runCatching { midiDevice?.close() }
        disconnectInputDevice()
        midiOutput = null
        midiDevice = null
    }

    private fun startSynth() {
        if (synthRunning) return
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 10)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        synthRunning = true
        track.play()
        synthThread = Thread({ renderSynth(track) }, "Almus-MIDI-Synth").also { it.start() }
    }

    private fun stopSynth() {
        synthRunning = false
        runCatching { synthThread?.join(300) }
        synthThread = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    private fun renderSynth(track: AudioTrack) {
        val block = ShortArray(1024)
        val twoPi = 2.0 * PI
        while (synthRunning) {
            synchronized(lock) {
                var frame = 0
                while (frame < block.size) {
                    var left = 0.0
                    var right = 0.0
                    val iterator = voices.values.iterator()
                    while (iterator.hasNext()) {
                        val voice = iterator.next()
                        val patch = voice.patch
                        val velocityPitch = ((voice.velocity * 127.0 - 64.0) / 63.0) * voice.velocityPitchCents
                        val bendCents = voice.pitchBend.coerceIn(-1f, 1f) * voice.bendRangeSemitones.coerceIn(1, 24) * 100.0
                        val vibratoCents = if (voice.modulation > 0f) {
                            kotlin.math.sin(voice.ageFrames.toDouble() / sampleRate.toDouble() * 2.0 * PI * 5.0) * voice.modulation * 35.0
                        } else 0.0
                        val expressionCents = velocityPitch + bendCents + vibratoCents
                        val frequency = 440.0 * Math.pow(2.0, (((voice.pitch + patch.octave * 12) - 69) * 100.0 + expressionCents) / 1200.0)
                        val phase01 = (voice.phase / twoPi).mod(1.0)
                        val raw = if (voice.sample != null && voice.sampleFile != null) {
                            sampledDrum(voice) ?: if (voice.channel == 9) drumSample(voice.pitch, voice.ageFrames) else 0.0
                        } else if (voice.channel == 9) {
                            drumSample(voice.pitch, voice.ageFrames)
                        } else when (patch.waveform) {
                            "SQUARE" -> if (phase01 < 0.5) 1.0 else -1.0
                            "SAW" -> 2.0 * phase01 - 1.0
                            "TRIANGLE" -> 1.0 - 4.0 * kotlin.math.abs(phase01 - 0.5)
                            else -> sin(voice.phase)
                        }
                        val envelope = if (voice.sample != null) sampleEnvelopeGain(voice, voice.sample) else envelopeGain(voice, patch)
                        val gain = Math.pow(10.0, patch.gainDb / 20.0)
                        val aftertouchGain = 1.0 + (voice.aftertouch.coerceIn(0f, 1f) * 0.35)
                        val mono = raw * voice.velocity * envelope * gain * aftertouchGain
                        val pan = voice.sample?.pan?.coerceIn(-1f, 1f) ?: 0f
                        val l = kotlin.math.cos((pan + 1.0) * PI / 4.0)
                        val r = kotlin.math.sin((pan + 1.0) * PI / 4.0)
                        left += mono * l
                        right += mono * r
                        voice.phase += twoPi * frequency / sampleRate
                        if (voice.phase >= twoPi) voice.phase %= twoPi
                        voice.ageFrames++
                        if (voice.releasing) {
                            voice.releaseAgeFrames++
                            if (voice.releaseAgeFrames >= sampleRate * patch.releaseMs / 1000L) iterator.remove()
                        }
                    }
                    block[frame] = (left.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                    block[frame + 1] = (right.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                    frame += 2
                }
            }
            runCatching { track.write(block, 0, block.size) }
        }
    }

    /** Converts the stored performance controls into cents for deterministic offline playback. */
    private fun performancePitchCents(voice: Voice): Double {
        val velocityPitch = ((voice.velocity * 127.0 - 64.0) / 63.0) * voice.velocityPitchCents
        val bend = voice.pitchBend.coerceIn(-1f, 1f) * voice.bendRangeSemitones.coerceIn(1, 24) * 100.0
        return velocityPitch + bend
    }

    private fun sampledDrum(voice: Voice): Double? {
        val sampleDef = voice.sample ?: return null
        val file = voice.sampleFile ?: return null
        val sample = sampleCache.get(file) ?: return null
        val totalFrames = (sample.pcm.size / sample.channels).toLong()
        val sourceStart = sampleDef.startFrame.coerceIn(0L, totalFrames)
        val sourceEnd = (if (sampleDef.endFrame > sourceStart) sampleDef.endFrame else totalFrames).coerceAtMost(totalFrames)
        val playableFrames = (sourceEnd - sourceStart).coerceAtLeast(1L)
        val ratio = Math.pow(2.0, ((voice.pitch - sampleDef.rootNote) * 100.0 + sampleDef.fineTuneCents + performancePitchCents(voice)) / 1200.0)
        val sourceStep = ratio * sample.sampleRate.toDouble() / sampleRate.toDouble()
        var logicalFrame = voice.sampleFrame
        if (sampleDef.loopEnabled) {
            val loopStart = (sampleDef.loopStartFrame - sourceStart).coerceIn(0L, playableFrames - 1L)
            val loopEnd = (if (sampleDef.loopEndFrame > sampleDef.loopStartFrame) sampleDef.loopEndFrame - sourceStart else playableFrames)
                .coerceIn(loopStart + 1L, playableFrames)
            if (logicalFrame >= loopStart) {
                val loopLength = (loopEnd - loopStart).coerceAtLeast(1L)
                logicalFrame = loopStart + ((logicalFrame - loopStart) % loopLength)
            }
        } else if (logicalFrame >= playableFrames) return 0.0
        val logicalInt = logicalFrame.toLong().coerceIn(0L, playableFrames - 1L)
        val srcLogical = if (sampleDef.reverse) playableFrames - 1L - logicalInt else logicalInt
        val srcFrame = sourceStart + srcLogical
        val i = (srcFrame * sample.channels).toInt()
        if (i < 0 || i >= sample.pcm.size) return null
        var mono = if (sample.channels == 1) sample.pcm[i].toDouble() / 32768.0 else {
            val r = if (i + 1 < sample.pcm.size) sample.pcm[i + 1] else sample.pcm[i]
            (sample.pcm[i].toDouble() + r.toDouble()) / (2.0 * 32768.0)
        }
        val fadeIn = sampleDef.fadeInFrames.coerceAtMost(playableFrames)
        val fadeOut = sampleDef.fadeOutFrames.coerceAtMost(playableFrames)
        val inGain = if (fadeIn > 0) (logicalInt.toDouble() / fadeIn).coerceIn(0.0, 1.0) else 1.0
        val outGain = if (fadeOut > 0 && !sampleDef.loopEnabled) ((playableFrames - logicalInt).toDouble() / fadeOut).coerceIn(0.0, 1.0) else 1.0
        mono *= inGain * outGain
        if (sampleDef.normalize) {
            var peak = 0.0
            val scanFrames = min(playableFrames, 48000L)
            var scan = 0L
            while (scan < scanFrames) {
                val sf = sourceStart + (if (sampleDef.reverse) playableFrames - 1L - scan else scan)
                val si = (sf * sample.channels).toInt()
                val v = if (sample.channels == 1) kotlin.math.abs(sample.pcm[si].toDouble()) else {
                    val r = if (si + 1 < sample.pcm.size) sample.pcm[si + 1].toDouble() else sample.pcm[si].toDouble()
                    kotlin.math.abs((sample.pcm[si].toDouble() + r) / 2.0)
                } / 32768.0
                if (v > peak) peak = v
                scan++
            }
            if (peak > 0.0001) mono /= peak
        }
        voice.sampleFrame += sourceStep
        val gain = Math.pow(10.0, sampleDef.gainDb / 20.0)
        return mono.coerceIn(-1.0, 1.0) * gain
    }

    private fun sampleEnvelopeGain(voice: Voice, sample: DrumSample): Double {
        val age = voice.sampleEnvelopeAge
        voice.sampleEnvelopeAge++
        val attack = (sampleRate * sample.attackMs / 1000L).coerceAtLeast(0L)
        val decay = (sampleRate * sample.decayMs / 1000L).coerceAtLeast(0L)
        val release = (sampleRate * sample.releaseMs / 1000L).coerceAtLeast(1L)
        val sustain = sample.sustain.coerceIn(0f, 1f).toDouble()
        val adsr = when {
            attack > 0 && age < attack -> age.toDouble() / attack
            decay > 0 && age < attack + decay -> 1.0 - (1.0 - sustain) * ((age - attack).toDouble() / decay)
            else -> sustain
        }
        if (!sample.loopEnabled && sample.releaseMs > 0) {
            // Keep the tail controlled when a short one-shot reaches its scheduled end.
            val remaining = (release - (age % release)).toDouble() / release
            return adsr.coerceIn(0.0, 1.0) * (0.35 + 0.65 * remaining.coerceIn(0.0, 1.0))
        }
        return adsr.coerceIn(0.0, 1.0)
    }

    private fun drumSample(pitch: Int, age: Long): Double {
        val t = age.toDouble() / sampleRate.toDouble()
        val decay = when (pitch) { 36 -> 0.32; 38, 39 -> 0.20; 42 -> 0.09; 46 -> 0.28; 49 -> 0.70; else -> 0.18 }
        val env = kotlin.math.exp(-t / decay)
        return when (pitch) {
            36 -> sin(2.0 * PI * (150.0 - 105.0 * (t / 0.32).coerceIn(0.0, 1.0)) * t) * env
            45 -> sin(2.0 * PI * 115.0 * t) * env
            42, 46, 49 -> {
                val x = kotlin.math.sin((age * 12.9898 + pitch * 78.233)) * 43758.5453
                val noise = (x - kotlin.math.floor(x)) * 2.0 - 1.0
                noise * env
            }
            else -> {
                val x = kotlin.math.sin((age * 17.123 + pitch * 31.77)) * 24634.6345
                val noise = (x - kotlin.math.floor(x)) * 2.0 - 1.0
                (noise * 0.75 + sin(2.0 * PI * 185.0 * t) * 0.25) * env
            }
        }
    }

    private fun envelopeGain(voice: Voice, patch: SynthPatch): Double {
        if (voice.releasing) {
            val releaseFrames = (sampleRate * patch.releaseMs / 1000L).coerceAtLeast(1L)
            val start = sustainEnvelope(voice.ageFrames, patch)
            return start * (1.0 - (voice.releaseAgeFrames.toDouble() / releaseFrames)).coerceIn(0.0, 1.0)
        }
        return sustainEnvelope(voice.ageFrames, patch)
    }

    private fun sustainEnvelope(age: Long, patch: SynthPatch): Double {
        val attack = (sampleRate * patch.attackMs / 1000L).coerceAtLeast(1L)
        val decay = (sampleRate * patch.decayMs / 1000L).coerceAtLeast(1L)
        return when {
            age < attack -> age.toDouble() / attack
            age < attack + decay -> 1.0 - (1.0 - patch.sustain) * ((age - attack).toDouble() / decay)
            else -> patch.sustain.toDouble()
        }
    }
}
