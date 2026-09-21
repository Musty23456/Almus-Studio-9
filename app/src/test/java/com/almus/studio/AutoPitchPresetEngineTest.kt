package com.almus.studio

import com.almus.studio.audio.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPitchPresetEngineTest {
    @Test fun everyCategoryHasAtLeastOnePreset() {
        AutoPitchCategory.values().forEach { category ->
            assertTrue(AutoPitchPresetEngine.forCategory(category).isNotEmpty())
        }
    }

    @Test fun presetAppliesAllSupportedDspParameters() {
        val base = PitchCorrectionSettings()
        val result = AutoPitchPresetEngine.apply(AutoPitchPreset.SCI_FI, base)
        assertEquals(AutoPitchPreset.SCI_FI, result.preset)
        assertEquals(AutoPitchPreset.SCI_FI.strength, result.strength, 0.0001f)
        assertEquals(AutoPitchPreset.SCI_FI.speedMs, result.speedMs, 0.0001f)
        assertEquals(AutoPitchPreset.SCI_FI.hardMode, result.hardMode)
        assertEquals(AutoPitchPreset.SCI_FI.harmony, result.harmony)
        assertEquals(AutoPitchPreset.SCI_FI.harmonyMix, result.harmonyMix, 0.0001f)
        assertEquals(AutoPitchPreset.SCI_FI.harmonyPan, result.harmonyPan, 0.0001f)
        assertEquals(AutoPitchPreset.SCI_FI.formantCompensation, result.formantCompensation, 0.0001f)
    }

    @Test fun naturalKeepsMoreFormantPreservationThanHeaviest() {
        assertTrue(AutoPitchPreset.NATURAL.formantCompensation > AutoPitchPreset.HEAVIEST.formantCompensation)
        assertTrue(AutoPitchPreset.NATURAL.speedMs > AutoPitchPreset.HEAVIEST.speedMs)
    }
}
