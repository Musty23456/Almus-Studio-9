package com.almus.studio

import com.almus.studio.audio.AutoPitchPreset
import com.almus.studio.audio.MusicalScale
import com.almus.studio.audio.RootNote
import com.almus.studio.audio.PitchCorrectionSettings
import com.almus.studio.audio.toMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPitchSettingsTest {
    @Test fun majorMaskHasSevenNotes() {
        assertEquals(7, MusicalScale.MAJOR.intervals.size)
        assertEquals(7, Integer.bitCount(MusicalScale.MAJOR.toMask()))
    }

    @Test fun heaviestPresetIsFastAndFullStrength() {
        val p = AutoPitchPreset.HEAVIEST
        assertEquals(1.0f, p.strength, 0.0001f)
        assertTrue(p.speedMs <= 10f)
        assertTrue(p.hardMode)
    }

    @Test fun settingsDefaultToClassic() {
        val s = PitchCorrectionSettings(root = RootNote.C)
        assertEquals(AutoPitchPreset.CLASSIC, s.preset)
        assertEquals(RootNote.C, s.root)
    }
}
