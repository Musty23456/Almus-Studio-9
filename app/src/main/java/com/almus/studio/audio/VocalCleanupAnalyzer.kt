package com.almus.studio.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Lightweight offline analyzer used by the vocal editor to find sustained low-level
 * regions. It never changes the source WAV: cleanup is performed by creating
 * non-destructive clip windows around the detected audible regions. */
object VocalCleanupAnalyzer {
    data class Region(val startFrame: Long, val endFrame: Long) {
        val lengthFrames: Long get() = (endFrame - startFrame).coerceAtLeast(0L)
    }

    private data class Wav(val channels: Int, val bits: Int, val float32: Boolean, val sampleRate: Int, val dataStart: Long, val dataSize: Long)

    fun detectAudibleRegions(
        file: File,
        thresholdDb: Float = -45f,
        minSilenceMs: Long = 120L,
        paddingMs: Long = 20L
    ): List<Region> {
        val wav = readHeader(file) ?: return emptyList()
        if (wav.channels <= 0 || wav.sampleRate <= 0 || wav.dataSize <= 0L) return emptyList()
        val bytesPerSample = wav.bits / 8
        if (bytesPerSample <= 0) return emptyList()
        val frameBytes = bytesPerSample * wav.channels
        val totalFrames = wav.dataSize / frameBytes
        if (totalFrames <= 0) return emptyList()

        val windowFrames = max(64L, wav.sampleRate / 100L) // 10 ms analysis windows
        val minSilenceFrames = max(1L, wav.sampleRate * minSilenceMs / 1000L)
        val paddingFrames = max(0L, wav.sampleRate * paddingMs / 1000L)
        val audible = ArrayList<Boolean>()

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(wav.dataStart)
            val bytes = ByteArray((windowFrames * frameBytes).coerceAtMost(1_048_576L).toInt())
            var frame = 0L
            while (frame < totalFrames) {
                val wantedFrames = min(windowFrames, totalFrames - frame)
                val wantedBytes = (wantedFrames * frameBytes).toInt()
                raf.readFully(bytes, 0, wantedBytes)
                var sum = 0.0
                var count = 0L
                var p = 0
                repeat(wantedFrames.toInt()) {
                    var peak = 0f
                    repeat(wav.channels) {
                        peak = max(peak, kotlin.math.abs(sampleToFloat(bytes, p, wav.bits, wav.float32)))
                        p += bytesPerSample
                    }
                    sum += peak.toDouble() * peak.toDouble()
                    count++
                }
                val rms = sqrt((sum / max(1L, count)).coerceAtLeast(1e-12))
                val db = (20.0 * log10(rms)).toFloat()
                audible += db > thresholdDb
                frame += wantedFrames
            }
        }

        val raw = mutableListOf<Region>()
        var runStart = -1L
        var runEnd = 0L
        for (i in audible.indices) {
            val start = i * windowFrames
            val end = min(totalFrames, start + windowFrames)
            if (audible[i]) {
                if (runStart < 0L) runStart = start
                runEnd = end
            } else if (runStart >= 0L) {
                raw += Region(runStart, runEnd)
                runStart = -1L
            }
        }
        if (runStart >= 0L) raw += Region(runStart, runEnd)

        // Merge short silent gaps. This avoids chopping breaths/word tails into tiny clips.
        val merged = mutableListOf<Region>()
        for (region in raw) {
            val previous = merged.lastOrNull()
            if (previous != null && region.startFrame - previous.endFrame < minSilenceFrames) {
                merged[merged.lastIndex] = Region(previous.startFrame, region.endFrame)
            } else merged += region
        }
        return merged.map {
            Region(
                startFrame = (it.startFrame - paddingFrames).coerceAtLeast(0L),
                endFrame = (it.endFrame + paddingFrames).coerceAtMost(totalFrames)
            )
        }.filter { it.lengthFrames > 0L }
    }

    private fun readHeader(file: File): Wav? {
        if (!file.exists() || file.length() < 44L) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12); raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null
            var channels = 0; var bits = 0; var float32 = false; var sampleRate = 0
            var dataStart = -1L; var dataSize = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4); raf.readFully(idBytes)
                val size = readLe32(raf)
                val id = String(idBytes)
                when (id) {
                    "fmt " -> {
                        val n = size.toInt().coerceAtLeast(16)
                        val fmt = ByteArray(n); raf.readFully(fmt)
                        val format = le16(fmt, 0); channels = le16(fmt, 2); sampleRate = le32(fmt, 4)
                        bits = le16(fmt, 14); float32 = format == 3
                    }
                    "data" -> { dataStart = raf.filePointer; dataSize = size; raf.seek(min(raf.length(), raf.filePointer + size)) }
                    else -> raf.seek(min(raf.length(), raf.filePointer + size))
                }
                if (size % 2L != 0L) raf.seek(min(raf.length(), raf.filePointer + 1))
            }
            return if (dataStart >= 0L) Wav(channels, bits, float32, sampleRate, dataStart, dataSize) else null
        }
    }

    private fun sampleToFloat(b: ByteArray, o: Int, bits: Int, isFloat: Boolean): Float = when {
        isFloat && bits == 32 -> Float.fromBits(le32(b, o))
        bits == 16 -> leSigned16(b, o) / 32768f
        bits == 24 -> { val v = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16); (if (v and 0x800000 != 0) v or -0x1000000 else v) / 8388608f }
        bits == 32 -> le32(b, o) / 2147483648f
        else -> 0f
    }
    private fun readLe32(raf: RandomAccessFile): Long = (raf.read() or (raf.read() shl 8) or (raf.read() shl 16) or (raf.read() shl 24)).toLong() and 0xffffffffL
    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or (b[o + 3].toInt() shl 24)
    private fun leSigned16(b: ByteArray, o: Int): Int { val v = le16(b, o); return if (v and 0x8000 != 0) v or -0x10000 else v }
}
