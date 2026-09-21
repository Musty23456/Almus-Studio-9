package com.almus.studio.audio

/**
 * Thin Kotlin wrapper around the native (C++/Oboe) multitrack audio engine.
 *
 * Design note: every method here that touches audio state ends up on a
 * lock-free command queue inside the native engine (see audio_engine.cpp).
 * Nothing in this class blocks the Oboe real-time callback -- Kotlin/JNI
 * calls only ever *enqueue* a change; the audio thread applies it between
 * buffers. This is what keeps the UI thread and the audio thread decoupled.
 */
object AudioEngine {

    init {
        System.loadLibrary("almus_audio")
    }

    // --- Engine lifecycle -------------------------------------------------

    /** Must be called once before any other method, typically from onCreate(). */
    external fun nativeInit(sampleRate: Int, framesPerBurst: Int)

    external fun nativeShutdown()

    // --- Track management ---------------------------------------------------

    /** Registers a track in the native mixer; returns a native track handle. */
    external fun addTrack(trackId: String): Int

    external fun removeTrack(trackHandle: Int)

    external fun setTrackVolumeDb(trackHandle: Int, db: Float)

    external fun setTrackPan(trackHandle: Int, pan: Float)

    external fun setTrackMuted(trackHandle: Int, muted: Boolean)

    external fun setTrackSolo(trackHandle: Int, solo: Boolean)

    // --- Clip scheduling ------------------------------------------------------

    /**
     * Schedules a decoded WAV file to play on [trackHandle] starting at
     * [startFrame] (project timeline position, in frames at engine sample rate).
     * If the file's sample rate differs from the project's, it is resampled
     * (linear interpolation) before scheduling -- see wav_file.h on the native
     * side. Returns a native clip handle, or -1 if the file could not be decoded.
     */
    external fun scheduleClip(
        trackHandle: Int,
        filePath: String,
        startFrame: Long,
        sourceOffsetFrames: Long,
        lengthFrames: Long,
        gainDb: Float,
        looping: Boolean,
        fadeInFrames: Long = 0,
        fadeOutFrames: Long = 0
    ): Int

    external fun removeClip(clipHandle: Int)

    external fun moveClip(clipHandle: Int, newStartFrame: Long)

    // --- Transport --------------------------------------------------------

    external fun play()

    external fun pause()

    external fun stop()

    external fun seekToFrame(frame: Long)

    /** Current playhead position in frames, safe to poll frequently from the UI thread. */
    external fun getPlayheadFrame(): Long

    external fun setLoopRegion(startFrame: Long, endFrame: Long, enabled: Boolean)

    // --- Recording ----------------------------------------------------------

    /**
     * Starts recording microphone input into a new WAV file at [outputFilePath]
     * while it is simultaneously routed to [monitorTrackHandle] for the meter.
     * Returns false if the microphone is unavailable or already in use --
     * call [getLastRecordingError] immediately after a false result for why.
     */
    external fun startRecording(monitorTrackHandle: Int, outputFilePath: String): Boolean

    /** Opens microphone input for monitoring without creating a recording file. */
    external fun startInputMonitoring(): Boolean

    /** Stops monitor-only microphone capture. Safe to call when recording is active (no-op). */
    external fun stopInputMonitoring()

    /** Human-readable reason the most recent [startRecording] call failed, or empty. */
    external fun getLastRecordingError(): String

    external fun pauseRecording()

    external fun resumeRecording()

    /** Stops recording and returns the number of frames written. */
    external fun stopRecording(): Long

    /** Instantaneous input level in dBFS, or Float.NEGATIVE_INFINITY if not recording. */
    external fun getInputLevelDb(): Float

    external fun isInputClipping(): Boolean

    /** Software input gain applied immediately after capture, before monitoring/recording. */
    external fun setInputGainDb(db: Float)

    external fun getInputGainDb(): Float

    /** Selects the Android input device for the next recording stream. -1 means default. */
    external fun setInputDeviceId(deviceId: Int)
    external fun getInputDeviceId(): Int
    external fun getInputSampleRate(): Int
    external fun getInputChannelCount(): Int
    external fun getInputFramesPerBurst(): Int
    external fun getInputBufferSizeFrames(): Int
    external fun setInputBufferSizeFrames(frames: Int): Boolean

    /** Recent input peak history in dBFS, oldest to newest. */
    external fun getRecordingPeakHistory(maxPoints: Int = 128): FloatArray

    // --- Master / metering --------------------------------------------------

    external fun setMasterVolumeDb(db: Float)

    /** Zero-latency master limiter. The limiter runs after the master fader and before metering. */
    external fun setMasterLimiterEnabled(enabled: Boolean)
    external fun setMasterLimiterCeilingDb(db: Float)
    external fun setMasterLimiterReleaseMs(ms: Float)

    /** Returns [momentaryDb, shortTermDb, peakDb] for the final master output. */
    external fun getMasterLoudness(): FloatArray

    /** Peak level in dBFS for [left, right], for the master meter. */
    external fun getMasterPeaksDb(): FloatArray

