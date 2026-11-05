package io.github.parthibdebnath.jots.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.MainActivity
import io.github.parthibdebnath.jots.editor.BrushSpec
import io.github.parthibdebnath.jots.editor.Document
import io.github.parthibdebnath.jots.editor.EditorView
import io.github.parthibdebnath.jots.editor.StrokeEntry
import io.github.parthibdebnath.jots.editor.Tool
import io.github.parthibdebnath.jots.input.Action
import io.github.parthibdebnath.jots.input.ActionTarget
import io.github.parthibdebnath.jots.input.DialMode
import io.github.parthibdebnath.jots.notes.NoteMeta
import io.github.parthibdebnath.jots.notes.Paper
import io.github.parthibdebnath.jots.notes.StrokeCodec
import io.github.parthibdebnath.jots.settings.AppSettings
import io.github.parthibdebnath.jots.settings.EraserMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow

/**
 * The state and behaviour of one open note: owns the [EditorView], mirrors what the toolbar needs
 * as Compose state, saves in the background, and executes actions from buttons and the dial.
 */
class EditorController(
    private val activity: MainActivity,
    private val app: JotsApp,
    val noteId: String,
    private val scope: CoroutineScope,
) : EditorView.Host, ActionTarget {

    val view = EditorView(activity, this)

    var meta by mutableStateOf(app.notes.meta(noteId) ?: NoteMeta(noteId))
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var tool by mutableStateOf(Tool.PEN)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var hasSelection by mutableStateOf(false)
        private set
    var zoomPercent by mutableIntStateOf(100)
        private set
    var pen by mutableStateOf(app.settings.value.pen)
        private set
    var highlighter by mutableStateOf(app.settings.value.highlighter)
        private set
    var eraserMode by mutableStateOf(app.settings.value.eraserMode)
        private set
    var eraserSize by mutableStateOf(app.settings.value.eraserSize)
        private set

    /** A short-lived status line ("Dial: Zoom"). */
    var flash by mutableStateOf<Pair<String, Long>?>(null)
        private set

    private var dirty = false
    private var saveJob: Job? = null
    private val io get() = app.diskIo

    init {
        view.penSpec = pen
        view.highlighterSpec = highlighter
        view.eraserMode = eraserMode
        view.eraserSizeDp = eraserSize
        view.paper = meta.paper
        view.darkPaper = meta.darkPaper
        scope.launch {
            // On the disk queue, so any save still finishing from a previous visit lands first.
            val result = withContext(app.diskIo) { runCatching { app.notes.loadInk(noteId) to app.notes.meta(noteId) } }
            result.onSuccess { (entries, fresh) ->
                if (fresh != null) {
                    meta = fresh
                    view.paper = fresh.paper
                    view.darkPaper = fresh.darkPaper
                }
                view.load(Document(entries), meta.zoom, meta.offsetX, meta.scrollY)
            }.onFailure {
                loadError = "This note couldn't be opened: ${it.message}"
            }
            loading = false
            onEditorStateChanged()
        }
    }

    // region EditorView.Host

    override val settings: AppSettings get() = app.settings.value
    override val heldActions: Set<Action> get() = app.router.heldActions

    override fun onDocumentEdited() {
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            save()
        }
    }

    override fun onEditorStateChanged() {
        tool = view.tool
        canUndo = view.canUndo
        canRedo = view.canRedo
        hasSelection = view.hasSelection
        zoomPercent = view.zoomPercent
    }

    override fun onToggleFocus() = activity.toggleFocus()

    // endregion

    // region Tools

    fun selectTool(t: Tool) {
        view.tool = t
        onEditorStateChanged()
    }

    fun updatePen(spec: BrushSpec) {
        pen = spec
        view.penSpec = spec
        app.settings.update { it.copy(pen = spec) }
    }

    fun updateHighlighter(spec: BrushSpec) {
        highlighter = spec
        view.highlighterSpec = spec
        app.settings.update { it.copy(highlighter = spec) }
    }

    fun setEraser(mode: EraserMode = eraserMode, size: Float = eraserSize) {
        eraserMode = mode
        eraserSize = size.coerceIn(4f, 120f)
        view.eraserMode = mode
        view.eraserSizeDp = eraserSize
        app.settings.update { it.copy(eraserMode = mode, eraserSize = eraserSize) }
    }

    fun setPaper(paper: Paper = meta.paper, dark: Boolean = meta.darkPaper) {
        view.paper = paper
        view.darkPaper = dark
        updateMeta(meta.copy(paper = paper, darkPaper = dark))
        dirty = true
        onDocumentEdited()
    }

    fun rename(title: String) {
        updateMeta(meta.copy(title = title.trim()))
    }

    private fun updateMeta(m: NoteMeta) {
        meta = m
        app.appScope.launch(io) { app.notes.saveMeta(m) }
    }

    /** The ink tool the size/colour actions apply to right now. */
    private val inkTarget: Tool get() = if (tool == Tool.HIGHLIGHTER || (tool != Tool.PEN && view.lastInkTool == Tool.HIGHLIGHTER)) Tool.HIGHLIGHTER else Tool.PEN

    private fun cycleColor(step: Int) {
        val s = settings
        if (inkTarget == Tool.HIGHLIGHTER) {
            val p = s.highlighterPalette.ifEmpty { return }
            val i = p.indexOf(highlighter.color)
            updateHighlighter(highlighter.copy(color = p[(i + step).mod(p.size)]))
            showFlash("Highlighter colour")
        } else {
            val p = s.palette.ifEmpty { return }
            val i = p.indexOf(pen.color)
            updatePen(pen.copy(color = p[(i + step).mod(p.size)]))
            showFlash("Pen colour")
        }
    }

    private fun resize(factor: Float) {
        when {
            tool == Tool.ERASER -> { setEraser(size = eraserSize * factor); showFlash("Eraser ${eraserSize.toInt()}") }
            inkTarget == Tool.HIGHLIGHTER -> { updateHighlighter(highlighter.withSize(highlighter.size * factor)); showFlash("Highlighter %.1f".format(highlighter.size)) }
            else -> { updatePen(pen.withSize(pen.size * factor)); showFlash("Pen %.1f".format(pen.size)) }
        }
    }

    fun showFlash(text: String) {
        flash = text to SystemClock.uptimeMillis()
    }

    // endregion

    // region ActionTarget

    override fun perform(action: Action) {
        if (loading) return
        when (action) {
            Action.NONE, Action.CYCLE_DIAL,
            Action.HOLD_ERASER, Action.HOLD_LASSO, Action.HOLD_PAN, Action.HOLD_HIGHLIGHTER -> Unit
            Action.TOOL_PEN -> selectTool(Tool.PEN)
            Action.TOOL_HIGHLIGHTER -> selectTool(Tool.HIGHLIGHTER)
            Action.TOOL_ERASER -> selectTool(Tool.ERASER)
            Action.TOOL_LASSO -> selectTool(Tool.LASSO)
            Action.TOGGLE_ERASER -> selectTool(if (tool == Tool.ERASER) view.lastInkTool else Tool.ERASER)
            Action.UNDO -> view.undo()
            Action.REDO -> view.redo()
            Action.DELETE_SELECTION -> view.deleteSelection()
            Action.TOGGLE_FOCUS -> activity.toggleFocus()
            Action.FIT_WIDTH -> view.fitWidth()
            Action.ZOOM_IN -> view.zoomBy(1.25f)
            Action.ZOOM_OUT -> view.zoomBy(0.8f)
            Action.PAGE_UP -> view.scrollScreens(-0.9f)
            Action.PAGE_DOWN -> view.scrollScreens(0.9f)
            Action.SCROLL_UP -> view.scrollScreens(-0.25f)
            Action.SCROLL_DOWN -> view.scrollScreens(0.25f)
            Action.NEXT_COLOR -> cycleColor(1)
            Action.PREV_COLOR -> cycleColor(-1)
            Action.SIZE_UP -> resize(1.2f)
            Action.SIZE_DOWN -> resize(1f / 1.2f)
        }
        onEditorStateChanged()
    }

    override fun onDial(mode: DialMode, steps: Int) {
        if (loading || steps == 0) return
        when (mode) {
            DialMode.SCROLL -> view.scrollScreens(steps * settings.dialScrollStep)
            DialMode.ZOOM -> view.zoomBy(1.1f.pow(steps))
            DialMode.BRUSH_SIZE -> resize(1.08f.pow(steps))
            DialMode.UNDO_REDO -> repeat(abs(steps)) { if (steps > 0) view.redo() else view.undo() }
            DialMode.COLOR -> cycleColor(if (steps > 0) 1 else -1)
        }
        onEditorStateChanged()
    }

    override fun onHoldsChanged() = view.onHoldsChanged()

    override fun onDialModeChanged(mode: DialMode) = showFlash("Dial: ${mode.label}")

    // endregion

    // region Saving

    /** Writes the note if it changed, and always records where the user was looking. */
    fun save() {
        if (loading || loadError != null) return
        saveJob?.cancel()
        val v = view.viewport
        val strokes: List<StrokeEntry> = view.document.strokes.toList()
        val wasDirty = dirty
        dirty = false
        val base = meta.copy(zoom = v.zoom, offsetX = v.offsetX, scrollY = v.scrollY)
        val paper = base.paper
        val dark = base.darkPaper
        val next = if (wasDirty) base.copy(modified = System.currentTimeMillis(), strokeCount = strokes.size, thumbVersion = base.thumbVersion + 1) else base
        meta = next
        app.appScope.launch(io) {
            try {
                if (wasDirty) {
                    app.notes.saveInk(noteId, StrokeCodec.encode(strokes))
                    runCatching { app.notes.saveThumb(noteId, EditorView.renderThumbnail(strokes, paper, dark, 360)) }
                }
                app.notes.saveMeta(next)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    dirty = true
                    showFlash("Couldn't save: ${t.message}")
                }
            }
        }
    }

    /** Deletes the open note's file; the caller closes the editor. */
    fun deleteNote() {
        saveJob?.cancel()
        dirty = false
        loadError = "deleted"
        app.appScope.launch(io) { app.notes.delete(noteId) }
    }

    // endregion
}
