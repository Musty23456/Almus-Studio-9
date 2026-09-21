package com.almus.studio

import com.almus.studio.data.DrumSample
import org.junit.Assert.assertEquals
import org.junit.Test

class SamplerPerformanceTest {
    @Test fun samplerDefaultsAreSafe() {
        val sample = DrumSample("s", 60, "Piano", "p.wav").normalized()
        assertEquals(60, sample.rootNote)
        assertEquals(0, sample.fineTuneCents)
        assertEquals(false, sample.loopEnabled)
    }

    @Test fun performanceRangesHaveExpectedBounds() {
        val bend = 1.5f.coerceIn(-1f, 1f)
        val range = 30.coerceIn(1, 24)
        val modulation = (-0.2f).coerceIn(0f, 1f)
        val aftertouch = 0.75f.coerceIn(0f, 1f)
        val velocityPitch = 1800.coerceIn(-1200, 1200)
        assertEquals(1f, bend, 0f)
        assertEquals(24, range)
        assertEquals(0f, modulation, 0f)
        assertEquals(0.75f, aftertouch, 0f)
        assertEquals(1200, velocityPitch)
    }
}
