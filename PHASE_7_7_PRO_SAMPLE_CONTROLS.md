# Almus Studio — Phase 7.7: Professional Sampler Controls

Phase 7.7 extends the Phase 7.6 offline sample-based drum engine with non-destructive sampler controls.

## Added
- Per-sample velocity range editing (velocity layers)
- Add velocity layer mapping for the same MIDI drum pitch
- Start/end trim metadata
- Fade-in/fade-out sample envelopes
- Reverse playback
- Optional peak normalization during playback
- Gain and stereo pan controls
- In-app Sample Editor dialog
- Existing project-local sample library and fallback procedural drums retained
- JSON persistence retained through existing `DrumSample`/`DrumKit` models
- Undo/persist support through the existing ViewModel project workflow

## Playback
Sample playback now honors trim, reverse, fades, normalization, gain and pan metadata. Missing/invalid samples still fall back to the procedural drum engine.

## Verification
- Modified Kotlin source files passed bracket/parenthesis static-balance checks.
- ZIP integrity checked with `unzip -t`.
- A full Android APK build was not run in this environment.
