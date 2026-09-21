package com.almus.studio

import com.almus.studio.audio.MidiFileCodec
import com.almus.studio.audio.MidiQuantize
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiQuantizeTest {
    @Test fun gridSizesAreCorrect() {
        assertEquals(MidiFileCodec.PPQ.toLong(), MidiQuantize.gridTicks(1))
        assertEquals(MidiFileCodec.PPQ / 2L, MidiQuantize.gridTicks(2))
        assertEquals(MidiFileCodec.PPQ / 4L, MidiQuantize.gridTicks(4))
    }

    @Test fun ticksSnapToNearestGrid() {
        assertEquals(480L, MidiQuantize.snapTick(430, 1))
        assertEquals(240L, MidiQuantize.snapTick(250, 2))
        assertEquals(120L, MidiQuantize.snapTick(179, 4))
    }

    @Test fun valuesAreClamped() {
        assertEquals(0, MidiQuantize.clampPitch(-2))
        assertEquals(127, MidiQuantize.clampPitch(200))
        assertEquals(1, MidiQuantize.clampVelocity(-4))
        assertEquals(127, MidiQuantize.clampVelocity(999))
        assertEquals(1L, MidiQuantize.clampDuration(0))
    }
}
