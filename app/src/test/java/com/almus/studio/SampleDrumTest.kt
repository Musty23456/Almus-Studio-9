package com.almus.studio

import com.almus.studio.data.DrumKit
import com.almus.studio.data.DrumSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDrumTest {
    @Test fun sampleNormalizationClampsAndStripsPath() {
        val s = DrumSample("x", 140, "Kick", "samples\\kick.wav", 0, 140, 30f, 2f, -4, -1).normalized()
        assertEquals(127, s.pitch)
        assertEquals(1, s.velocityMin)
        assertEquals(127, s.velocityMax)
        assertEquals("kick.wav", s.fileName)
        assertEquals(12f, s.gainDb)
        assertEquals(1f, s.pan)
        assertEquals(0L, s.startFrame)
    }

    @Test fun kitKeepsOneMappingPerPitchVelocityRange() {
        val kit = DrumKit(samples = listOf(
            DrumSample("a",36,"Kick","a.wav"),
            DrumSample("b",36,"Kick 2","b.wav"),
            DrumSample("c",38,"Snare","c.wav")
        )).normalized()
        assertTrue(kit.samples.size <= 2)
    }
    @Test fun samplerControlsAreClampedAndPersistable() {
        val s = DrumSample("x", 36, "Kick", "kick.wav", 100, 120, -3f, -0.5f, 10, 1000, 200, 300, true, true, true).normalized()
        assertEquals(10L, s.startFrame)
        assertEquals(1000L, s.endFrame)
        assertEquals(200L, s.fadeInFrames)
        assertEquals(300L, s.fadeOutFrames)
        assertTrue(s.reverse)
        assertTrue(s.normalize)
    }

}
