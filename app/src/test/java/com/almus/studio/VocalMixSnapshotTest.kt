package com.almus.studio

import com.almus.studio.data.TrackMixSnapshot
import com.almus.studio.data.VocalMixSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class VocalMixSnapshotTest {
    @Test
    fun snapshotKeepsTrackMixerValues() {
        val snapshot = VocalMixSnapshot(
            id = "s1",
            name = "Lead vocal",
            createdAtEpochMs = 1L,
            masterVolumeDb = -1f,
            reverbReturnVolumeDb = -8f,
            delayReturnVolumeDb = -12f,
            reverbReturnMuted = false,
            reverbReturnSolo = false,
            delayReturnMuted = false,
            delayReturnSolo = false,
            tracks = listOf(TrackMixSnapshot("t1", -3f, 0.25f, false, true, -18f, -24f))
        )
        assertEquals("Lead vocal", snapshot.name)
        assertEquals(-3f, snapshot.tracks.single().volumeDb)
        assertEquals(0.25f, snapshot.tracks.single().pan)
        assertEquals(-18f, snapshot.tracks.single().reverbSendDb)
    }
}
