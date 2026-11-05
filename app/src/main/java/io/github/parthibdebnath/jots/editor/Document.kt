package io.github.parthibdebnath.jots.editor

import androidx.ink.geometry.Box
import androidx.ink.strokes.Stroke

/**
 * One stroke in a note.
 *
 * [id] is unique within the document. [z] orders strokes back to front; pieces made by
 * splitting a stroke with the eraser keep the original's z, so they stay in the same layer.
 */
class StrokeEntry(
    val id: Long,
    val z: Double,
    val spec: BrushSpec,
    val stroke: Stroke,
) {
    val bounds: Box? = stroke.shape.computeBoundingBox()

    fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val b = bounds ?: return false
        return b.xMax >= left && b.xMin <= right && b.yMax >= top && b.yMin <= bottom
    }
}

/** A reversible change: [removed] strokes go away, [added] strokes appear. */
class Edit(val removed: List<StrokeEntry>, val added: List<StrokeEntry>) {
    val isEmpty get() = removed.isEmpty() && added.isEmpty()
}

/**
 * The strokes of an open note, kept sorted by z, plus undo history.
 * Main-thread only.
 */
class Document(initial: List<StrokeEntry> = emptyList()) {
    private val list = ArrayList<StrokeEntry>(initial.sortedBy { it.z })
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    private var nextZ = (initial.maxOfOrNull { it.z } ?: 0.0) + 1.0

    /** Bumps on every change; used to know when to save and redraw. */
    var revision = 0L
        private set

    val strokes: List<StrokeEntry> get() = list
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Lowest y of any ink, in document units (0 if empty). */
    var contentBottom = computeBottom()
        private set

    fun newId(): Long = nextId++
    fun newZ(): Double = (nextZ++)

    fun entry(spec: BrushSpec, stroke: Stroke, z: Double = newZ()) = StrokeEntry(newId(), z, spec, stroke)

    /** Applies [edit] and records it for undo. */
    fun apply(edit: Edit) {
        if (edit.isEmpty) return
        applyRaw(edit.removed, edit.added)
        undoStack.addLast(edit)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Applies a change that is part of a gesture still in progress (the eraser), without touching
     * history. The gesture records one combined [Edit] with [record] when it ends.
     */
    fun applyLive(removed: List<StrokeEntry>, added: List<StrokeEntry>) {
        if (removed.isEmpty() && added.isEmpty()) return
        applyRaw(removed, added)
    }

    fun record(edit: Edit) {
        if (edit.isEmpty) return
        undoStack.addLast(edit)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Edit? {
        val e = undoStack.removeLastOrNull() ?: return null
        applyRaw(e.added, e.removed)
        redoStack.addLast(e)
        return e
    }

    fun redo(): Edit? {
        val e = redoStack.removeLastOrNull() ?: return null
        applyRaw(e.removed, e.added)
        undoStack.addLast(e)
        return e
    }

    fun byId(id: Long): StrokeEntry? = list.firstOrNull { it.id == id }

    private fun applyRaw(remove: List<StrokeEntry>, add: List<StrokeEntry>) {
        if (remove.isNotEmpty()) {
            val ids = remove.mapTo(HashSet()) { it.id }
            list.removeAll { it.id in ids }
        }
        for (e in add) insertSorted(e)
        contentBottom = computeBottom()
        revision++
    }

    private fun insertSorted(e: StrokeEntry) {
        // Most inserts are new strokes on top, so search from the end.
        var i = list.size
        while (i > 0 && list[i - 1].z > e.z) i--
        list.add(i, e)
    }

    private fun computeBottom(): Float {
        var b = 0f
        for (e in list) {
            val y = e.bounds?.yMax ?: continue
            if (y > b) b = y
        }
        return b
    }

    companion object {
        const val MAX_UNDO = 200
    }
}
