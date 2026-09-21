# Almus Studio — Phase 7.6: Sample-Based Drum Kit Engine

## What was added
- Offline project-local `samples/` library.
- `DrumKit` and `DrumSample` persistent project models.
- WAV sample import from the Android file picker; non-WAV audio is decoded to PCM16 WAV using Android MediaCodec.
- Per-drum MIDI pitch mapping (GM drum pitches such as Kick 36, Snare 38, etc.).
- Velocity-range sample mapping foundation.
- Per-sample gain and pan.
- Non-destructive start/end trim metadata.
- In-memory LRU WAV PCM cache (32 samples) for repeated drum hits.
- Sample playback in the internal MIDI engine with procedural fallback when a sample is unavailable.
- Stereo internal MIDI output so drum sample pan is audible.
- Sample playback length is scheduled from the imported sample/trim region instead of being cut to one sequencer step.
- Drum-machine UI now has a `Load sample` action for each drum row and shows `Sample ✓` when a mapping exists.
- Existing pattern bank, swing, probability, accents, randomization, chaining, persistence, MIDI output and GitHub Actions remain intact.

## Offline architecture
Project data stays under the app's project directory:

`Projects/<projectId>/project.json`

`Projects/<projectId>/audio/`

`Projects/<projectId>/samples/`

No cloud service or network dependency was introduced.

## Validation
- Kotlin source brace/parenthesis balance checked.
- Added `SampleDrumTest` for sample normalization and kit mapping persistence logic.
- ZIP integrity verified with `unzip -t`.
- A full Android/Gradle APK build was **not** run in this environment because the project does not include a Gradle wrapper executable and a system Gradle installation is unavailable.
