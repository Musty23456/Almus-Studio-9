# Almus Studio — Phase 7.9 Professional Sampler/Looper Engine

Implemented on top of Phase 7.8.

## Added
- Sample root-note mapping (0–127)
- Fine tuning (-100..+100 cents)
- One-shot and loop playback modes
- Loop start/end frame controls
- Pitch-aware sample playback with source-rate conversion
- Non-destructive ADSR envelope for mapped samples
- Choke groups for mutually exclusive sample voices
- Existing trim, fade, reverse, normalize, gain and pan controls retained
- Sample playback continues to use the offline WAV cache
- Persistent sampler controls through the existing project JSON model
- UI controls in the Sample Editor
- `SamplerControlsTest`

## Compatibility
Older projects deserialize with the new fields at their defaults, so existing drum kits remain usable.

## Verification
- Source structure/static checks performed.
- ZIP integrity verified after packaging.
- Full Android/Gradle APK build was not run in this environment.
