package com.almus.studio.audio

import com.almus.studio.data.MidiClip
import com.almus.studio.data.MidiNote
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Minimal Standard MIDI File (SMF) type-1 codec. No external MIDI library is required. */
object MidiFileCodec {
    const val PPQ = 480

    data class ImportedTrack(val name: String, val notes: List<MidiNote>)

    fun export(file: File, clips: List<MidiClip>, bpm: Int, channel: Int = 0) {
        require(bpm > 0)
        val tracks = clips.map { clip -> buildTrack(clip, channel) }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII)); writeU32(out, 6)
        writeU16(out, if (tracks.size > 1) 1 else 0); writeU16(out, tracks.size.coerceAtLeast(1)); writeU16(out, PPQ)
        if (tracks.isEmpty()) out.write(buildTrack(MidiClip(UUID.randomUUID().toString(), 0, 0, emptyList()), channel))
        else tracks.forEach(out::write)
        file.parentFile?.mkdirs(); file.writeBytes(out.toByteArray())
    }

    fun import(file: File): List<ImportedTrack> {
        val b = file.readBytes(); var p = 0
        fun take(n: Int): ByteArray { require(p + n <= b.size) { "Truncated MIDI file" }; return b.copyOfRange(p, p + n).also { p += n } }
        require(String(take(4), Charsets.US_ASCII) == "MThd")
        val headerLen = u32(take(4)).toInt(); require(headerLen >= 6)
        val format = u16(take(2)); val trackCount = u16(take(2)); val division = u16(take(2));
        if (headerLen > 6) take(headerLen - 6)
        require(format in 0..2) { "Unsupported MIDI format" }
        require(division and 0x8000 == 0) { "SMPTE MIDI division is unsupported" }
        val scale = PPQ.toDouble() / division.toDouble().coerceAtLeast(1.0)
        val result = mutableListOf<ImportedTrack>()
        repeat(trackCount) {
            require(String(take(4), Charsets.US_ASCII) == "MTrk")
            val len = u32(take(4)).toInt(); val end = p + len
            var tick = 0L; var running = -1; val active = mutableMapOf<Pair<Int,Int>, Pair<Long,Int>>()
            val notes = mutableListOf<MidiNote>(); var name = "MIDI Track"
            while (p < end) {
                val (delta, used) = readVar(b, p); p += used; tick += delta
                var status = b[p].toInt() and 0xff
                if (status < 0x80) { require(running >= 0); status = running } else { p++ }
                if (status == 0xff) {
                    val meta = b[p++].toInt() and 0xff; val (n, u) = readVar(b, p); p += u
                    if (meta == 0x03) name = String(b, p, n.toInt(), Charsets.UTF_8)
                    p += n.toInt(); if (meta == 0x2f) { p = end; break }; continue
                }
                if (status == 0xf0 || status == 0xf7) { val (n,u)=readVar(b,p); p+=u+n.toInt(); continue }
                running = status
                val type = status and 0xf0; val ch = status and 0x0f
                when (type) {
                    0x80, 0x90 -> { val pitch=b[p++].toInt() and 0xff; val vel=b[p++].toInt() and 0xff; val key=ch to pitch
                        if (type==0x90 && vel>0) active[key]=tick to vel else active.remove(key)?.let { (start,v) -> notes += MidiNote(UUID.randomUUID().toString(), (start*scale).toLong(), ((tick-start)*scale).toLong().coerceAtLeast(1), pitch, v, ch).normalized() }
                    }
                    0xa0,0xb0,0xe0 -> p += 2
                    0xc0,0xd0 -> p += 1
                    else -> p = end
                }
            }
            active.forEach { (key, value) -> notes += MidiNote(UUID.randomUUID().toString(), (value.first*scale).toLong(), 1, key.second, value.second, key.first) }
            result += ImportedTrack(name, notes.sortedBy { it.startTick })
            p = end
        }
        return result
    }

    private fun buildTrack(clip: MidiClip, channel: Int): ByteArray {
        val events = mutableListOf<Pair<Long, ByteArray>>()
        events += 0L to byteArrayOf(0xFF.toByte(), 0x03, clip.name.length.toByte()) + clip.name.toByteArray()
        clip.notes.map { it.normalized() }.forEach { n ->
            val ch = n.channel.coerceIn(0,15).coerceAtMost(15)
            events += n.startTick to byteArrayOf((0x90 or ch).toByte(), n.pitch.toByte(), n.velocity.toByte())
            events += (n.startTick + n.durationTicks) to byteArrayOf((0x80 or ch).toByte(), n.pitch.toByte(), 0)
        }
        events.sortWith(compareBy<Pair<Long,ByteArray>> { it.first }.thenBy { it.second[0].toInt() and 0xff })
        val body = ByteArrayOutputStream(); var last=0L
        events.forEach { (tick, bytes) -> writeVar(body, tick-last); body.write(bytes); last=tick }
        writeVar(body, 0); body.write(byteArrayOf(0xFF.toByte(),0x2F,0))
        val out=ByteArrayOutputStream(); out.write("MTrk".toByteArray(Charsets.US_ASCII)); writeU32(out, body.size().toLong()); out.write(body.toByteArray()); return out.toByteArray()
    }
    private fun writeU16(o:ByteArrayOutputStream,v:Int){o.write((v ushr 8) and 255);o.write(v and 255)}
    private fun writeU32(o:ByteArrayOutputStream,v:Long){o.write(((v ushr 24) and 255).toInt());o.write(((v ushr 16) and 255).toInt());o.write(((v ushr 8) and 255).toInt());o.write((v and 255).toInt())}
    /** Write a Standard MIDI variable-length quantity (VLQ). */
    private fun writeVar(o: ByteArrayOutputStream, value0: Long) {
        var value = value0.coerceAtLeast(0L)
        var buffer = value and 0x7f

        // Build the VLQ from the least-significant 7-bit groups. Every
        // byte except the last one has its continuation bit set.
        while ((value ushr 7) != 0L) {
            value = value ushr 7
            buffer = (buffer shl 8) or ((value and 0x7f) or 0x80)
        }

        while (true) {
            o.write((buffer and 0xff).toInt())
            if ((buffer and 0x80) == 0L) break
            buffer = buffer ushr 8
        }
    }
    private fun readVar(b:ByteArray,pos:Int):Pair<Long,Int>{var p=pos;var v=0L;var c=0;do{val x=b[p++].toInt() and 255;v=(v shl 7) or (x and 127).toLong();c++}while(x and 128 !=0);return v to c}
    private fun u16(b:ByteArray)=((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
    private fun u32(b:ByteArray)=((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)
}
