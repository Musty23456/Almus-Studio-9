package com.almus.studio

import com.almus.studio.audio.TimeConversion
import org.junit.Assert.assertEquals
import org.junit.Test

class Phase5TimelineTest {
    @Test fun subdivisionSnapWorksAt120Bpm48k() {
        assertEquals(6000L, TimeConversion.snapFrames(5900, 4, 120, 48000))
        assertEquals(12000L, TimeConversion.snapFrames(11900, 4, 120, 48000))
    }

    @Test fun beatGridIsExactAt120Bpm48k() {
        val beat = TimeConversion.framesPerBeat(120, 48000).toLong()
        assertEquals(24000L, beat)
        assertEquals(48000L, TimeConversion.beatsToFrames(2.0, 120, 48000))
    }
}
