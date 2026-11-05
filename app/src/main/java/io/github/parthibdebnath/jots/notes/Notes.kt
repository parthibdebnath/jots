package io.github.parthibdebnath.jots.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import io.github.parthibdebnath.jots.editor.BrushSpec
import io.github.parthibdebnath.jots.editor.Brushes
import io.github.parthibdebnath.jots.editor.InkKind
import io.github.parthibdebnath.jots.editor.PenType
import io.github.parthibdebnath.jots.editor.PressureCurve
import io.github.parthibdebnath.jots.editor.StrokeEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@Serializable
enum class Paper(val label: String) { PLAIN("Plain"), RULED("Lined"), GRID("Grid"), DOTS("Dotted") }

@Serializable
data class NoteMeta(
    val id: String,
    val title: String = "",
    val created: Long = System.currentTimeMillis(),
    val modified: Long = created,
    val paper: Paper = Paper.PLAIN,
    val darkPaper: Boolean = false,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val scrollY: Float = 0f,
    val strokeCount: Int = 0,
    /** Bumped whenever the thumbnail is rewritten, so image caches know to reload. */
    val thumbVersion: Int = 0,
) {
    val displayTitle: String get() = title.ifBlank { "Untitled note" }
}

/** The binary ink format. Brush settings are stored by name so enum reordering can't break it. */
object StrokeCodec {
    private const val MAGIC = 0x4A4F5453 // "JOTS"
    private const val VERSION = 1

    fun encode(strokes: List<StrokeEntry>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(strokes.size)
            val buf = ByteArrayOutputStream()
            for (e in strokes) {
                out.writeLong(e.id)
                out.writeDouble(e.z)
                out.writeUTF(e.spec.kind.name)
                out.writeUTF(e.spec.penType.name)
                out.writeUTF(e.spec.curve.name)
                out.writeInt(e.spec.color)
                out.writeFloat(e.spec.size)
                buf.reset()
                e.stroke.inputs.encode(buf)
                out.writeInt(buf.size())
                buf.writeTo(out)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): List<StrokeEntry> {
        val input = DataInputStream(ByteArrayInputStream(data))
        if (input.readInt() != MAGIC) throw IOException("Not a Jots ink file")
        val version = input.readInt()
        if (version > VERSION) throw IOException("Ink file from a newer version of Jots ($version)")
        val count = input.readInt()
        val out = ArrayList<StrokeEntry>(count)
        repeat(count) {
            val id = input.readLong()
            val z = input.readDouble()
            val spec = BrushSpec(
                kind = enumOr(input.readUTF(), InkKind.PEN),
                penType = enumOr(input.readUTF(), PenType.FOUNTAIN),
                curve = enumOr(input.readUTF(), PressureCurve.NORMAL),
                color = input.readInt(),
                size = input.readFloat(),
            )
            val len = input.readInt()
            val raw = ByteArray(len)
            input.readFully(raw)
            // One unreadable stroke shouldn't take the whole note down with it.
            runCatching {
                val batch = StrokeInputBatch.decode(ByteArrayInputStream(raw))
                if (!batch.isEmpty()) out += StrokeEntry(id, z, spec, Stroke(Brushes.brush(spec), batch))
            }
        }
        return out
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}

/**
 * Notes on disk: `files/notes/<id>/{meta.json, ink.bin, thumb.png}`. Writes go to a temporary file
 * that is then renamed over the old one, so a crash mid-save can't corrupt a note.
 */
class NoteRepository(context: Context) {
    private val root = File(context.filesDir, "notes").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _notes = MutableStateFlow<List<NoteMeta>>(emptyList())
    val notes: StateFlow<List<NoteMeta>> = _notes

    init {
        refresh()
    }

    @Synchronized
    fun refresh() {
        val list = root.listFiles()?.mapNotNull { dir ->
            val f = File(dir, META)
            if (!f.isFile) return@mapNotNull null
            runCatching { json.decodeFromString<NoteMeta>(f.readText()) }.getOrNull()
        }.orEmpty()
        _notes.value = list.sortedByDescending { it.modified }
    }

    fun create(paper: Paper, darkPaper: Boolean): NoteMeta {
        val meta = NoteMeta(id = UUID.randomUUID().toString(), paper = paper, darkPaper = darkPaper)
        saveMeta(meta)
        return meta
    }

    fun meta(id: String): NoteMeta? = _notes.value.firstOrNull { it.id == id }
        ?: runCatching { json.decodeFromString<NoteMeta>(File(dir(id), META).readText()) }.getOrNull()

    fun loadInk(id: String): List<StrokeEntry> {
        val f = File(dir(id), INK)
        if (!f.isFile) return emptyList()
        return StrokeCodec.decode(f.readBytes())
    }

    @Synchronized
    fun saveMeta(meta: NoteMeta) {
        writeAtomically(File(dir(meta.id), META), json.encodeToString(NoteMeta.serializer(), meta).toByteArray())
        _notes.value = (_notes.value.filter { it.id != meta.id } + meta).sortedByDescending { it.modified }
    }

    fun saveInk(id: String, data: ByteArray) = writeAtomically(File(dir(id), INK), data)

    fun saveThumb(id: String, bmp: Bitmap) {
        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        writeAtomically(File(dir(id), THUMB), bytes.toByteArray())
    }

    fun thumb(id: String): Bitmap? {
        val f = File(dir(id), THUMB)
        return if (f.isFile) BitmapFactory.decodeFile(f.path) else null
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _notes.value = _notes.value.filter { it.id != id }
    }

    /** Copies a note's files under a new id; returns the copy. */
    fun duplicate(id: String): NoteMeta? {
        val src = meta(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = src.copy(id = UUID.randomUUID().toString(), title = "${src.displayTitle} (copy)", created = now, modified = now)
        File(dir(id), INK).takeIf { it.isFile }?.let { writeAtomically(File(dir(copy.id), INK), it.readBytes()) }
        File(dir(id), THUMB).takeIf { it.isFile }?.let { writeAtomically(File(dir(copy.id), THUMB), it.readBytes()) }
        saveMeta(copy)
        return copy
    }

    private fun dir(id: String): File {
        // Ids are UUIDs we made; refuse anything that could escape the notes folder.
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) { "Bad note id" }
        return File(root, id).apply { mkdirs() }
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(data)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save ${target.name}")
        }
    }

    private companion object {
        const val META = "meta.json"
        const val INK = "ink.bin"
        const val THUMB = "thumb.png"
    }
}