    // --- Native aux sends --------------------------------------------------
    external fun setTrackReverbSendDb(trackHandle: Int, db: Float)
    external fun setTrackDelaySendDb(trackHandle: Int, db: Float)
    external fun setReverbReturnVolumeDb(db: Float)
    external fun setDelayReturnVolumeDb(db: Float)
    external fun setReverbReturnMuted(muted: Boolean)
    external fun setDelayReturnMuted(muted: Boolean)
    external fun setReverbReturnSolo(solo: Boolean)
    external fun setDelayReturnSolo(solo: Boolean)

    // --- Automation (Phase 6.12) -------------------------------------------
    // parameter: 0=volume dB, 1=pan, 2=reverb send dB, 3=delay send dB
    external fun setTrackAutomationPoint(trackHandle: Int, parameter: Int, frame: Long, value: Float)
    external fun clearTrackAutomation(trackHandle: Int, parameter: Int)
    external fun setMasterAutomationPoint(frame: Long, value: Float)
    external fun clearMasterAutomation()
    external fun setReverbReturnAutomationPoint(frame: Long, value: Float)
    external fun clearReverbReturnAutomation()
    external fun setDelayReturnAutomationPoint(frame: Long, value: Float)
    external fun clearDelayReturnAutomation()

    // --- Effects ------------------------------------------------------------

    /**
     * Pushes a full effect-chain snapshot for [trackHandle] to the native
     * mixer. Fixed-slot rather than generic key/value, matching the native
     * EffectChain struct (see effect_chain.h) -- Phase 2 ships EQ, high/low
     * pass, compressor, delay, and reverb. Limiter, noise gate, chorus,
     * flanger, distortion, and pitch correction are not implemented yet
     * (see ROADMAP.md).
     */
    external fun setEffectChain(
        trackHandle: Int,
        eqEnabled: Boolean, eqLowGainDb: Float, eqMidGainDb: Float, eqHighGainDb: Float,
        highPassEnabled: Boolean, highPassCutoffHz: Float,
        lowPassEnabled: Boolean, lowPassCutoffHz: Float,
        compressorEnabled: Boolean, compressorThresholdDb: Float, compressorRatio: Float,
        compressorAttackMs: Float, compressorReleaseMs: Float,
        noiseGateEnabled: Boolean, noiseGateThresholdDb: Float, noiseGateAttackMs: Float, noiseGateReleaseMs: Float,
        deEsserEnabled: Boolean, deEsserThresholdDb: Float, deEsserReductionDb: Float,
        delayEnabled: Boolean, delayTimeMs: Float, delayFeedback: Float, delayMix: Float,
        reverbEnabled: Boolean, reverbRoomSize: Float, reverbMix: Float
    )

    // --- Offline export -------------------------------------------------------

    /**
     * Renders the project to a WAV file on a background native thread, not the
     * real-time audio thread. [totalFrames] should be the end of the furthest
     * clip on the timeline (computed on the Kotlin side, where the project
     * model lives) so the render stops exactly where the arrangement ends.
     * Progress is reported through [ExportProgressListener]; returns immediately.
     */
    external fun startOfflineExport(outputFilePath: String, totalFrames: Long, listener: ExportProgressListener)

    external fun cancelOfflineExport()

    // --- Offline vocal pitch correction --------------------------------------
    // Deliberately not called "Auto-Tune": autocorrelation pitch detection +
    // a granular resample pitch shifter, with no formant preservation. Real,
    // working, and genuinely useful on monophonic vocal takes -- but not a
    // professional-grade pitch corrector. See ROADMAP.md / README.md.

    /**
     * Processes the WAV at [inputPath] and writes a pitch-corrected copy to
     * [outputPath]. [scaleMask] has bit i set if pitch class (root + i) % 12
     * is an allowed target note (see [MusicalScale]). Runs synchronously --
     * call from a background thread. Returns false if the input couldn't be
     * decoded or the output couldn't be written.
     */
    external fun correctPitch(
        inputPath: String,
        outputPath: String,
        rootPitchClass: Int,
        scaleMask: Int,
        strength: Float,
        speedMs: Float,
        hardMode: Boolean,
        harmonyMode: Int,
        harmonyMix: Float,
        harmonyPan: Float,
        formantCompensation: Float = 0.5f
    ): Boolean

    interface ExportProgressListener {
        fun onProgress(fractionComplete: Float)
        fun onComplete(outputFilePath: String)
        fun onError(message: String)
    }

    // --- Metronome (Phase 4) --------------------------------------------------
    // Click is monitoring-only: it plays through the live output but is never
    // written into recordings or the offline export (see audio_engine.cpp).

    external fun setMetronomeEnabled(enabled: Boolean)
    external fun setTempo(bpm: Float, beatsPerBar: Int)

    // --- Live pitch-corrected monitoring (Phase 4) ----------------------------
    // Only produces sound while the input (recording) stream is open -- there
    // is no separate "just monitor, don't record" mode. Use headphones: this
    // is routed to the live output, so monitoring over a speaker will feed
    // back into the microphone. See RealtimePitchMonitor's doc comment for
    // the honest scope/latency tradeoffs of this feature.

    external fun setMonitoringEnabled(enabled: Boolean)
    external fun setMonitorPitchParams(rootPitchClass: Int, scaleMask: Int, strength: Float, speedMs: Float, hardMode: Boolean, harmonyMode: Int, harmonyMix: Float, harmonyPan: Float, formantCompensation: Float = 0.5f)
    external fun setMonitorVolumeDb(db: Float)

}
