package com.almus.studio

import com.almus.studio.audio.MidiRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiRecordingTest {
    @Test fun capturesNotesAndQuantizes() {
        val r = MidiRecorder()
        r.configure(MidiRecorder.CaptureSettings(quantizeSubdivision = 2))
        r.start(0)
        r.noteOn(0, 60, 100, 10)
        r.noteOff(0, 60, 470)
        val out = r.stop(480)
        assertEquals(1, out.notes.size)
        assertEquals(0, out.notes.first().startTick)
        assertTrue(out.notes.first().durationTicks > 0)
    }

    @Test fun capturesCcAndPitchBend() {
        val r = MidiRecorder()
        r.start(0)
        r.controlChange(0, 1, 90, 120)
        r.pitchBend(0, 8192, 240)
        val out = r.stop(480)
        assertEquals(2, out.automation.size)
    }
}
