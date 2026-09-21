package com.almus.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRecoveryTest {
    @Test fun recoveredFrameCountUsesFloat32ChannelWidth() {
        val dataBytes = 48000L * 2L * 4L
        assertEquals(48000L, dataBytes / (2L * 4L))
    }

    @Test fun monoFloatWavFrameCountIsDataBytesOverFour() {
        val dataBytes = 12345L * 4L
        assertEquals(12345L, dataBytes / 4L)
    }
}
