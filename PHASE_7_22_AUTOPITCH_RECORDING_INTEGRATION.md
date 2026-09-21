# Almus Studio — Phase 7.22

## AutoPitch Live Performance & Recording Integration

Phase 7.22 connects the AutoPitch preset/engine controls to the vocal recording workflow.

### Capture modes
- **Dry Record** — records the original microphone signal; AutoPitch can still be monitored through headphones.
- **Monitor Only** — opens the microphone monitor and does not create a take.
- **Print AutoPitch** — records the clean take first, then renders the selected AutoPitch settings into a new WAV before placing the clip on the timeline. This preserves exact take length and avoids baking real-time monitor latency into the recorded file.

### Signal paths
```text
DRY RECORD
Mic -> input gain -> WAV -> clip
                 \\-> optional AutoPitch monitor -> headphones

MONITOR ONLY
Mic -> AutoPitch monitor -> headphones
(no WAV / no clip)

PRINT AUTOPITCH
Mic -> clean WAV -> AutoPitch offline render -> printed WAV -> clip
     \\-> optional AutoPitch monitor -> headphones
```

### Existing features retained
- Key + scale targeting
- Retune speed / hard mode
- Formant compensation
- Harmony modes and mix/width
- AutoPitch preset categories
- Count-in, punch recording, take lanes and recovery workflow

### Important implementation choice
The print path is deliberately **post-record**, not a direct dump of the live monitor buffer. The real-time monitor has DSP latency; writing that delayed buffer directly would change clip timing and can introduce leading/trailing artifacts. Offline rendering keeps the take's frame count and timeline alignment exact.

### Verification
- Source/static checks: run before packaging.
- ZIP integrity: verified after packaging.
- Full Android Gradle/APK build: not performed in this environment because the archive does not contain an executable Gradle wrapper and the environment has no `gradle` executable.
