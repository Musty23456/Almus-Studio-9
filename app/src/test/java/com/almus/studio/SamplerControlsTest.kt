package com.almus.studio

import com.almus.studio.data.DrumSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplerControlsTest {
    @Test fun normalizedSamplerControlsAreClamped() {
        val sample = DrumSample(
            id = "x", pitch = 200, name = "x", fileName = "../kick.wav",
            rootNote = -2, fineTuneCents = 900, loopStartFrame = -5, loopEndFrame = -1,
            attackMs = -2, decayMs = 9000, sustain = 2f, releaseMs = 0, chokeGroup = 99
        ).normalized()
        assertEquals(127, sample.pitch)
        assertEquals(0, sample.rootNote)
        assertEquals(100, sample.fineTuneCents)
        assertEquals(0L, sample.loopStartFrame)
        assertEquals(0L, sample.loopEndFrame)
        assertEquals(0, sample.attackMs)
        assertEquals(5000, sample.decayMs)
        assertEquals(1f, sample.sustain, 0f)
        assertEquals(1, sample.releaseMs)
        assertEquals(32, sample.chokeGroup)
        assertEquals("kick.wav", sample.fileName)
    }

    @Test fun loopCanBeEnabledWithoutChangingTrim() {
        val sample = DrumSample("x", 36, "Kick", "kick.wav", loopEnabled = true, loopStartFrame = 100, loopEndFrame = 500)
        assertTrue(sample.normalized().loopEnabled)
        assertEquals(100L, sample.normalized().loopStartFrame)
        assertEquals(500L, sample.normalized().loopEndFrame)
    }
}
