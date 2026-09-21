# Almus Studio

An offline-only Android multitrack audio production app. No account, no
cloud, no network permission requested anywhere in the app — everything
(recording, playback, mixing, project storage) runs entirely on-device.

This repository currently contains the original audio foundation plus the completed MIDI/sampler phases through **Phase 7.11**. The sampler now includes chromatic playback, professional sample controls, drum-kit management, and expression/performance controls.

The historical roadmap below remains the detailed implementation record.

This repository originally implemented **Phase 1, Phase 2, most of Phase 3, and
a first slice of Phase 4** of the roadmap in [`ROADMAP.md`](ROADMAP.md): a
working multitrack recorder/player, a real per-track effects chain (EQ,
high/low-pass, compressor, delay, reverb), clip split/copy/cut/paste/fade/
drag-move/drag-trim, sample-rate-aware import, export driven by the
project's actual length, per-track colors, undo/redo, a metronome, timeline
zoom, an A-B loop, and both offline and real-time vocal pitch correction. It
is a genuinely useful DAW foundation, not a finished professional one — see
[Known limitations](#known-limitations-be-technically-honest) and
[Vocal pitch correction](#vocal-pitch-correction-be-technically-honest)
below before you rely on it for real work.

## Why these technologies

| Concern | Choice | Why |
|---|---|---|
| App language | Kotlin + Jetpack Compose | Modern, less boilerplate than XML/Views for a UI this state-heavy (playhead, meters, per-track controls all changing constantly); first-class coroutines for the UI polling loop. |
| Real-time audio | C++ (NDK) + [Oboe](https://github.com/google/oboe) | Kotlin/JVM audio APIs (AudioTrack/AudioRecord via Java) add GC pauses and scheduling jitter that are audible as glitches in multitrack playback. Oboe wraps AAudio (API 27+) / OpenSL ES (API 26) and picks the best one automatically, giving the lowest latency Android currently offers without hand-rolling both backends. |
| Cross-thread audio control | A lock-free SPSC command queue (`command_queue.h`) | The Oboe callback runs on a real-time thread — it must never block on a mutex, allocate, or do I/O, or the OS can starve it and you hear a glitch. Every UI/JNI call *enqueues* a change instead of mutating engine state directly. |
| Project storage | Local JSON (Moshi) under `getExternalFilesDir()` | No database is warranted for the current schema (a handful of tracks/clips per project); JSON is trivially inspectable/debuggable, and `getExternalFilesDir()` needs no runtime permission on API 26+ while still being visible to a file manager or connected computer — unlike internal-only storage. |
| Audio file format | WAV (PCM/float) | Simple to decode, encode, and reason about without a codec license question; MP3/AAC encoding is left for a later export-quality phase (see roadmap). |

## Architecture

```
Kotlin/Compose UI  →  StudioViewModel  →  AudioEngine (JNI, Kotlin object)
                                              │  enqueue-only calls
                                              ▼
                                    CommandQueue (lock-free SPSC ring buffer)
                                              │  drained at top of each callback
                                              ▼
                              AlmusAudioEngine (C++, owns Oboe streams)
                               ├─ Output stream callback → mixes tracks/clips
                               └─ Input stream callback  → writes WavWriter
```

- **UI thread**: Compose screens + `StudioViewModel`. Polls playhead/meters
  every 50ms (`StateFlow`s) rather than the audio thread pushing to it, since
  the audio thread must never call back into the JVM per-buffer.
- **JNI bridge** (`jni_bridge.cpp`): translates each `AudioEngine.kt` external
  function into either a synchronous read (e.g. `getPlayheadFrame`) or an
  enqueued `Command`.
- **Real-time audio thread**: owned entirely by Oboe. `drainCommands()` runs
  first in every callback (cheap, bounded, lock-free), then `mixInto()` sums
  every unmuted track's active clips into the output buffer.
- **Structural changes** (add/remove track or clip) briefly take a
  `std::mutex` — *not* on the per-sample hot path, only around the handful of
  vector insert/erase calls — so the offline exporter can safely read
  `tracks_` from its own thread. This is documented in `audio_engine.h`.
- **Per-track effects** (`app/src/main/cpp/track_effects.h` + `dsp/`): each
  track sums its active clips into a thread-local scratch buffer, runs that
  buffer through its `EffectChain` (EQ → high-pass → low-pass → compressor →
  delay → reverb, in that fixed order), *then* applies track volume/pan into
  the master mix. Effects process a track's full signal, not each clip in
  isolation. The chain is a fixed set of slots (not a generic plugin system)
  — see `effect_chain.h`.
- **Concurrency hazard, by design, mitigated in the UI**: `mixInto()` runs on
  both the real-time output thread and the offline-export worker thread. The
  per-buffer scratch mixing buffer is `thread_local` so those two threads
  can't corrupt each other's mix — but each track's persistent effect state
  (filter history, delay lines, reverb tail) is *not* duplicated per thread,
  so exporting while also playing live would have both threads mutating the
  same effect state concurrently. `StudioViewModel.play()` refuses to start
  playback while an export is in progress specifically to avoid this; see
  the comment above `play()`.

## Resampling and clip fades

- **Sample-rate mismatches are now corrected**, not just documented as a
  known issue: `AudioEngine.scheduleClip` resamples (linear interpolation,
  not anti-aliased — see `wav_file.h`) to the project's sample rate before
  scheduling.
- **Clips support linear fade-in/fade-out** (`AudioClip.fadeInFrames` /
  `fadeOutFrames`), editable from the clip-tap dialog in `StudioScreen.kt`,
  applied per-sample in `mixInto()`.
- **Splitting a clip** creates two clips referencing the same underlying WAV
  file at different offsets — no audio data is duplicated on disk.

## Vocal pitch correction (be technically honest)

Almus Studio has an offline vocal pitch correction feature, reachable from a
clip's action dialog ("Pitch correction…"). Read this before relying on it:

- **This is not Auto-Tune.** It's autocorrelation-based pitch detection
  (`dsp/pitch_correction.h`) plus a granular resample pitch shifter with
  overlap-add resynthesis. There is no formant preservation, so larger
  corrections shift the voice's timbre along with its pitch (audible as a
  "chipmunk" or "deepened" quality) — a real, known limitation of this
  technique, not a bug.
- **Offline only, on a whole clip** — you pick a clip, choose a key/scale,
  strength, and retune speed, and it processes and replaces that clip's
  audio. There's no real-time "sing and hear it corrected live" monitoring;
  that would need this same detection running fast enough, and well enough,
  inside the real-time audio callback, which is a substantially harder
  problem left for a later phase (see ROADMAP.md).
- **Works best on a single, clearly-pitched voice.** Polyphonic audio (a
  chord, multiple singers) or unvoiced sounds (breath, consonants, sibilance)
  aren't "corrected" — the pitch detector reports them as unvoiced and passes
  them through unchanged, rather than guessing and distorting them.
- **"Hard mode"** snaps close to instantly; the default ("natural") mode
  smooths the correction over the chosen retune-speed window so sustained
  notes glide into pitch rather than snapping abruptly.

## Track colors, cut/copy/paste, and undo/redo

- Each track gets a distinct color from a fixed palette (`TrackColors.kt`)
  when created, shown as a stripe on its header and used for its clips'
  waveforms in the timeline.
- Clip actions include copy, cut, and paste (at the playhead, on whichever
  track you tap the paste icon on) alongside split/fade/delete/pitch-correct.
  Copy/cut only hold one clip at a time — copying a second clip replaces the
  first in the clipboard.
- **Undo/redo is whole-project-snapshot based**, not per-field: every
  discrete edit (add track, mute/solo, delete/split/paste a clip, apply
  effects, apply pitch correction) pushes the *previous* project state onto
  an undo stack (capped at 50 steps) before applying the change, and
  undo/redo fully tears down and rebuilds the native engine's track/clip
  state to match the snapshot (`attachProjectToEngine` in
  `StudioViewModel.kt`) rather than trying to compute an inverse for every
  possible edit. Volume/pan slider drags are a deliberate exception: only
  the value when you release the slider is undoable, not every intermediate
  value while dragging — otherwise one drag would fill the entire undo
  history in a fraction of a second.

## Live pitch monitoring, metronome, drag editing, zoom, and A-B loop

- **Live pitch-corrected monitoring** (mic icon in the transport bar, long-press
  for key/scale/strength settings): while recording, you can hear a
  pitch-corrected version of your voice through headphones. This is a
  *separate, simpler* algorithm from the offline corrector — a real-time
  granular pitch shifter (`dsp/realtime_pitch_monitor.h`) using back-to-back
  grains with a short crossfade rather than full overlap-add, because that's
  what's tractable to run continuously with bounded latency. Expect
  noticeably more grain-boundary artifacts than the offline version, roughly
  20-30ms of added latency, and **use headphones** — monitoring through the
  phone's speaker will feed back into the microphone. The corrected signal
  is monitoring-only: what gets written to the recorded file is always the
  dry, uncorrected input, so you can apply the (better-quality) offline
  correction afterward if you want it baked in.
- **Metronome** (clock icon in the transport bar): a synthesized click, not a
  sample. Plays only through live monitoring — it is never written into a
  recording or included in an export.
- **Drag editing on the waveform**: drag the middle of a clip to move it;
  drag the thin handles at its left/right edges to trim. Each completed drag
  is one undo step (see `TrackTimelineLane` in `StudioScreen.kt`), not one
  step per pixel moved.
- **Zoom**: +/- buttons below the timeline scale how many pixels represent a
  beat, from 0.25x to 4x. Pinch-to-zoom isn't implemented yet (see
  ROADMAP.md).
- **Selection + A-B loop**: drag along the ruler to select a time range
  (shown as a highlighted band across all tracks), then "Set loop from
  selection" to loop playback between those two points (native support via
  `AudioEngine.setLoopRegion`, already used here for the first time). The
  selection and loop are in-memory UI state — they reset when you leave the
  project rather than being saved into it.

