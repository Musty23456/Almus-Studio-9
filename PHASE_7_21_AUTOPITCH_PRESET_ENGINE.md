# Phase 7.21 — Professional AutoPitch Preset Engine

This phase centralizes the AutoPitch preset catalog and wires preset selection to every currently supported DSP parameter.

## Presets
- Classic — balanced correction, no harmony, stronger formant preservation
- Natural — slower/smoother correction with higher formant preservation
- Third — lead + third harmony
- Duet — lead + harmony voice with wider stereo placement
- Big Harmony — lead + third/fifth/octave harmony stack
- Heaviest — hard/fast correction without harmony
- Sci-Fi — hard/fast correction with Big Harmony and wider stereo field

## Controls covered by presets
- Correction strength
- Retune speed
- Hard/instant mode
- Harmony mode
- Harmony mix
- Harmony width
- Formant preservation

## UI
The AutoPitch dialog now filters presets by the four style categories:
Essentials, Hip Hop, Hyperpop, and Sci-Fi.
Selecting a preset applies its complete supported parameter set rather than only strength/speed.

## Verification
- Added `AutoPitchPresetEngineTest`.
- Tests verify category coverage and full preset parameter application.
- Full Android/Gradle build depends on the Android/Gradle toolchain being available in the build environment.
