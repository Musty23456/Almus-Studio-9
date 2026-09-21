package com.almus.studio.data

import com.squareup.moshi.JsonClass

/**
 * On-disk project format. A project is a folder:
 *   /Android/data/com.almus.studio/files/Projects/<projectId>/
 *       project.json        <- this data, serialized
 *       audio/<clipId>.wav  <- managed copies of imported/recorded audio
 *
 * Using the app's external files directory (not internal-only storage) means
 * projects survive app reinstalls the user backs up manually and are visible
 * to a connected computer or file manager, per the "no inaccessible internal
 * paths for user projects" requirement.
 */
@JsonClass(generateAdapter = true)
data class Project(
    val id: String,
    val name: String,
    val bpm: Int = 120,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val sampleRate: Int = 48000,
    val createdAtEpochMs: Long,
    val modifiedAtEpochMs: Long,
    val tracks: List<Track> = emptyList(),
    val masterVolumeDb: Float = 0f,
    val masterLimiterEnabled: Boolean = true,
    val masterLimiterCeilingDb: Float = -1f,
    val masterLimiterReleaseMs: Float = 80f,
    val reverbReturnVolumeDb: Float = 0f,
    val delayReturnVolumeDb: Float = 0f,
    val reverbReturnMuted: Boolean = false,
    val reverbReturnSolo: Boolean = false,
    val delayReturnMuted: Boolean = false,
    val delayReturnSolo: Boolean = false,
    val masterAutomation: List<AutomationPoint> = emptyList(),
    val reverbReturnAutomation: List<AutomationPoint> = emptyList(),
    val delayReturnAutomation: List<AutomationPoint> = emptyList(),
    /** Named mixer snapshots for fast non-destructive vocal mix recall. */
    val vocalMixSnapshots: List<VocalMixSnapshot> = emptyList()
)

