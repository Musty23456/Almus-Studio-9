# Almus Studio — Phase 7.16

## Advanced Piano Roll Pro

Implemented on top of Phase 7.15:
- Multi-note selection state in Piano Roll.
- Select All / Clear selection.
- Copy selected MIDI notes into an in-app clipboard.
- Paste selected notes at a target tick with fresh note IDs.
- Duplicate selected notes by the selected phrase span.
- Multi-selection visual highlighting.
- Existing note move/resize/velocity/delete workflows retained.
- Existing Quantize, Swing, Humanize, Velocity, Legato, Transpose, Scale Quantize and Cleanup retained.
- Batch note operations use the existing project undo/persistence pipeline.
- MIDI automation model remains persistent and ready for the dedicated automation-lane editing phase.
- Offline-first architecture retained.

## Validation
- ZIP integrity checked after packaging.
- Source delimiter/bracket checks performed.
- Full Android/Gradle build was not run because the project archive has no Gradle wrapper in this environment.
