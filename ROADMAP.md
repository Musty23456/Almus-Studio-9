# Roadmap

Phase 1, Phase 2, most of Phase 3, and the first slice of Phase 4 (this
repository's current state) are described in the main
[README.md](README.md). The items below are not yet implemented; they're
recorded here so scope is explicit and nothing is silently claimed as done
before it is.

## Phase 3 — remaining items

- Step-sequencer drum machine + basic sample playback engine, reusing the
  existing clip-scheduling machinery in `AlmusAudioEngine` with very short,
  looping clips.
- Piano roll / MIDI-style note grid for programming melodic patterns.
- Track volume automation (the `EffectSettings`/track model would grow an
  automation lane type; the mixer would interpolate gain per-buffer instead
  of reading a single atomic).
- MIDI file import/export for patterns.
- Remaining effects not in Phase 2's fixed-slot chain: limiter, noise gate,
  chorus, flanger, distortion, saturation, time stretching.
- Vibrato preservation in pitch correction beyond what the retune-speed
  smoothing already gives you (no explicit vibrato detection/exclusion).
- Formant-preserving pitch shifting (both the offline and real-time pitch
  correctors change formants along with pitch, audible as a "chipmunk/deep
  voice" effect on larger corrections).

## Phase 4 — remaining items

- **Lock-free cross-thread audio** for the live pitch monitor (currently a
  mutex-guarded `std::deque` — see `realtime_pitch_monitor.h` for why this
  is an honest simplification rather than a fully real-time-safe design).
- Cache effect filter coefficients (recompute only when a parameter actually
  changes, instead of every process() call) -- a real but minor CPU-usage
  optimization, not a correctness fix; see `track_effects.h`.
- A dedicated, independent export playhead (removing the current "briefly
  borrow the live playhead" approach documented in `audio_engine.cpp`) so
  exporting no longer risks a few frames of jitter on live playback.
- Anti-aliased resampling (the Phase 2 resampler is linear-interpolation
  only, adequate but not broadcast-quality — see `wav_file.h`).
- Replace the fixed 10ms UI polling loop with an event-driven meter update
  where practical, to reduce battery use.
- Project autosave/recovery after a crash or forced app kill.
- Broader device testing (matrix of sample rates, framesPerBurst values,
  Bluetooth vs. wired monitoring latency).
- Persisting the A-B loop region and timeline selection into the project
  file (currently in-memory UI state only, cleared when you leave the
  project).
- Pinch-to-zoom (the current zoom is +/- buttons only).
- Waveform snapping/magnetism when dragging clips (currently free-form drag,
  no snap-to-grid or snap-to-other-clip-edges).



## Phase 5.1 — Professional Timeline Pass
- Professional filled min/max waveform envelope with cached peaks
- Bar/beat/quarter-beat visual grid subdivisions
- Quarter-beat snap for clip movement
- Persistent timeline playhead overlay
- Improved ruler typography and timeline rail
- GitHub Actions debug APK build remains mandatory

## Phase 5.2 — Professional Clip Editing Pass
- Selected clip visual state and stronger waveform envelope rendering
- Visual fade boundaries on clip waveforms
- Per-clip gain control (-24 dB to +12 dB) synchronized with the native audio engine
- Higher-resolution cached waveform envelopes
- Preserve GitHub Actions as the required APK build path

## Phase 5.3 — Professional Timeline Interaction
- Pinch-to-zoom timeline (0.25x–8x) with mouse/button zoom fallback.
- Draggable playhead for precise frame-level seeking.
- Extended zoom-aware timeline ruler/canvas width.
- Selected clip border emphasis.
- Visible fade curves matching the native linear fade processing.
- Existing snap, trim, move, duplicate, clip gain, waveform caching and GitHub Actions APK build remain enabled.


## Phase 6 — Professional Voice Recording & Monitoring
- Recording is aligned to the current timeline playhead instead of always starting at frame 0.
- Starting a recording from a stopped/paused transport automatically starts playback so the vocal take stays aligned with the project timeline.
- Input gain control (-24 dB to +24 dB) is applied before monitoring and writing the WAV.
- Live input peak history is exposed to the UI as a lightweight waveform-style meter.
- Input clipping indication is shown in the transport.
- Record-arm workflow remains per-track and the resulting take is inserted at the exact record start frame.
- GitHub Actions remains the required APK build path.


## Phase 6.1 — Advanced Vocal Recording
- Beat-synchronised 0/1/2/4 beat count-in (monitoring-only).
- Pre-roll behavior starts transport before the take and captures the exact post-count-in playhead frame.
- Count-in never enters the recorded WAV.
- Pending recording can be cancelled safely.
- Existing input gain, clipping meter, live peak history, monitoring and exact timeline placement remain intact.


## Phase 6.3 — Vocal Recording Quality & Device Control
- Android input-device selection for the next recording stream.
- Native input sample-rate/channel/burst diagnostics.
- Active input buffer-size control using the device native burst size.
- Low-latency exclusive-input attempt with Shared fallback.
- Direct-monitoring safety guidance for headphones.
- Input gain, clipping detection and live peak history retained from Phase 6.
- GitHub Actions APK build remains mandatory.


### Phase 6.5 — Professional Vocal Editing
- Slip editing
- Crossfade with previous clip
- Ripple delete
- Vocal fade preset support
- GitHub Actions remains mandatory

## Phase 6.6 — Vocal Cleanup & Precision Editing
- Offline WAV silence/audible-region analysis (10 ms windows).
- Conservative -45 dB threshold and 120 ms minimum-silence default.
- Padding around audible regions to preserve consonants and breath tails.
- Non-destructive vocal silence cleanup by creating source windows instead of rewriting the WAV.
- Undo/redo remains project-snapshot based.
- Existing slip, split, crossfade, ripple delete, fades, gain and take comping remain available.


### Phase 6.7
- Professional vocal mixing foundation
- Noise gate with threshold/attack/release
- De-esser with threshold/reduction
- Persisted per-track settings
- Native DSP integration
- GitHub Actions APK build preserved

## Phase 6.8 — Professional Vocal Monitoring & I/O (2026-09-21)
- Dedicated microphone monitoring stream independent of recording.
- Monitor-only mode does not create or modify a WAV take.
- Shared/exclusive Oboe input fallback retained.
- Input buffer sizing and selected input device continue to apply to monitoring.
- Live input meter/peak history remains available while monitoring.
- Existing pitch-corrected monitoring remains monitoring-only and is never baked into recordings.
- Recording still owns the input stream when an actual take is being captured.
- GitHub Actions Android APK workflow remains mandatory.

## Phase 6.9 — Vocal Mixer & Routing Foundation
- [x] Dedicated mixer UI
- [x] Master fader and stereo peak meter
- [x] Persistent Reverb Send and Delay Send controls
- [x] Mixer Mute/Solo controls
- [x] Preserve GitHub Actions APK workflow
- [x] Native aux send/return buses


## Phase 6.10 — Native Vocal Aux Bus & FX Return
- Native post-fader Reverb Send -> Reverb Bus -> wet Return -> Master.
- Native post-fader Delay Send -> Delay Bus -> wet Return -> Master.
- Thread-local aux DSP state keeps realtime and offline render paths isolated.
- Kotlin project send values are now pushed to the native audio engine.

## Phase 6.11 — FX Return Controls
- Dedicated native Reverb Return and Delay Return volume controls.
- Native return mute/solo commands and persistent project state.
- Mixer signal-flow UI: track sends -> shared FX returns -> master.


## Phase 6.12 — Vocal Automation
- Non-destructive automation points stored in project JSON.
- Native sample-position interpolation for track volume, pan, reverb send, and delay send.
- Native master/reverb-return/delay-return automation lanes.
- Automation survives project reload and undo/redo snapshots.
- Automation UI exposes per-track lanes and point insertion at the current playhead.
- GitHub Actions Android build remains mandatory.

### Phase 6.13 — Vocal Automation Lane Editor
Visual automation curves, draggable points, and point deletion are now available in the vocal timeline. The next vocal milestone is precision automation polish and final vocal workflow validation before Phase 7.


## Phase 6.14 — Vocal Automation Precision
- Atomic automation-point moves with a single undo snapshot.
- Timeline-grid snapping for automation points, following the existing Snap toggle.
- Collision-safe point replacement when a dragged point lands on an existing frame.
- Automation lane separators and clearer editing affordance.
- Native automation state is rebuilt after each committed point move.


## Phase 6.15 — Vocal Mix Snapshots
- Named non-destructive mixer snapshots for vocal tracks.
- Saves track volume/pan/mute/solo and Reverb/Delay sends plus return/master state.
- Recall is undoable and does not alter clips or automation lanes.


## Phase 6.17 — Vocal Mastering / Final Polish
- Native zero-latency master limiter with configurable ceiling and release.
- Final master peak protection and clipping guard.
- Momentary and short-term final-output loudness meters.
- Persistent master limiter settings in the project.
- GitHub Actions APK build workflow remains required.


## Phase 7.0 — MIDI Engine Foundation
- MIDI track model and persistent MIDI clips/notes
- Standard MIDI File (SMF) type 0/1 import/export foundation
- Android MIDI device discovery foundation
- MIDI clip/note editing entry points in Studio UI
- Backward-compatible project JSON defaults
- GitHub Actions APK build workflow retained

## Phase 7.1 — Piano Roll Core
- [x] Piano roll grid and keyboard
- [x] Note drawing, selection, movement and deletion
- [x] Velocity editing foundation
- [x] 1/4, 1/8 and 1/16 snapping
- [x] Horizontal/vertical scrolling and zoom controls
- [x] Persistent MIDI note edits
- [x] JVM quantization tests
- [ ] MIDI playback/virtual instruments (next phases)


## Phase 7.2 — MIDI Playback Engine + Virtual Instrument Foundation
- Project MIDI notes are scheduled from the native AudioEngine playhead.
- Android MIDI output device connection and MIDI Note On/Off + Program Change are supported.
- Added a lightweight built-in polyphonic sine synth for offline/local MIDI auditioning.
- Studio transport automatically starts/stops MIDI playback with project playback.
- This is a playback foundation; advanced samplers, multi-sample instruments, envelopes, and full GM sound banks remain future work.


## Phase 7.3 — Professional MIDI Instrument Foundation
- Four built-in waveforms: Sine, Square, Saw, Triangle.
- ADSR envelope controls.
- Octave transpose and synth gain.
- MIDI instrument presets: Sine, Pluck, Bright, Pad.
- Per-MIDI-track patch settings persisted in project JSON.
- Velocity-sensitive polyphonic playback retained.

### Phase 7.4 — MIDI Drum Machine + Drum Sequencer
- [x] Persistent 16-step drum pattern model
- [x] Drum sequencer UI
- [x] MIDI channel 10 drum playback foundation
- [x] Internal procedural drum voices
- [ ] Sample-based drum kits
- [ ] Swing/groove and velocity lane
- [ ] Pattern chaining and song arrangement

## Phase 7.11 — Advanced Sampler Performance Engine
- Persistent pitch-bend control with configurable 1–24 semitone range.
- Modulation mapped to smooth sampler vibrato.
- Aftertouch expression mapped to post-note amplitude pressure.
- Velocity-to-pitch expression in cents.
- Expression applies to chromatic sample playback.
- Sampler samples can now render on ordinary MIDI sampler channels, not only channel 10.
- GitHub Actions workflow retained; full Android build must be verified in CI.


## Phase 7.12
Live MIDI Performance Engine: controller input, live sampler triggering, pitch bend, CC1 modulation, CC11 expression, CC64 sustain, aftertouch, and MIDI panic.


## Phase 7.13
MIDI Recording & Capture: live MIDI note/CC/pitch-bend/aftertouch capture, quantization, overdub, punch filtering, and recording controls.


## Phase 7.20 — Formant-Aware AutoPitch
LPC source/filter vocal processing, formant-preserving pitch shifting, real-time/offline integration, and a Formant Preservation control.

## Phase 7.21 — Professional AutoPitch Preset Engine
- Central AutoPitch preset catalog
- Essentials / Hip Hop / Hyperpop / Sci-Fi category filtering
- Classic / Natural / Third / Duet / Big Harmony / Heaviest / Sci-Fi presets
- Presets apply all currently supported DSP parameters
- Preset engine unit tests
