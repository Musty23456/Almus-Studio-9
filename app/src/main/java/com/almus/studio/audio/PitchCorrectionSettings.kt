package com.almus.studio.audio

enum class RootNote(val pitchClass: Int, val label: String) {
    C(0, "C"), C_SHARP(1, "C#"), D(2, "D"), D_SHARP(3, "D#"), E(4, "E"), F(5, "F"),
    F_SHARP(6, "F#"), G(7, "G"), G_SHARP(8, "G#"), A(9, "A"), A_SHARP(10, "A#"), B(11, "B")
}

enum class MusicalScale(val label: String, val intervals: List<Int>) {
    MAJOR("Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    CHROMATIC("Chromatic", (0..11).toList())
}

fun MusicalScale.toMask(): Int = intervals.fold(0) { mask, interval -> mask or (1 shl interval) }

enum class HarmonyMode(val label: String, val offsets: List<Int>) {
    NONE("Classic", emptyList()),
    THIRD("Third", listOf(4)),
    FIFTH("Fifth", listOf(7)),
    DUET("Duet", listOf(7)),
    BIG_HARMONY("Big Harmony", listOf(4, 7, 12)),
    OCTAVE("Octave", listOf(12))
}

enum class AutoPitchCaptureMode(val label: String, val description: String) {
    DRY_RECORD("Dry Record", "Record the clean microphone signal."),
    MONITOR_ONLY("Monitor Only", "Hear AutoPitch without creating a take."),
    PRINT_EFFECT("Print AutoPitch", "Record first, then render AutoPitch into the take."),
}

enum class AutoPitchCategory(val label: String) {
    ESSENTIALS("Essentials"),
    HIP_HOP("Hip Hop"),
    HYPERPOP("Hyperpop"),
    SCI_FI("Sci-Fi")
}

/**
 * Production presets. Every value maps only to parameters that are actually
 * supported by the current native AutoPitch engine.
 */
enum class AutoPitchPreset(
    val label: String,
    val category: AutoPitchCategory,
    val strength: Float,
    val speedMs: Float,
    val hardMode: Boolean,
    val harmony: HarmonyMode = HarmonyMode.NONE,
    val harmonyMix: Float = 0.35f,
    val harmonyPan: Float = 0.55f,
    val formantCompensation: Float = 0.5f
) {
    CLASSIC("Classic", AutoPitchCategory.ESSENTIALS, 0.70f, 55f, false, HarmonyMode.NONE, 0.0f, 0.50f, 0.62f),
    NATURAL("Natural", AutoPitchCategory.ESSENTIALS, 0.38f, 110f, false, HarmonyMode.NONE, 0.0f, 0.50f, 0.82f),
    THIRD("Third", AutoPitchCategory.ESSENTIALS, 0.68f, 48f, false, HarmonyMode.THIRD, 0.30f, 0.64f, 0.68f),
    DUET("Duet", AutoPitchCategory.HIP_HOP, 0.72f, 42f, false, HarmonyMode.DUET, 0.32f, 0.78f, 0.66f),
    BIG_HARMONY("Big Harmony", AutoPitchCategory.HIP_HOP, 0.78f, 34f, false, HarmonyMode.BIG_HARMONY, 0.38f, 0.90f, 0.62f),
    HEAVIEST("Heaviest", AutoPitchCategory.HYPERPOP, 1.0f, 8f, true, HarmonyMode.NONE, 0.0f, 0.50f, 0.42f),
    SCI_FI("Sci-Fi", AutoPitchCategory.SCI_FI, 1.0f, 4f, true, HarmonyMode.BIG_HARMONY, 0.52f, 1.0f, 0.50f)
}

fun AutoPitchPreset.applyTo(current: PitchCorrectionSettings): PitchCorrectionSettings = current.copy(
    preset = this,
    strength = strength,
    speedMs = speedMs,
    hardMode = hardMode,
    harmony = harmony,
    harmonyMix = harmonyMix,
    harmonyPan = harmonyPan,
    formantCompensation = formantCompensation
)

data class PitchCorrectionSettings(
    val root: RootNote = RootNote.C,
    val scale: MusicalScale = MusicalScale.MAJOR,
    val strength: Float = 0.7f,
    val speedMs: Float = 50f,
    val hardMode: Boolean = false,
    val preset: AutoPitchPreset = AutoPitchPreset.CLASSIC,
    val harmony: HarmonyMode = HarmonyMode.NONE,
    val harmonyMix: Float = 0.35f,
    val harmonyPan: Float = 0.55f,
    val formantCompensation: Float = 0.5f,
    val captureMode: AutoPitchCaptureMode = AutoPitchCaptureMode.DRY_RECORD,
)
