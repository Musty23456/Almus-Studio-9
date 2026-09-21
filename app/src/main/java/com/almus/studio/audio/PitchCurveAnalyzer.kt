package com.almus.studio.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline vocal pitch-curve analysis for the visual AutoPitch editor. */
object PitchCurveAnalyzer {
    data class Point(val frame: Long, val hz: Float, val midi: Float, val confidence: Float, val voiced: Boolean)
    data class Result(val sampleRate: Int, val totalFrames: Long, val points: List<Point>)

    fun analyze(file: File, maxPoints: Int = 320): Result? {
        val wav = readWav(file) ?: return null
        if (wav.frames < 1024) return null
        val window = 2048
        val hop = 512
        val count = min(maxPoints, max(1, ((wav.frames - window) / hop + 1).toInt()))
        val step = max(1, ((wav.frames - window).toFloat() / max(1, count - 1)).roundToInt())
        val points = ArrayList<Point>(count)
        var frame = 0L
        while (frame + window <= wav.frames && points.size < count) {
            val start = frame.toInt()
            val mono = FloatArray(window)
            var energy = 0f
            for (i in 0 until window) {
                val s = wav.samples[start + i]
                val w = 0.5f - 0.5f * cos(2.0 * Math.PI * i / (window - 1)).toFloat()
                mono[i] = s * w
                energy += s * s
            }
            val rms = sqrt(energy / window)
            if (rms < 0.012f) {
                points += Point(frame, 0f, 0f, 0f, false)
            } else {
                val minLag = max(2, (wav.sampleRate / 1000f).roundToInt())
                val maxLag = min(window / 2, (wav.sampleRate / 55f).roundToInt())
                var bestLag = -1
                var best = 0f
                for (lag in minLag..maxLag) {
                    var sum = 0f
                    var a = 0f
                    var b = 0f
                    for (i in 0 until window - lag) {
                        val x = mono[i]; val y = mono[i + lag]
                        sum += x * y; a += x * x; b += y * y
                    }
                    val score = if (a > 1e-9f && b > 1e-9f) sum / sqrt(a * b) else 0f
                    if (score > best) { best = score; bestLag = lag }
                }
                if (bestLag > 0 && best >= 0.55f) {
                    val hz = wav.sampleRate.toFloat() / bestLag
                    val midi = 69f + 12f * (kotlin.math.ln(hz / 440f) / kotlin.math.ln(2f))
                    points += Point(frame, hz, midi, best.coerceIn(0f, 1f), true)
                } else points += Point(frame, 0f, 0f, best.coerceIn(0f, 1f), false)
            }
            frame += step.toLong()
        }
        return Result(wav.sampleRate, wav.frames, points)
    }

    fun nearestMidi(midi: Float): Int = midi.roundToInt().coerceIn(0, 127)

    private data class Wav(val sampleRate: Int, val frames: Long, val samples: FloatArray)
    private fun readWav(file: File): Wav? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val h = ByteArray(12); raf.readFully(h)
            if (String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") return null
            var rate = 44100; var channels = 1; var bits = 16; var dataStart = -1L; var dataSize = 0L
            while (raf.filePointer < raf.length() - 8) {
                val id = ByteArray(4); raf.readFully(id)
                val sb = ByteArray(4); raf.readFully(sb)
                val size = leInt(sb).toLong() and 0xffffffffL
                when (String(id)) {
                    "fmt " -> { val f = ByteArray(size.toInt()); raf.readFully(f); channels = leShort(f,2); rate = leInt(f,4); bits = leShort(f,14) }
                    "data" -> { dataStart = raf.filePointer; dataSize = size; raf.seek(raf.filePointer + size) }
                    else -> raf.seek(raf.filePointer + size)
                }
                if (size and 1L != 0L) raf.seek(raf.filePointer + 1)
            }
            if (dataStart < 0 || channels < 1 || bits !in listOf(16, 24, 32)) return null
            val bytesPerFrame = channels * bits / 8
            val frames = dataSize / bytesPerFrame
            val needed = min(frames, Int.MAX_VALUE.toLong()).toInt()
            val out = FloatArray(needed)
            raf.seek(dataStart)
            val buf = ByteArray(bytesPerFrame * 4096)
            var pos = 0
            while (pos < needed) {
                val n = min(4096, needed - pos); raf.readFully(buf, 0, n * bytesPerFrame)
                for (i in 0 until n) {
                    var sum = 0f
                    for (ch in 0 until channels) sum += sample(buf, i * bytesPerFrame + ch * bits / 8, bits)
                    out[pos + i] = sum / channels
                }
                pos += n
            }
            Wav(rate, frames, out)
        }
    }.getOrNull()

    private fun sample(b: ByteArray, o: Int, bits: Int): Float = when(bits) {
        16 -> leShortSigned(b,o) / 32768f
        24 -> { val v=(b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16); (if(v and 0x800000!=0) v or -0x1000000 else v)/8388608f }
        else -> leInt(b,o) / 2147483648f
    }
    private fun leShort(b:ByteArray,o:Int)= (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)
    private fun leShortSigned(b:ByteArray,o:Int):Int { val v=leShort(b,o); return if(v and 0x8000!=0) v or -0x10000 else v }
    private fun leInt(b:ByteArray,o:Int=0)= (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or (b[o+3].toInt() shl 24)
}
