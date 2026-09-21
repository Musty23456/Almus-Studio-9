# Almus Studio — Phase 7.19
## Vocal Harmony Engine + AutoPitch Harmony Workflow

### Added
- Real-time AutoPitch harmony modes: Classic/None, Third, Fifth, Duet, Big Harmony, Octave.
- Offline vocal processing can render corrected lead plus generated harmony voices into a stereo WAV.
- Harmony mix and stereo width controls are persisted through the AutoPitch settings object.
- Live monitoring receives harmony parameters through the native JNI bridge.
- Harmony voices share the detected lead-note timeline so they follow the singer rather than using fixed-rate pitch shifting.
- Existing key/scale, strength, retune speed and hard-mode controls remain active.

### Scope
This is a working harmony foundation, not a claim of feature parity with a commercial product. The current engine uses granular OLA pitch shifting and does not yet implement a true phase-vocoder/formant-preserving vocal model. Formant-aware processing remains a later DSP phase.

### Verification
- Native source delimiter checks.
- Kotlin source delimiter checks.
- C++ syntax smoke compile where host headers/toolchain permit.
- WAV/harmony DSP smoke tests.
- ZIP integrity check.
- Full Android Gradle/APK build is environment-dependent and is not claimed unless it completes successfully.
