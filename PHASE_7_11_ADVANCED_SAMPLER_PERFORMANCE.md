# Phase 7.11 — Advanced Sampler Performance Engine

- Persistent pitch-bend control with configurable 1–24 semitone range.
- Modulation control mapped to a smooth vibrato during sampler rendering.
- Aftertouch expression adds post-note amplitude pressure.
- Velocity-to-pitch expression in cents, clamped to a musical ±1200-cent range.
- Performance expression is applied to chromatic sample playback, not only procedural synth voices.
- Sampler playback is no longer restricted to MIDI channel 10; mapped samples can play on ordinary MIDI sampler tracks.
- Existing trim, loop, reverse, fade, normalize, ADSR, choke, gain and pan behavior remains intact.
- All controls are offline and persist through project JSON.
