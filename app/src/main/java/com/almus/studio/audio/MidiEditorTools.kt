package com.almus.studio.audio

import com.almus.studio.data.MidiNote
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** Professional, deterministic MIDI note-editing transforms used by the piano roll. */
object MidiEditorTools {
    fun quantize(notes: List<MidiNote>, subdivision: Int, strength: Int = 100): List<MidiNote> {
        val s = strength.coerceIn(0, 100) / 100.0
        return notes.map { n ->
            val target = MidiQuantize.snapTick(n.startTick, subdivision)
            val start = (n.startTick + (target - n.startTick) * s).roundToLong().coerceAtLeast(0)
            n.copy(startTick = start).normalized()
        }.sortedWith(compareBy<MidiNote> { it.startTick }.thenBy { it.pitch })
    }

    fun swing(notes: List<MidiNote>, subdivision: Int, percent: Int): List<MidiNote> {
        val grid = MidiQuantize.gridTicks(subdivision)
        val amount = percent.coerceIn(0, 75) / 100.0
        return notes.map { n ->
            val cell = Math.floorDiv(n.startTick, grid)
            val odd = cell % 2L == 1L
            val shift = if (odd) (grid * 0.5 * amount).roundToLong() else 0L
            n.copy(startTick = (n.startTick + shift).coerceAtLeast(0)).normalized()
        }.sortedBy { it.startTick }
    }

    /** Deterministic pseudo-humanize; avoids random project diffs and remains undo-friendly. */
    fun humanize(notes: List<MidiNote>, ticks: Int, velocity: Int): List<MidiNote> {
        val t = ticks.coerceIn(0, 120)
        val v = velocity.coerceIn(0, 30)
        return notes.mapIndexed { i, n ->
            val phase = ((i * 37 + n.pitch * 11) % 101) - 50
            val dt = (phase / 50.0 * t).roundToInt().toLong()
            val dv = (phase / 50.0 * v).roundToInt()
            n.copy(startTick = (n.startTick + dt).coerceAtLeast(0), velocity = (n.velocity + dv).coerceIn(1, 127)).normalized()
        }.sortedBy { it.startTick }
    }

    fun velocityScale(notes: List<MidiNote>, center: Int, amount: Int): List<MidiNote> {
        val factor = amount.coerceIn(-100, 100) / 100.0
        return notes.map { n ->
            val delta = (n.velocity - center) * factor
            n.copy(velocity = (center + delta).roundToInt().coerceIn(1, 127)).normalized()
        }
    }

    fun legato(notes: List<MidiNote>, gapTicks: Long = 0L): List<MidiNote> {
        val sorted = notes.sortedWith(compareBy<MidiNote> { it.pitch }.thenBy { it.startTick })
        val result = sorted.toMutableList()
        var i = 0
        while (i + 1 < result.size) {
            val a = result[i]
            val b = result[i + 1]
            if (a.pitch == b.pitch) {
                val newDuration = (b.startTick - a.startTick - gapTicks).coerceAtLeast(1L)
                result[i] = a.copy(durationTicks = newDuration).normalized()
            }
            i++
        }
        return result.sortedBy { it.startTick }
    }

    fun transpose(notes: List<MidiNote>, semitones: Int): List<MidiNote> =
        notes.map { it.copy(pitch = (it.pitch + semitones).coerceIn(0, 127)).normalized() }

    fun scaleQuantize(notes: List<MidiNote>, root: Int, scale: Int): List<MidiNote> {
        val allowed = when (scale) {
            1 -> setOf(0, 2, 4, 5, 7, 9, 11) // major
            2 -> setOf(0, 2, 3, 5, 7, 8, 10) // natural minor
            3 -> setOf(0, 2, 3, 5, 7, 9, 10) // melodic/dorian-style editor scale
            else -> (0..11).toSet()
        }
        fun nearest(p: Int): Int {
            var best = p
            var bestDist = Int.MAX_VALUE
            for (d in -6..6) {
                val candidate = (p + d).coerceIn(0, 127)
                if (((candidate - root) % 12 + 12) % 12 in allowed) {
                    val dist = abs(candidate - p)
                    if (dist < bestDist) { best = candidate; bestDist = dist }
                }
            }
            return best
        }
        return notes.map { it.copy(pitch = nearest(it.pitch)).normalized() }
    }

    fun duplicate(notes: List<MidiNote>, offsetTicks: Long): List<MidiNote> =
        notes.map { it.copy(id = java.util.UUID.randomUUID().toString(), startTick = (it.startTick + offsetTicks).coerceAtLeast(0)) }

    fun transposeSelected(notes: List<MidiNote>, ids: Set<String>, semitones: Int): List<MidiNote> =
        notes.map { if (it.id in ids) it.copy(pitch = (it.pitch + semitones).coerceIn(0, 127)).normalized() else it }

    fun resizeSelected(notes: List<MidiNote>, ids: Set<String>, durationTicks: Long): List<MidiNote> =
        notes.map { if (it.id in ids) it.copy(durationTicks = durationTicks.coerceAtLeast(1)) else it }

    fun cleanup(notes: List<MidiNote>): List<MidiNote> {
        return notes.map { it.normalized() }
            .distinctBy { "${it.startTick}:${it.pitch}:${it.channel}" }
            .sortedWith(compareBy<MidiNote> { it.startTick }.thenBy { it.pitch })
    }
}