@JsonClass(generateAdapter = true)
data class VocalMixSnapshot(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val masterVolumeDb: Float,
    val reverbReturnVolumeDb: Float,
    val delayReturnVolumeDb: Float,
    val reverbReturnMuted: Boolean,
    val reverbReturnSolo: Boolean,
    val delayReturnMuted: Boolean,
    val delayReturnSolo: Boolean,
    val tracks: List<TrackMixSnapshot> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TrackMixSnapshot(
    val trackId: String,
    val volumeDb: Float,
    val pan: Float,
    val muted: Boolean,
    val solo: Boolean,
    val reverbSendDb: Float,
    val delaySendDb: Float
)

@JsonClass(generateAdapter = true)
data class Track(
    val id: String,
    val name: String,
    val volumeDb: Float = 0f,
    val pan: Float = 0f, // -1.0 (left) .. 1.0 (right)
    val muted: Boolean = false,
    val solo: Boolean = false,
    val armed: Boolean = false,
    val colorHex: String = "#5CE1E6",
    val clips: List<AudioClip> = emptyList(),
    val effects: List<EffectSettings> = emptyList(),
    /** Track role. Existing projects default to AUDIO for backward compatibility. */
    val type: TrackType = TrackType.AUDIO,
    /** MIDI clips stored non-destructively on this track. */
    val midiClips: List<MidiClip> = emptyList(),
    /** Professional MIDI take-lane state. */
    val midiTakeCompingEnabled: Boolean = false,
    val activeMidiTakeId: String? = null,
    val midiChannel: Int = 0,
    /** General MIDI program number, 0..127. */
    val midiProgram: Int = 0,
    /** Built-in instrument patch settings for the local MIDI synth. */
    val midiWaveform: String = "SINE",
    val midiAttackMs: Int = 8,
    val midiDecayMs: Int = 80,
    val midiSustain: Float = 0.78f,
    val midiReleaseMs: Int = 140,
    val midiOctave: Int = 0,
    val midiGainDb: Float = -6f,
    /** Professional sampler instrument mode for chromatic sample playback. */
    val samplerEnabled: Boolean = false,
    val samplerMono: Boolean = false,
    val samplerLegato: Boolean = false,
    val samplerGlideMs: Int = 0,
    val samplerPolyphony: Int = 16,
    val samplerSustainPedal: Boolean = true,
    val samplerGainDb: Float = -6f,
    /** Performance controls for live/preview sampler expression. */
    val samplerPitchBend: Float = 0f,
    val samplerBendRangeSemitones: Int = 2,
    val samplerModulation: Float = 0f,
    val samplerAftertouch: Float = 0f,
    /** Velocity-to-pitch expression depth in cents. */
    val samplerVelocityPitchCents: Int = 0,
    /** Optional 16-step drum pattern stored on a MIDI track. */
    val drumPattern: DrumPattern? = null,
    val drumPatterns: List<DrumPattern> = emptyList(),
    val activeDrumPattern: Int = 0,
    /** Sample-based drum kit mapped by General MIDI drum pitch. */
    val drumKit: DrumKit = DrumKit(),
    /** Named drum-kit bank. The active drumKit is mirrored here for backward compatibility. */
    val drumKits: List<DrumKit> = emptyList(),
    val activeDrumKit: Int = 0,
    /** Persistent post/pre-fader send levels in dB for the native reverb/delay aux buses. */
    val reverbSendDb: Float = -60f,
    val delaySendDb: Float = -60f,
    val reverbPreFader: Boolean = false,
    val delayPreFader: Boolean = false,
    /** Non-destructive automation lanes. Points are sorted by frame. */
    val automation: Map<String, List<AutomationPoint>> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class AutomationPoint(
    val frame: Long,
    val value: Float
)

enum class AutomationParameter {
    VOLUME_DB, PAN, REVERB_SEND_DB, DELAY_SEND_DB
}

@JsonClass(generateAdapter = true)
data class AudioClip(
    val id: String,
    /** Path relative to the project's audio/ folder, e.g. "a1b2c3.wav" */
    val fileName: String,
    /** Where the clip begins on the track timeline, in sample frames at project sample rate */
    val startFrame: Long,
    /** Offset into the source file where playback should start, in frames */
    val sourceOffsetFrames: Long,
    /** Length of the clip as it plays on the timeline, in frames */
    val lengthFrames: Long,
    val gainDb: Float = 0f,
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0,
    val looping: Boolean = false,
    /** Non-destructive vocal take metadata. Clips sharing a takeGroupId belong to one take lane. */
    val takeGroupId: String? = null,
    val takeNumber: Int = 0,
    val takeSelected: Boolean = true
)

@JsonClass(generateAdapter = true)
data class EffectSettings(
    val type: EffectType,
    val enabled: Boolean = true,
    /** Simple flat parameter map, e.g. "wet" -> 0.3f, "cutoffHz" -> 4000f */
    val params: Map<String, Float> = emptyMap()
)


enum class TrackType { AUDIO, MIDI }

@JsonClass(generateAdapter = true)
data class MidiClip(
    val id: String,
    val startFrame: Long,
    val lengthFrames: Long,
    val notes: List<MidiNote> = emptyList(),
    val name: String = "MIDI Clip",
    val automation: List<MidiAutomationEvent> = emptyList(),
    val takeId: String? = null,
    /** Professional MIDI take-lane metadata. */
    val takeNumber: Int = 0,
    val takeSelected: Boolean = true
)

@JsonClass(generateAdapter = true)
data class MidiAutomationEvent(
    val tick: Long,
    val type: String,
    val controller: Int = 0,
    val value: Int = 0,
    val channel: Int = 0
) {
    fun normalized(): MidiAutomationEvent = copy(
        tick = tick.coerceAtLeast(0),
        controller = controller.coerceIn(0, 127),
        value = value.coerceIn(0, 16383),
        channel = channel.coerceIn(0, 15)
    )
}

@JsonClass(generateAdapter = true)
data class MidiNote(
    val id: String,
    val startTick: Long,
    val durationTicks: Long,
    val pitch: Int,
    val velocity: Int = 100,
    val channel: Int = 0
) {
    fun normalized(): MidiNote = copy(
        startTick = startTick.coerceAtLeast(0),
        durationTicks = durationTicks.coerceAtLeast(1),
        pitch = pitch.coerceIn(0, 127),
        velocity = velocity.coerceIn(1, 127),
        channel = channel.coerceIn(0, 15)
    )
}

@JsonClass(generateAdapter = true)
data class DrumPattern(
    val id: String = "pattern-1",
    val name: String = "Pattern 1",
    val steps: Int = 16,
    val bars: Int = 1,
    val swingPercent: Int = 0,
    val chain: List<Int> = emptyList(),
    val sounds: List<DrumSoundPattern> = emptyList()
) {
    fun normalized(): DrumPattern {
        val count = if (steps >= 24) 32 else if (steps <= 8) 8 else 16
        val safeBars = bars.coerceIn(1, 4)
        val total = (count * safeBars).coerceAtMost(128)
        return copy(
            id = id.ifBlank { "pattern-1" }, name = name.ifBlank { "Pattern 1" },
            steps = count, bars = safeBars, swingPercent = swingPercent.coerceIn(0, 75),
            chain = chain.filter { it >= 0 }.take(16), sounds = sounds.map { it.normalized(total) }
        )
    }
}

@JsonClass(generateAdapter = true)
data class DrumSoundPattern(
    val pitch: Int,
    val name: String,
    val velocities: List<Int> = emptyList(),
    val probabilities: List<Int> = emptyList(),
    val accents: List<Boolean> = emptyList(),
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false
) {
    fun normalized(total: Int): DrumSoundPattern = copy(
        pitch = pitch.coerceIn(0, 127),
        velocities = List(total) { i -> velocities.getOrNull(i)?.coerceIn(0, 127) ?: 0 },
        probabilities = List(total) { i -> probabilities.getOrNull(i)?.coerceIn(0, 100) ?: 100 },
        accents = List(total) { i -> accents.getOrNull(i) ?: false },
        volumeDb = volumeDb.coerceIn(-60f, 12f), pan = pan.coerceIn(-1f, 1f)
    )
}

@JsonClass(generateAdapter = true)
data class DrumKit(
    val id: String = "default-kit",
    val name: String = "Default Kit",
    val samples: List<DrumSample> = emptyList()
) {
    fun normalized(): DrumKit = copy(
        id = id.ifBlank { "default-kit" },
        name = name.ifBlank { "Default Kit" },
        samples = samples.map { it.normalized() }.distinctBy { it.pitch to it.velocityMin }
    )
}

@JsonClass(generateAdapter = true)
data class DrumSample(
    val id: String,
    val pitch: Int,
    val name: String,
    /** WAV filename relative to the project's samples/ directory. */
    val fileName: String,
    val velocityMin: Int = 1,
    val velocityMax: Int = 127,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val startFrame: Long = 0L,
    /** 0 means use the end of the source. */
    val endFrame: Long = 0L,
    val fadeInFrames: Long = 0L,
    val fadeOutFrames: Long = 0L,
    val reverse: Boolean = false,
    val normalize: Boolean = false,
    /** Sampler playback controls. */
    val rootNote: Int = 60,
    val fineTuneCents: Int = 0,
    val loopEnabled: Boolean = false,
    val loopStartFrame: Long = 0L,
    val loopEndFrame: Long = 0L,
    val attackMs: Int = 0,
    val decayMs: Int = 0,
    val sustain: Float = 1f,
    val releaseMs: Int = 20,
    /** Optional choke group; voices in the same non-zero group are mutually exclusive. */
    val chokeGroup: Int = 0,
    val enabled: Boolean = true
) {
    fun normalized() = copy(
        pitch = pitch.coerceIn(0, 127),
        velocityMin = velocityMin.coerceIn(1, 127),
        velocityMax = velocityMax.coerceIn(1, 127).coerceAtLeast(velocityMin),
        gainDb = gainDb.coerceIn(-60f, 12f),
        pan = pan.coerceIn(-1f, 1f),
        startFrame = startFrame.coerceAtLeast(0L),
        endFrame = endFrame.coerceAtLeast(0L),
        rootNote = rootNote.coerceIn(0, 127),
        fineTuneCents = fineTuneCents.coerceIn(-100, 100),
        loopStartFrame = loopStartFrame.coerceAtLeast(0L),
        loopEndFrame = loopEndFrame.coerceAtLeast(0L),
        attackMs = attackMs.coerceIn(0, 5000),
        decayMs = decayMs.coerceIn(0, 5000),
        sustain = sustain.coerceIn(0f, 1f),
        releaseMs = releaseMs.coerceIn(1, 5000),
        chokeGroup = chokeGroup.coerceIn(0, 32),
        fileName = fileName.substringAfterLast('/').substringAfterLast('\\')
    )
}

enum class EffectType {
    GAIN, EQ3, COMPRESSOR, LIMITER, NOISE_GATE, DE_ESSER, REVERB, DELAY, CHORUS, FLANGER,
    DISTORTION, HIGH_PASS, LOW_PASS
}
