package com.almus.studio.data

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Local-only project persistence. No network calls exist anywhere in this class
 * or anything it touches -- projects live entirely under the app's external
 * files directory, which requires no runtime permission on API 26+ and is
 * removed automatically if the user uninstalls the app.
 */

@JsonClass(generateAdapter = true)
data class RecordingRecoverySession(
    val projectId: String,
    val trackId: String,
    val clipFileName: String,
    val startFrame: Long,
    val createdAtEpochMs: Long,
    val sampleRate: Int,
    val channelCount: Int = 1,
    val takeGroupId: String? = null,
    val takeNumber: Int = 0
)

class ProjectRepository(private val context: Context) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    // Non-reified Moshi.adapter(Class<T>) instead of the reified adapter<T>()
    // extension, which requires an @OptIn(ExperimentalStdlibApi::class) opt-in
    // we'd rather not spread around the codebase for one call site.
    private val adapter = moshi.adapter(Project::class.java)
    private val recoveryAdapter = moshi.adapter(RecordingRecoverySession::class.java)

    private val projectsRoot: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val root = File(base, "Projects")
            if (!root.exists()) root.mkdirs()
            return root
        }

    fun projectDir(projectId: String): File = File(projectsRoot, projectId).apply { mkdirs() }

    fun audioDir(projectId: String): File = File(projectDir(projectId), "audio").apply { mkdirs() }

    fun samplesDir(projectId: String): File = File(projectDir(projectId), "samples").apply { mkdirs() }

    fun recoveryFile(projectId: String): File = File(projectDir(projectId), "recording_session.json")

    fun writeRecoverySession(session: RecordingRecoverySession) {
        val target = recoveryFile(session.projectId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(recoveryAdapter.indent("  ").toJson(session))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun readRecoverySession(projectId: String): RecordingRecoverySession? =
        recoveryFile(projectId).takeIf { it.exists() }?.let { runCatching { recoveryAdapter.fromJson(it.readText()) }.getOrNull() }

    fun clearRecoverySession(projectId: String) { recoveryFile(projectId).delete() }

    /** Repairs a float32 WAV header after an interrupted recording. The audio data is
     * already durable because the native writer streamed it directly to disk; only
     * RIFF/data sizes may still contain their zero placeholders. */
    fun repairFloatWavHeader(file: File, sampleRate: Int, channelCount: Int): Long {
        if (!file.exists() || file.length() < 44L || channelCount <= 0 || sampleRate <= 0) return 0L
        val dataBytes = file.length() - 44L
        val frameBytes = channelCount.toLong() * 4L
        if (dataBytes <= 0L || dataBytes % frameBytes != 0L) return 0L
        val riffSize = 36L + dataBytes
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            if (raf.readByte().toInt().toChar() != 'R' || raf.readByte().toInt().toChar() != 'I' ||
                raf.readByte().toInt().toChar() != 'F' || raf.readByte().toInt().toChar() != 'F') return 0L
            fun writeLe32(value: Long) {
                raf.write((value and 0xff).toInt())
                raf.write(((value shr 8) and 0xff).toInt())
                raf.write(((value shr 16) and 0xff).toInt())
                raf.write(((value shr 24) and 0xff).toInt())
            }
            raf.seek(4); writeLe32(riffSize)
            raf.seek(40); writeLe32(dataBytes)
        }
        return dataBytes / frameBytes
    }

    fun scanAndRepairInterruptedRecordings(): List<RecordingRecoverySession> {
        val recovered = mutableListOf<RecordingRecoverySession>()
        val dirs = projectsRoot.listFiles { f -> f.isDirectory } ?: return recovered
        for (dir in dirs) {
            val session = readRecoverySession(dir.name) ?: continue
            val project = load(session.projectId) ?: continue
            val audio = File(audioDir(session.projectId), session.clipFileName)
            val frames = repairFloatWavHeader(audio, session.sampleRate, session.channelCount)
            if (frames <= 0L) {
                clearRecoverySession(session.projectId)
                continue
            }
            val track = project.tracks.find { it.id == session.trackId }
            if (track == null) {
                clearRecoverySession(session.projectId)
                continue
            }
            if (track.clips.none { it.fileName == session.clipFileName }) {
                val clip = AudioClip(
                    id = UUID.randomUUID().toString(),
                    fileName = session.clipFileName,
                    startFrame = session.startFrame,
                    sourceOffsetFrames = 0L,
                    lengthFrames = frames,
                    takeGroupId = session.takeGroupId,
                    takeNumber = session.takeNumber,
                    takeSelected = true
                )
                val updated = project.copy(tracks = project.tracks.map {
                    if (it.id == track.id) it.copy(clips = it.clips + clip) else it
                })
                save(updated)
            }
            clearRecoverySession(session.projectId)
            recovered += session
        }
        return recovered
    }

    fun createProject(name: String, bpm: Int): Project {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val project = Project(
            id = id,
            name = name,
            bpm = bpm,
            createdAtEpochMs = now,
            modifiedAtEpochMs = now,
            tracks = listOf(
                Track(id = UUID.randomUUID().toString(), name = "Track 1", colorHex = TrackColors.forIndex(0)),
                Track(id = UUID.randomUUID().toString(), name = "Track 2", colorHex = TrackColors.forIndex(1))
            )
        )
        save(project)
        return project
    }

    fun save(project: Project) {
        val updated = project.copy(modifiedAtEpochMs = System.currentTimeMillis())
        val file = File(projectDir(project.id), "project.json")
        file.writeText(adapter.indent("  ").toJson(updated))
    }

    fun load(projectId: String): Project? {
        val file = File(projectDir(projectId), "project.json")
        if (!file.exists()) return null
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull()
    }

    fun listProjects(): List<Project> {
        val root = projectsRoot
        val dirs = root.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.mapNotNull { load(it.name) }.sortedByDescending { it.modifiedAtEpochMs }
    }

    fun delete(projectId: String) {
        projectDir(projectId).deleteRecursively()
    }
}