## Settings

A minimal in-app Settings screen (gear icon on the home screen) shows the
fixed audio engine configuration (sample rate, backend, sharing-mode
fallback) and a short reminder that everything stays on-device. There's
nothing to configure yet beyond viewing this — no per-project audio settings
UI, since the engine's sample rate is currently fixed at initialization.

## Known limitations (be technically honest)

Per the project's own requirement to never claim more than is implemented:

- **Effects are a fixed set of six**, not a generic plugin system: EQ (3-band),
  high-pass, low-pass, compressor, delay, reverb. Limiter, noise gate,
  chorus, flanger, distortion, saturation, pitch shifting, and time
  stretching are **not implemented** (see `ROADMAP.md` Phase 3).
- **Filter coefficients are recomputed every audio callback** rather than
  cached and only recomputed when a parameter changes. Correct, just not
  maximally efficient — a noted Phase 4 optimization, not a bug.
- **Resampling is linear-interpolation only** (no anti-aliasing filter) —
  correct pitch/speed, but not broadcast-quality for large sample-rate
  changes.
- **No waveform-editing gestures** — split/delete/fade happen through a
  dialog after tapping a clip, not by dragging its edges directly.
- **No auto-tune / pitch correction yet.** Still a data-model placeholder
  only (Phase 3 item).
