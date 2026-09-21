package com.almus.studio.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/** Offline PCM16 WAV cache used by the drum sampler. */
class WavSampleCache(private val maxSamples: Int = 32) {
    data class Sample(val sampleRate: Int, val channels: Int, val pcm: ShortArray)
    private val cache = LinkedHashMap<String, Sample>(16, 0.75f, true)

    @Synchronized fun get(file: File): Sample? {
        val key = file.absolutePath
        cache[key]?.let { return it }
        val decoded = read(file) ?: return null
        cache[key] = decoded
        while (cache.size > maxSamples) cache.remove(cache.entries.iterator().next().key)
        return decoded
    }

    @Synchronized fun clear() = cache.clear()

    private fun read(file: File): Sample? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            fun le16(): Int { val a=raf.read(); val b=raf.read(); return (a and 255) or ((b and 255) shl 8) }
            fun le32(): Int { val a=raf.read(); val b=raf.read(); val c=raf.read(); val d=raf.read(); return (a and 255) or ((b and 255) shl 8) or ((c and 255) shl 16) or ((d and 255) shl 24) }
            val riff = ByteArray(4); raf.readFully(riff); val riffSize=le32(); val wave=ByteArray(4); raf.readFully(wave)
            if (String(riff) != "RIFF" || String(wave) != "WAVE") return null
            var format = 0; var channels = 0; var rate = 0; var bits = 0; var dataPos = -1L; var dataSize = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val id=ByteArray(4); raf.readFully(id); val size=le32().toLong() and 0xffffffffL; val next=raf.filePointer + size
                when (String(id)) {
                    "fmt " -> { format=le16(); channels=le16(); rate=le32(); le32(); le16(); bits=le16() }
                    "data" -> { dataPos=raf.filePointer; dataSize=size; break }
                }
                raf.seek(next + (size and 1L))
            }
            if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0 || dataPos < 0 || dataSize <= 0 || dataSize > Int.MAX_VALUE) return null
            val count=(dataSize/2L).toInt(); val pcm=ShortArray(count); raf.seek(dataPos); val bytes=ByteArray(min(64*1024,dataSize.toInt())); var written=0
            while(written<dataSize){ val want=min(bytes.size,dataSize.toInt()-written); raf.readFully(bytes,0,want); var j=0; while(j+1<want){ pcm[written/2]=((bytes[j].toInt() and 255) or (bytes[j+1].toInt() shl 8)).toShort(); j+=2; written+=2 } }
            Sample(rate,channels,pcm)
        }
    }.getOrNull()
}
