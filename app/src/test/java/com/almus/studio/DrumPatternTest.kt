package com.almus.studio

import com.almus.studio.data.DrumPattern
import com.almus.studio.data.DrumSoundPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumPatternTest {
    @Test fun normalizesStepsAndVelocities() {
        val p = DrumPattern(16, 1, listOf(DrumSoundPattern(36, "Kick", listOf(120, -2))))
            .normalized()
        assertEquals(16, p.steps)
        assertEquals(16, p.sounds.first().velocities.size)
        assertEquals(120, p.sounds.first().velocities[0])
        assertEquals(0, p.sounds.first().velocities[1])
        assertTrue(p.sounds.first().velocities.drop(2).all { it == 0 })
    }
}
