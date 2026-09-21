package com.almus.studio.audio

/** Central preset catalog/filtering used by the AutoPitch UI and future preset browsers. */
object AutoPitchPresetEngine {
    val all: List<AutoPitchPreset> = AutoPitchPreset.values().toList()

    fun forCategory(category: AutoPitchCategory): List<AutoPitchPreset> =
        all.filter { it.category == category }

    fun apply(preset: AutoPitchPreset, current: PitchCorrectionSettings): PitchCorrectionSettings =
        preset.applyTo(current)
}
