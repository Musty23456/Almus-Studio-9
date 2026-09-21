package com.almus.studio.audio

/** MIDI editor grid helpers. PPQ is 480, so a quarter note is 480 ticks. */
object MidiQuantize {
    fun gridTicks(subdivision: Int): Long = when (subdivision) {
        1 -> MidiFileCodec.PPQ.toLong()       // 1/4
        2 -> MidiFileCodec.PPQ / 2L            // 1/8
        else -> MidiFileCodec.PPQ / 4L         // 1/16
    }

    fun snapTick(tick: Long, subdivision: Int): Long {
        val grid = gridTicks(subdivision).coerceAtLeast(1L)
        return ((tick.toDouble() / grid).toLong() + if (tick % grid >= grid / 2.0) 1 else 0) * grid
    }

    fun clampPitch(pitch: Int): Int = pitch.coerceIn(0, 127)
    fun clampVelocity(velocity: Int): Int = velocity.coerceIn(1, 127)
    fun clampDuration(durationTicks: Long): Long = durationTicks.coerceAtLeast(1L)
}
