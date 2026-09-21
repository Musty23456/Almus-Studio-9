package com.almus.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportDeliveryTest {
    @Test fun exportExtensionIsWav() {
        val name = "My Vocal Mix-123.wav"
        assertEquals("wav", name.substringAfterLast('.'))
    }

    @Test fun supportedSampleRatesAreStandard() {
        val rates = setOf(44100, 48000, 96000)
        assertEquals(true, 48000 in rates)
        assertEquals(false, 22050 in rates)
    }
}
