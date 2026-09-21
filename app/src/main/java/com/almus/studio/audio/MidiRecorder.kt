package com.almus.studio.audio

import com.almus.studio.data.MidiAutomationEvent
import com.almus.studio.data.MidiNote
import java.util.UUID
import kotlin.math.roundToLong

/** Offline-friendly MIDI capture state machine. UI/ViewModel supplies musical time. */
class MidiRecorder(private val ppq: Long = MidiFileCodec.PPQ.toLong()) {
    data class CaptureSettings(
        val countInBars: Int = 0,
        val overdub: Boolean = false,
        val loopRecording: Boolean = false,
        val punchInTick: Long? = null,
        val punchOutTick: Long? = null,
        val quantizeSubdivision: Int = 1
    )
    data class CaptureResult(
        val notes: List<MidiNote>,
        val automation: List<MidiAutomationEvent>,
        val durationTicks: Long
    )
    private data class OpenNote(val pitch: Int, val channel: Int, val velocity: Int, val startTick: Long)

    private var settings = CaptureSettings()
    private var recording = false
    private var startTick = 0L
    private var clipStartTick = 0L
    private val open = LinkedHashMap<String, OpenNote>()
    private val notes = mutableListOf<MidiNote>()
    private val automation = mutableListOf<MidiAutomationEvent>()

    fun configure(value: CaptureSettings) { settings = value }
    fun isRecording() = recording
    fun start(atTick: Long) {
        recording = true; startTick = atTick; clipStartTick = atTick
        open.clear(); notes.clear(); automation.clear()
    }
    fun stop(atTick: Long): CaptureResult {
        if (!recording) return CaptureResult(emptyList(), emptyList(), 0)
        open.keys.toList().forEach { finishNote(it, atTick) }
        recording = false
        val duration = (atTick - clipStartTick).coerceAtLeast(1)
        return CaptureResult(notes.sortedBy { it.startTick }, automation.sortedBy { it.tick }, duration)
    }
    fun noteOn(channel: Int, pitch: Int, velocity: Int, tick: Long) {
        if (!accept(tick)) return
        val key = "$channel:$pitch"
        if (open.containsKey(key)) finishNote(key, tick)
        open[key] = OpenNote(pitch, channel, velocity.coerceIn(1,127), tick.coerceAtLeast(clipStartTick))
    }
    fun noteOff(channel: Int, pitch: Int, tick: Long) {
        if (!accept(tick)) return
        finishNote("$channel:$pitch", tick)
    }
    fun controlChange(channel: Int, controller: Int, value: Int, tick: Long) {
        if (!accept(tick)) return
        automation += MidiAutomationEvent((tick-clipStartTick).coerceAtLeast(0), "CC", controller, value, channel).normalized()
    }
    fun pitchBend(channel: Int, value14: Int, tick: Long) {
        if (!accept(tick)) return
        automation += MidiAutomationEvent((tick-clipStartTick).coerceAtLeast(0), "PITCH_BEND", 0, value14, channel).normalized()
    }
    fun aftertouch(channel: Int, value: Int, tick: Long) {
        if (!accept(tick)) return
        automation += MidiAutomationEvent((tick-clipStartTick).coerceAtLeast(0), "AFTERTOUCH", 0, value, channel).normalized()
    }
    private fun accept(tick: Long): Boolean {
        if (!recording) return false
        val inTick = tick - clipStartTick
        val pin = settings.punchInTick
        val pout = settings.punchOutTick
        if (pin != null && inTick < pin) return false
        if (pout != null && inTick > pout) return false
        return true
    }
    private fun finishNote(key: String, endTick: Long) {
        val n = open.remove(key) ?: return
        val start = (n.startTick-clipStartTick).coerceAtLeast(0)
        var duration = (endTick-n.startTick).coerceAtLeast(1)
        val sub = settings.quantizeSubdivision
        if (sub > 0) {
            val grid = MidiQuantize.gridTicks(sub)
            val qs = MidiQuantize.snapTick(start, sub)
            val qe = MidiQuantize.snapTick(start+duration, sub)
            notes += MidiNote(UUID.randomUUID().toString(), qs, (qe-qs).coerceAtLeast(grid), n.pitch, n.velocity, n.channel).normalized()
        } else notes += MidiNote(UUID.randomUUID().toString(), start, duration, n.pitch, n.velocity, n.channel).normalized()
    }
}
