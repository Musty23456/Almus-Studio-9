# Almus Studio — Phase 7.18 AutoPitch Pro

## Delivered
- Upgraded pitch detector with DC removal, Hann windowing, normalized autocorrelation, and parabolic lag interpolation.
- Added stable target-note hysteresis to reduce note-boundary chatter.
- Retune smoothing now operates in semitone space.
- Reworked offline granular OLA pitch shifting so read position progresses continuously instead of restarting inside every grain.
- Reworked live monitor around overlapping Hann-windowed grains and bounded OLA output.
- Added AutoPitch Pro presets: Classic, Natural, Heaviest, Sci-Fi.
- Added a BandLab-inspired AutoPitch control surface with intensity dial, style tabs, key, scale, retune speed, and hard/instant correction.
- Existing live monitoring, recording, and offline correction APIs remain backward compatible.

## Scope
This is a real offline + real-time pitch-correction implementation, not a mock UI. It remains intentionally monophonic-vocal focused. It does not claim commercial formant-preserving Auto-Tune parity, and the harmony presets are not implemented as generated harmony voices.

## Verification
- Kotlin source delimiter/static checks: run before packaging.
- C++ pitch DSP header compile smoke test: run before packaging.
- ZIP integrity: run before packaging.
- Full Android Gradle build depends on the project's Gradle wrapper/toolchain availability.
