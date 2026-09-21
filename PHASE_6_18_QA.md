# Almus Studio — Phase 6.18 Vocal Final QA

## Scope

This phase is a regression pass before moving beyond the vocal-first stage. It checks the project/data path across:

1. Recording recovery and take metadata
2. Non-destructive split/trim/fade/gain workflow
3. Take selection/comping
4. Silence cleanup analysis
5. Vocal mixer sends and native aux routing
6. Reverb/Delay returns
7. Volume/Pan/FX-send automation
8. Vocal mix snapshots
9. Master limiter/loudness protection
10. WAV export and delivery safeguards

## Build policy

GitHub Actions remains the required Android build path. The workflow installs the pinned Android NDK/CMake toolchain, runs unit tests, builds the debug APK, and uploads the APK artifact. Release signing remains optional through repository secrets.

## Verification performed in this environment

- ZIP archive integrity: PASS
- Android project structure: PASS
- GitHub Actions workflow present: PASS
- Phase 6.18 regression test source added: PASS
- Android APK build: **not claimed here** because the current environment does not provide the Android/Gradle toolchain.

## Exit criteria for the vocal stage

The vocal-first implementation should be considered functionally staged for the next major area only after GitHub Actions reports both `gradle test` and `gradle assembleDebug` successful on the repository.
