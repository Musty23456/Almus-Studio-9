package com.almus.studio

import com.almus.studio.audio.MidiFileCodec
import com.almus.studio.data.MidiClip
import com.almus.studio.data.MidiNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class MidiFileCodecTest {
    @Test fun roundTripPreservesNotes() {
        val file = File.createTempFile("almus", ".mid")
        try {
            val notes = listOf(
                MidiNote(UUID.randomUUID().toString(), 0, 480, 60, 100, 0),
                MidiNote(UUID.randomUUID().toString(), 480, 960, 64, 90, 0)
            )
            MidiFileCodec.export(file, listOf(MidiClip("c", 0, 48000, notes, "Lead")), 120)
            assertTrue(file.length() > 20)
            val imported = MidiFileCodec.import(file)
            assertEquals(1, imported.size)
            assertEquals(2, imported.first().notes.size)
            assertEquals(60, imported.first().notes.first().pitch)
            assertEquals(480, imported.first().notes[1].startTick)
        } finally { file.delete() }
    }
}
