# Almus Studio — Phase 7.20: Formant-Aware AutoPitch

Phase 7.20 upgrades AutoPitch from a basic pitch shifter to a compact source/filter vocal processor.

## Added
- LPC spectral-envelope estimation per vocal grain.
- LPC inverse filtering to separate excitation from the broad vocal envelope.
- Pitch shifting of the excitation while keeping the original LPC filter coefficients.
- LPC resynthesis to keep broad formant locations more stable after pitch correction.
- Blend control: `formantCompensation` 0..1.
- Real-time monitoring uses the same formant-aware path when enabled.
- Offline pitch correction uses the same path.
- AutoPitch UI exposes a Formant Preservation slider.
- Offline processing now correctly passes harmony and formant settings.

## DSP notes
This is a compact LPC source/filter implementation, not a clone of any commercial vocal processor. It is intended to reduce spectral-envelope movement and chipmunk coloration; it does not guarantee perfect formant tracking on every voice, consonant, or extreme pitch shift.

## Verification
- Standalone C++ pitch DSP compile/test: PASS
- Standalone real-time monitor header compile/test: PASS
- ZIP integrity: PASS
- Full Android/Gradle build: not run in this environment (no Gradle wrapper/toolchain available).
