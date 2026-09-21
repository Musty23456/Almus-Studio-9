package com.almus.studio

import com.almus.studio.audio.MidiEditorTools
import com.almus.studio.data.MidiNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiEditorToolsTest {
    private fun note(id: String, start: Long, pitch: Int = 60, velocity: Int = 80) = MidiNote(id, start, 240, pitch, velocity)

    @Test fun quantizeStrengthMovesTowardGrid() {
        val result = MidiEditorTools.quantize(listOf(note("a", 100)), 2, 100)
        assertEquals(0L, result.single().startTick)
    }

    @Test fun swingMovesOffbeatLater() {
        val result = MidiEditorTools.swing(listOf(note("a", 240)), 2, 50)
        assertTrue(result.single().startTick > 240L)
    }

    @Test fun transposeClampsAndMovesPitch() {
        assertEquals(72, MidiEditorTools.transpose(listOf(note("a", 0, 60)), 12).single().pitch)
    }

    @Test fun scaleQuantizeKeepsMajorNotes() {
        val result = MidiEditorTools.scaleQuantize(listOf(note("a", 0, 61)), 0, 1)
        assertTrue(result.single().pitch in setOf(60, 62))
    }

    @Test fun cleanupRemovesDuplicateStartPitchChannel() {
        val result = MidiEditorTools.cleanup(listOf(note("a", 0, 60), note("b", 0, 60), note("c", 240, 62)))
        assertEquals(2, result.size)
    }

    @Test fun duplicateCreatesFreshIdsAndPreservesPhrase() {
        val source = listOf(note("a", 100, 60), note("b", 340, 64))
        val copy = MidiEditorTools.duplicate(source, 480)
        assertEquals(listOf(580L, 820L), copy.map { it.startTick })
        assertTrue(copy.zip(source).all { it.first.id != it.second.id })
    }

    @Test fun transposeSelectedOnlyChangesSelectedNotes() {
        val source = listOf(note("a", 0, 60), note("b", 120, 64))
        val result = MidiEditorTools.transposeSelected(source, setOf("b"), 12)
        assertEquals(60, result[0].pitch)
        assertEquals(76, result[1].pitch)
    }

    @Test fun resizeSelectedOnlyChangesSelectedNotes() {
        val source = listOf(note("a", 0, 60), note("b", 120, 64))
        val result = MidiEditorTools.resizeSelected(source, setOf("a"), 960)
        assertEquals(960L, result[0].durationTicks)
        assertEquals(240L, result[1].durationTicks)
    }
}
