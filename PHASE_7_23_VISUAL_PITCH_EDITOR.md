# Almus Studio Phase 7.23 — Visual Pitch Editor

Adds an offline vocal pitch-curve analyzer and a visual editor surface for audio clips.

- Pitch detection curve with voiced/unvoiced points
- Nearest musical target visualization
- Point selection and manual target-note dragging
- Non-destructive editor layer
- Integrated into the clip actions dialog
- Uses project-local WAV data only
- No network dependency

The manual point map is intentionally an editor foundation in this phase. Rendering arbitrary per-point pitch targets into the native DSP is reserved for the next pitch-warp stage; the existing AutoPitch processor remains available through the existing Pitch correction dialog.
