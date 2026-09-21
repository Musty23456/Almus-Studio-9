package com.almus.studio

import com.almus.studio.audio.VocalCleanupAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

class VocalCleanupAnalyzerTest {
    @Test fun detectsAudibleRegionsAndKeepsPadding() {
        val file = File.createTempFile("almus-vocal", ".wav")
        try {
            val sr = 1000
            val frames = 1000
            RandomAccessFile(file, "rw").use { raf ->
                val dataBytes = frames * 2
                raf.writeBytes("RIFF"); write32(raf, 36L + dataBytes); raf.writeBytes("WAVE")
                raf.writeBytes("fmt "); write32(raf, 16); write16(raf, 1); write16(raf, 1); write32(raf, sr.toLong()); write32(raf, sr * 2L); write16(raf, 2); write16(raf, 16)
                raf.writeBytes("data"); write32(raf, dataBytes.toLong())
                repeat(frames) { i ->
                    val sample = if (i in 300..699) (sin(2.0 * PI * i / 20.0) * 12000).toInt() else 0
                    write16(raf, sample.toLong())
                }
            }
            val regions = VocalCleanupAnalyzer.detectAudibleRegions(file, -35f, minSilenceMs = 80, paddingMs = 10)
            assertEquals(1, regions.size)
            assertTrue(regions[0].startFrame <= 300)
            assertTrue(regions[0].endFrame >= 700)
        } finally { file.delete() }
    }

    private fun write16(raf: RandomAccessFile, v: Long) { raf.write((v and 255).toInt()); raf.write(((v shr 8) and 255).toInt()) }
    private fun write32(raf: RandomAccessFile, v: Long) { repeat(4) { i -> raf.write(((v shr (8 * i)) and 255).toInt()) } }
}