- **Recording is mono input, mixed to a single monitor track**; the DAW does
  not yet support multi-channel audio interfaces explicitly (Oboe will use
  whatever the OS reports as the default input).
- **Exporting while transport is also playing is disallowed by the UI**
  (the Play button is disabled during export), because both would otherwise
  drive the same per-track effect state from two threads at once — see the
  "Concurrency hazard" note above.

None of the above are silently stubbed with fake UI — the corresponding
controls simply aren't in the app yet, or (for effects not in the six above)
have no data model at all rather than an inert one.

## Repository layout

```
app/
  src/main/java/com/almus/studio/
    audio/        Kotlin JNI wrapper, waveform analyzer, time-conversion helpers
    data/         Project/Track/AudioClip models + local JSON repository
    ui/           Compose screens, theme, reusable components
    viewmodel/    StudioViewModel (single source of UI truth)
  src/main/cpp/    Native Oboe-based multitrack engine (see architecture above)
  src/test/        JVM unit tests (no device/emulator needed)
.github/workflows/ CI: build + test + APK artifact upload
```

## Building the APK

### Locally

You need Android Studio (Koala or newer) with the NDK and CMake components
installed (Settings → Languages & Frameworks → Android SDK → SDK Tools →
check "NDK (Side by side)" and "CMake"), or a system `gradle` install (8.7+).

