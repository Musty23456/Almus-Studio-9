package com.almus.studio

import com.almus.studio.data.AudioClip
import com.almus.studio.data.Project
import com.almus.studio.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.18 regression coverage for the complete vocal delivery pipeline. */
class VocalPipelineQaTest {
    @Test
    fun vocalClipPreservesNonDestructiveEditingMetadata() {
        val clip = AudioClip(
            id = "clip", fileName = "take.wav", startFrame = 48000L,
            sourceOffsetFrames = 240L, lengthFrames = 96000L,
            gainDb = -2f, fadeInFrames = 120L, fadeOutFrames = 240L,
            takeGroupId = "lead", takeNumber = 2, takeSelected = true
        )
        assertEquals(48000L, clip.startFrame)
        assertEquals(240L, clip.sourceOffsetFrames)
        assertEquals(2, clip.takeNumber)
        assertTrue(clip.fadeInFrames > 0)
        assertTrue(clip.fadeOutFrames > 0)
    }

    @Test
    fun vocalTrackRetainsAuxSendsAndAutomation() {
        val track = Track(
            id = "vocal", name = "Lead Vocal",
            reverbSendDb = -12f, delaySendDb = -18f,
            automation = mapOf("VOLUME_DB" to emptyList())
        )
        assertEquals(-12f, track.reverbSendDb)
        assertEquals(-18f, track.delaySendDb)
        assertTrue(track.automation.containsKey("VOLUME_DB"))
    }

    @Test
    fun projectKeepsFinalMasterProtectionDefaults() {
        val project = Project(
            id = "p", name = "Vocal QA", createdAtEpochMs = 1L, modifiedAtEpochMs = 1L,
            tracks = listOf(Track("v", "Vocal"))
        )
        assertTrue(project.masterLimiterEnabled)
        assertEquals(-1f, project.masterLimiterCeilingDb)
        assertEquals(48000, project.sampleRate)
    }
}