```bash
# One-time, if you don't already have gradlew: generates the wrapper jar/scripts.
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Via GitHub Actions

Push to `main` or open a pull request; `.github/workflows/android-build.yml`
will:

1. Check out the repo.
2. Install JDK 17 and the Android SDK.
3. Provision Gradle 8.7 (no committed wrapper jar required).
4. Run `gradle test`.
5. Run `gradle assembleDebug` and upload the APK as a workflow artifact
   named `almus-studio-debug-apk`.
6. Optionally build and upload a signed release APK **only if** the repo has
   `ALMUS_RELEASE_KEYSTORE_BASE64`, `ALMUS_RELEASE_KEYSTORE_PASSWORD`,
   `ALMUS_RELEASE_KEY_ALIAS`, and `ALMUS_RELEASE_KEY_PASSWORD` configured as
   repository secrets. No signing key is ever committed to the repository.

Download the APK from the workflow run's "Artifacts" section.

## Installing and testing on an Android phone

1. On your phone: Settings → About phone → tap "Build number" 7 times to
   enable Developer Options, then Settings → Developer options → enable
   "USB debugging".
2. Connect the phone via USB and accept the "Allow USB debugging?" prompt.
3. From a computer with the APK downloaded:
   ```bash
   adb install app-debug.apk
   ```
   or copy the APK to the phone and open it directly (you'll need to allow
   "install unknown apps" for whichever app you copied it with).
4. Launch **Almus Studio**, tap **New Project**, then tap the record button —
   you'll be prompted for microphone permission the first time.
5. For a quick multitrack test: create two tracks, import a WAV file (or
   record one) into each via the import icon on a track's timeline lane, then
   hit play. You should hear both mixed together, and can adjust each
   track's volume/pan slider live during playback.

A physical device is strongly recommended over an emulator for anything
audio-related — emulator audio timing does not reflect real-world latency,
which matters a great deal for a DAW.

## Running tests

```bash
./gradlew test          # JVM unit tests (project model, time conversion)
./gradlew connectedCheck  # instrumented tests, requires a connected device/emulator
```

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) for Phases 2–4 (editing/effects, beat maker &
MIDI & offline vocal pitch correction, and professional refinement).

## Phase 5 — Pro Timeline & Waveform Core
- Beat-grid snapping for clip movement (toggleable from the Studio toolbar)
- Clip duplication
- Cached waveform peak envelopes to reduce repeated WAV decoding
- Higher-resolution, professional min/max waveform rendering with centerline/detail accents
- GitHub Actions APK build remains mandatory

### Phase 5.3 timeline improvements
- Pinch-to-zoom timeline interaction up to 8x.
- Draggable playhead for precise seeking.
- Zoom-aware timeline canvas width.
- Stronger selected-clip visual state and fade-curve visualization.
- GitHub Actions APK build remains the required build path.


### Phase 6.1 recording workflow
Recording supports an optional 1/2/4-beat count-in. The count-in is monitoring-only; the recorded take begins at the exact playhead frame after the count-in.


### Phase 6.3 recording improvements
The recording path now exposes Android input-device selection for the next take, native input sample rate/channel/burst diagnostics, and active input buffer-size control. The native Oboe input stream attempts low-latency Exclusive mode and falls back to Shared mode when the device does not support it. Direct monitoring should be used with headphones to avoid acoustic feedback.


### Phase 6.4 recording safety & recovery
- Streaming recording sessions create an atomic `recording_session.json` sidecar.
- If Android/app process death interrupts a take, the next launch scans recovery sessions, repairs the float32 WAV RIFF/data sizes, and restores the recovered clip into its original track.
- Normal successful recording clears the sidecar only after the project has been updated.
- Recovery preserves punch-take group metadata where available.
- `ViewModel.onCleared()` stops native recording without deleting the recovery metadata.
- GitHub Actions remains the mandatory APK build path.


## Phase 6.5 — Professional Vocal Editing
- Non-destructive slip editing (source-window movement)
- One-tap crossfade with the previous clip
- Ripple delete to close vocal timeline gaps
- Existing split/trim/move/fade/gain/take comping preserved
- GitHub Actions APK build workflow preserved


## Phase 6.7 — Professional Vocal Mixing

Added native vocal channel processing for **Noise Gate** and a lightweight **De-Esser**, with persistent per-track settings and Compose controls. Existing EQ, filters, compressor, delay and reverb remain in the chain. Processing is real-time and non-destructive. GitHub Actions APK build remains the required build path.

### Phase 6.8 — Professional Vocal Monitoring & I/O
Almus Studio now supports a dedicated microphone monitoring mode that opens the input stream without creating a recording file. This lets a vocalist test the mic, input gain, input meter, and live monitoring chain before pressing Record. Recording still uses the same low-latency Oboe input path and remains separate from monitor-only capture.

## Phase 6.9 — Vocal Mixer & Routing Foundation
- Added a dedicated Mixer bottom sheet for vocal projects.
- Added persistent per-track Reverb Send and Delay Send levels (-60 dB to 0 dB).
- Added persistent master fader (-60 dB to +6 dB) with live stereo master peak display.
- Mute/Solo controls are available from the mixer surface.
- Existing track Volume, Pan, FX and record-arm workflow remain intact.
- Send values are routed through native post-fader aux buses: Reverb Send -> Reverb Bus -> wet Return -> Master, and Delay Send -> Delay Bus -> wet Return -> Master.
- GitHub Actions APK workflow remains part of the project.



## Phase 6.10 — Native Vocal Aux Bus & FX Return
Native post-fader Reverb and Delay sends now feed dedicated native C++ aux buses and wet returns before the master stage. Kotlin project send values are pushed into the real-time command queue, and thread-local aux DSP state keeps live playback and offline export histories isolated.


### Phase 6.12 Automation
Automation is now non-destructive and persisted in the project. Track volume/pan/reverb-send/delay-send lanes are evaluated natively at the audio sample position; master and shared return lanes have the same native interpolation foundation.

## Phase 6.14 — Vocal Automation Lane Editor
- Visual automation curves are rendered directly over each vocal timeline lane.
- Volume, pan, reverb-send, and delay-send lanes are visible with draggable points.
- Long-pressing a point removes it; changes remain non-destructive and persist in project JSON.
- Automation edits remain undoable through the existing project history.
- GitHub Actions Android build workflow remains mandatory.


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

## Phase 6.18 — Vocal Final QA

Phase 6.18 is a regression/QA pass over the vocal-first pipeline: recording recovery, non-destructive clip editing, take comping, cleanup analysis, mixer routing, automation, vocal mix snapshots, master protection, and WAV delivery. It adds regression tests for the project data path and updates the Android version to `0.6.18-phase6`.

The project remains offline-first and keeps GitHub Actions as the required Android build path.


## Phase 7.0 — MIDI Engine Foundation
- MIDI track model and persistent MIDI clips/notes
- Standard MIDI File (SMF) type 0/1 import/export foundation
- Android MIDI device discovery foundation
- MIDI clip/note editing entry points in Studio UI
- Backward-compatible project JSON defaults
- GitHub Actions APK build workflow retained

## Phase 7.1 — Piano Roll Core
- Real Compose piano-roll editor for MIDI clips.
- 128-note vertical keyboard/grid with note rectangles.
- Tap empty grid to draw snapped notes.
- Drag notes to move them in pitch/time.
- Select notes and edit velocity or delete them.
- 1/4, 1/8 and 1/16-note snapping.
- Horizontal/vertical scrolling and independent X/Y zoom controls.
- MIDI edits remain persisted in project JSON and participate in undo/redo.


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

## Phase 7.4 — MIDI Drum Machine + Step Sequencer
- 16-step drum pattern foundation with Kick, Snare, Clap, Closed/Open Hat, Tom, Crash and Percussion rows.
- Drum patterns persist inside MIDI tracks as project JSON.
- Drum steps are rendered through MIDI channel 10 (zero-based channel 9) and the internal synth includes lightweight procedural drum voices.
- Preview uses the existing MIDI playback transport.


## Phase 7.12
Live MIDI Performance Engine: controller input, live sampler triggering, pitch bend, CC1 modulation, CC11 expression, CC64 sustain, aftertouch, and MIDI panic.


## Phase 7.13
MIDI Recording & Capture: live MIDI note/CC/pitch-bend/aftertouch capture, quantization, overdub, punch filtering, and recording controls.


## Phase 7.20 — Formant-Aware AutoPitch
LPC source/filter vocal processing, formant-preserving pitch shifting, real-time/offline integration, and a Formant Preservation control.

## Phase 7.21 — Professional AutoPitch Preset Engine

Phase 7.21 centralizes AutoPitch presets and wires preset selection to correction strength, retune speed, hard mode, harmony mode/mix/width, and formant preservation. The UI now uses the preset catalog for Essentials, Hip Hop, Hyperpop, and Sci-Fi categories.
# Almus-Studio-9
