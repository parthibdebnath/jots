package io.github.parthibdebnath.jots.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import io.github.parthibdebnath.jots.input.Action
import io.github.parthibdebnath.jots.notes.Paper
import io.github.parthibdebnath.jots.settings.AppSettings
import io.github.parthibdebnath.jots.settings.EraserMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class Tool { PEN, HIGHLIGHTER, ERASER, LASSO }

/**
 * The page: paper, finished ("dry") strokes, the low-latency in-progress ("wet") layer, and an
 * overlay with the scroll bar, hover preview, eraser ring and lasso.
 *
 * All pen input arrives here as ordinary [MotionEvent]s, whether from Android's own stylus support
 * or synthesized by the tablet driver, so both behave identically.
 */
@SuppressLint("ViewConstructor")
class EditorView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        val settings: AppSettings
        /** Hold actions currently held on tablet keys or the keyboard. */
        val heldActions: Set<Action>
        fun onDocumentEdited()
        fun onEditorStateChanged()
        fun onToggleFocus()
    }

    val viewport = Viewport()
    var document = Document()
        private set
    var paper = Paper.RULED
        set(v) { field = v; dryView.invalidate() }
    var darkPaper = false
        set(v) { field = v; dryView.invalidate(); overlay.invalidate() }

    var tool = Tool.PEN
        set(v) {
            if (field == v) return
            if (v != Tool.LASSO) clearSelection()
            if (v != Tool.ERASER && v != Tool.LASSO) lastInkTool = v
            field = v
            overlay.invalidate()
            host.onEditorStateChanged()
        }
    var lastInkTool = Tool.PEN
        private set

    var penSpec = BrushSpec()
    var highlighterSpec = BrushSpec(kind = InkKind.HIGHLIGHTER)
    var eraserMode = EraserMode.PARTIAL
    /** Eraser diameter in dp, so it feels the same at every zoom level. */
    var eraserSizeDp = 14f

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val renderer = CanvasStrokeRenderer.create()
    private val wet = InProgressStrokesView(context)
    private val dryView = DryView(context)
    private val overlay = OverlayView(context)

    private val docToView = Matrix()
    private val viewToDoc = Matrix()

    private val scrollBar = ScrollBar()
    private val scrollStrip = RectF()
    private val stripWidth = 26 * density

    init {
        clipChildren = true
        addView(dryView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = onWetFinished(strokes)
        })
        isFocusable = true
        // Writing often means pausing to think or read; the screen shouldn't time out meanwhile.
        // Applies only while this view is on screen, so the home and settings screens still sleep.
        keepScreenOn = true
    }

    // region Document & viewport

    fun load(doc: Document, zoom: Float, offsetX: Float, scrollY: Float) {
        document = doc
        clearSelection()
        viewport.restore(zoom, offsetX, scrollY, doc.contentBottom)
        viewChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wet.eagerInit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.setSize(w, h, document.contentBottom)
        // The view moved under the pen; the preview reappears with the next hover report.
        hoverVisible = false
        updateStrip()
        viewChanged()
    }

    private fun updateStrip() {
        val left = host.settings.scrollbarOnLeft
        scrollStrip.set(
            if (left) 0f else width - stripWidth, 0f,
            if (left) stripWidth else width.toFloat(), height.toFloat(),
        )
        scrollBar.trackTop = 10 * density
        scrollBar.trackBottom = height - 10 * density
        wet.maskPath = Path().apply { addRect(scrollStrip, Path.Direction.CW) }
    }

    /** Call when settings that affect layout change. */
    fun onSettingsChanged() {
        updateStrip()
        overlay.invalidate()
    }

    private fun viewChanged() {
        viewport.docToView(docToView)
        viewport.viewToDoc(viewToDoc)
        layoutScrollBar()
        dryView.invalidate()
        overlay.invalidate()
        host.onEditorStateChanged()
    }

    private fun layoutScrollBar() {
        scrollBar.layout(
            viewport.scrollY,
            viewport.maxScrollY(document.contentBottom),
            viewport.visibleHeight,
            minThumb = 36 * density,
        )
        applyGestureExclusion()
    }

    // Android's edge-swipe back gesture would otherwise take strokes that start near the screen's
    // sides. It can only be excluded for about 200dp per edge, so while the pen is around, that
    // allowance follows the pen. A hovering pen is seen well before it lands, which gives the
    // system time to apply it. Fingers get normal back gestures again once the pen has gone.
    private var edgeShieldY = Float.NaN
    private val dropEdgeShield = Runnable {
        edgeShieldY = Float.NaN
        applyGestureExclusion()
    }

    private fun shieldEdgesAt(y: Float) {
        if (edgeShieldY.isNaN() || abs(y - edgeShieldY) > EDGE_SHIELD_MOVE_DP * density) {
            edgeShieldY = y
            applyGestureExclusion()
        }
    }

    private fun dropEdgeShieldAfter(ms: Long) {
        removeCallbacks(dropEdgeShield)
        postDelayed(dropEdgeShield, ms)
    }

    private fun applyGestureExclusion() {
        systemGestureExclusionRects = if (edgeShieldY.isNaN()) {
            // Keep the back gesture away from the thumb so a finger can grab it too.
            listOf(
                Rect(
                    scrollStrip.left.toInt(), (scrollBar.thumbTop - 16 * density).toInt(),
                    scrollStrip.right.toInt(), (scrollBar.thumbBottom + 16 * density).toInt(),
                ),
            )
        } else {
            val half = (EDGE_SHIELD_HEIGHT_DP / 2 * density).toInt()
            val top = edgeShieldY.toInt() - half
            val bottom = edgeShieldY.toInt() + half
            val w = (EDGE_SHIELD_WIDTH_DP * density).toInt()
            listOf(Rect(0, top, w, bottom), Rect(width - w, top, width, bottom))
        }
    }

    /**
     * True if a back gesture that just fired is really a pen stroke the system took over: it
     * cancels the stroke first. The safety net for when the edge shield didn't get there in time.
     */
    fun consumePenBackGesture(): Boolean {
        val hit = SystemClock.uptimeMillis() - penCancelledAt < PEN_BACK_WINDOW_MS
        penCancelledAt = -PEN_BACK_WINDOW_MS
        return hit
    }

    val canUndo get() = document.canUndo
    val canRedo get() = document.canRedo
    val zoomPercent get() = (viewport.zoom * 100).toInt()

    fun undo() {
        if (penGesture != PenGesture.NONE) return
        clearSelection()
        if (document.undo() != null) edited()
    }

    fun redo() {
        if (penGesture != PenGesture.NONE) return
        clearSelection()
        if (document.redo() != null) edited()
    }

    private fun edited() {
        layoutScrollBar()
        dryView.invalidate()
        overlay.invalidate()
        host.onDocumentEdited()
        host.onEditorStateChanged()
    }

    /** Scrolls by a fraction of the screen height (negative = up). */
    fun scrollScreens(fraction: Float) {
        if (penGesture == PenGesture.INK) return
        scroller.forceFinished(true)
        if (viewport.scrollBy(0f, viewport.visibleHeight * fraction, document.contentBottom)) viewChanged()
    }

    fun zoomBy(factor: Float, px: Float = hoverX.takeIf { hoverVisible } ?: (width / 2f), py: Float = hoverY.takeIf { hoverVisible } ?: (height / 2f)) {
        if (penGesture == PenGesture.INK) return
        if (viewport.zoomBy(factor, px, py, document.contentBottom)) viewChanged()
    }

    fun fitWidth() {
        if (penGesture == PenGesture.INK) return
        if (viewport.resetZoom(document.contentBottom)) viewChanged()
    }

    // endregion

    // region Input dispatch

    // SNAPPED: the stroke already ended by holding still; the rest of the gesture is ignored.
    private enum class PenGesture { NONE, INK, SNAPPED, ERASE, LASSO, PAN, SCROLLBAR, MOVE_SELECTION, SCALE_SELECTION }

    private enum class Effective { PEN, HIGHLIGHTER, ERASER, LASSO, PAN }

    private var penGesture = PenGesture.NONE
    private var penPointerId = -1
    private var inkStrokeId: InProgressStrokeId? = null
    private val pendingSpecs = HashMap<InProgressStrokeId, BrushSpec>()

    // Shapes: strokes started with auto shape on get recognized when they finish; strokes the
    // user held still at the end are snapped right away with whatever shape was recognized.
    private val autoShapeIds = HashSet<InProgressStrokeId>()
    private val snapShapes = HashMap<InProgressStrokeId, Shape>()
    private val inkPath = Polyline.Builder()
    private var holdX = 0f
    private var holdY = 0f
    private var lastInkEvent: MotionEvent? = null
    private val holdToSnap = Runnable { onHoldStill() }
    private var lastPenX = 0f
    private var lastPenY = 0f
    private var lastPenActivity = 0L
    private var penCancelledAt = -PEN_BACK_WINDOW_MS

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            touchMode = if (isPenLike(ev, 0)) TouchMode.PEN else TouchMode.FINGER
        }
        when (touchMode) {
            TouchMode.PEN -> handlePen(ev)
            TouchMode.FINGER -> handleFingers(ev)
            TouchMode.NONE -> Unit
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) touchMode = TouchMode.NONE
        return true
    }

    private enum class TouchMode { NONE, PEN, FINGER }
    private var touchMode = TouchMode.NONE

    private fun isStylus(ev: MotionEvent, i: Int): Boolean {
        val t = ev.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun isPenLike(ev: MotionEvent, i: Int) =
        isStylus(ev, i) || (host.settings.fingerDrawing && SystemClock.uptimeMillis() - lastPenActivity > 1500)

    private fun resolve(ev: MotionEvent?, index: Int = 0): Effective {
        if (ev != null && ev.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER) return Effective.ERASER
        val holds = HashSet(host.heldActions)
        if (ev != null) {
            val bs = ev.buttonState
            val mouse = ev.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE
            val b1 = bs and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 || (mouse && bs and MotionEvent.BUTTON_SECONDARY != 0)
            val b2 = bs and MotionEvent.BUTTON_STYLUS_SECONDARY != 0 || (mouse && bs and MotionEvent.BUTTON_TERTIARY != 0)
            val pen = host.settings.bindings.pen
            if (b1) pen[0]?.let { holds += it }
            if (b2) pen[1]?.let { holds += it }
        }
        return when {
            Action.HOLD_PAN in holds -> Effective.PAN
            Action.HOLD_ERASER in holds -> Effective.ERASER
            Action.HOLD_LASSO in holds -> Effective.LASSO
            Action.HOLD_HIGHLIGHTER in holds -> Effective.HIGHLIGHTER
            else -> when (tool) {
                Tool.PEN -> Effective.PEN
                Tool.HIGHLIGHTER -> Effective.HIGHLIGHTER
                Tool.ERASER -> Effective.ERASER
                Tool.LASSO -> Effective.LASSO
            }
        }
    }

    private fun handlePen(ev: MotionEvent) {
        lastPenActivity = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                penPointerId = ev.getPointerId(0)
                hoverVisible = false
                penDown(ev)
            }
            MotionEvent.ACTION_MOVE -> penMove(ev)
            MotionEvent.ACTION_UP -> penUp(ev, cancel = false)
            MotionEvent.ACTION_CANCEL -> penUp(ev, cancel = true)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A finger joining a finger-drawn stroke means the user wants to pinch instead.
                if (penGesture == PenGesture.INK && !isStylus(ev, 0)) {
                    inkStrokeId?.let { wet.cancelStroke(it, ev); pendingSpecs.remove(it) }
                    inkStrokeId = null
                    penGesture = PenGesture.NONE
                    touchMode = TouchMode.FINGER
                    handleFingers(ev)
                }
            }
        }
        overlay.invalidate()
    }

    private fun penDown(ev: MotionEvent) {
        val x = ev.x
        val y = ev.y
        lastPenX = x
        lastPenY = y
        removeCallbacks(dropEdgeShield)
        shieldEdgesAt(y)
        // A tablet Android treats as a mouse reports a barrel-button press as a "touch" with no
        // primary button: that's the button, not the tip, so it mustn't draw.
        if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE && ev.buttonState and MotionEvent.BUTTON_PRIMARY == 0) {
            penGesture = PenGesture.NONE
            return
        }
        requestUnbufferedDispatch(ev)

        if (scrollStrip.contains(x, y)) {
            penGesture = PenGesture.SCROLLBAR
            layoutScrollBar()
            scrollBar.down(y, viewport.scrollY, viewport.maxScrollY(document.contentBottom), slop = 8 * density)
            return
        }

        val effective = resolve(ev)
        if (effective == Effective.LASSO && selection.isNotEmpty()) {
            when (hitSelection(x, y)) {
                SelHit.HANDLE -> { penGesture = PenGesture.SCALE_SELECTION; beginTransform(x, y); return }
                SelHit.INSIDE -> { penGesture = PenGesture.MOVE_SELECTION; beginTransform(x, y); return }
                SelHit.OUTSIDE -> clearSelection()
            }
        } else if (selection.isNotEmpty()) {
            clearSelection()
        }

        when (effective) {
            Effective.PEN, Effective.HIGHLIGHTER -> {
                val spec = if (effective == Effective.PEN) penSpec else highlighterSpec
                val id = wet.startStroke(ev, penPointerId, Brushes.brush(spec), viewToDoc)
                pendingSpecs[id] = spec
                if (host.settings.autoShape) autoShapeIds += id
                inkStrokeId = id
                penGesture = PenGesture.INK
                inkPath.clear()
                inkPath.add(viewport.toDocX(x), viewport.toDocY(y), 0, 0f)
                rememberInkEvent(ev)
                holdX = x; holdY = y
                armHold()
            }
            Effective.ERASER -> {
                penGesture = PenGesture.ERASE
                erase = EraseSession()
                erase!!.to(viewport.toDocX(x), viewport.toDocY(y))
                eraserX = x; eraserY = y
            }
            Effective.LASSO -> {
                penGesture = PenGesture.LASSO
                lasso.reset()
                lasso.add(viewport.toDocX(x), viewport.toDocY(y))
            }
            Effective.PAN -> penGesture = PenGesture.PAN
        }
    }

    private fun penMove(ev: MotionEvent) {
        val idx = ev.findPointerIndex(penPointerId).takeIf { it >= 0 } ?: return
        when (penGesture) {
            PenGesture.INK -> inkStrokeId?.let {
                runCatching { wet.addToStroke(ev, penPointerId, it) }
                for (h in 0 until ev.historySize) inkPath.add(viewport.toDocX(ev.getHistoricalX(idx, h)), viewport.toDocY(ev.getHistoricalY(idx, h)), 0, 0f)
                inkPath.add(viewport.toDocX(ev.getX(idx)), viewport.toDocY(ev.getY(idx)), 0, 0f)
                rememberInkEvent(ev)
                // Any real movement restarts the "hold still to snap" clock.
                if (hypot(ev.getX(idx) - holdX, ev.getY(idx) - holdY) > HOLD_SLOP_DP * density) {
                    holdX = ev.getX(idx); holdY = ev.getY(idx)
                    armHold()
                }
            }
            PenGesture.SNAPPED -> Unit
            PenGesture.ERASE -> {
                val e = erase ?: return
                for (h in 0 until ev.historySize) e.to(viewport.toDocX(ev.getHistoricalX(idx, h)), viewport.toDocY(ev.getHistoricalY(idx, h)))
                e.to(viewport.toDocX(ev.getX(idx)), viewport.toDocY(ev.getY(idx)))
                eraserX = ev.getX(idx); eraserY = ev.getY(idx)
            }
            PenGesture.LASSO -> {
                for (h in 0 until ev.historySize) lasso.add(viewport.toDocX(ev.getHistoricalX(idx, h)), viewport.toDocY(ev.getHistoricalY(idx, h)))
                lasso.add(viewport.toDocX(ev.getX(idx)), viewport.toDocY(ev.getY(idx)))
            }
            PenGesture.PAN -> {
                val dx = ev.getX(idx) - lastPenX
                val dy = ev.getY(idx) - lastPenY
                if (viewport.scrollBy(-dx / viewport.scale, -dy / viewport.scale, document.contentBottom)) viewChanged()
            }
            PenGesture.SCROLLBAR -> {
                val r = scrollBar.move(ev.getY(idx), touchSlop)
                val moved = if (!r.scrollTo.isNaN()) viewport.scrollTo(r.scrollTo, document.contentBottom)
                else if (r.panDeltaPx != 0f) viewport.scrollBy(0f, -r.panDeltaPx / viewport.scale, document.contentBottom)
                else false
                if (moved) viewChanged() else overlay.invalidate()
            }
            PenGesture.MOVE_SELECTION, PenGesture.SCALE_SELECTION -> updateTransform(ev.getX(idx), ev.getY(idx))
            PenGesture.NONE -> Unit
        }
        lastPenX = ev.getX(idx)
        lastPenY = ev.getY(idx)
    }

    private fun penUp(ev: MotionEvent, cancel: Boolean) {
        dropEdgeShieldAfter(EDGE_SHIELD_LINGER_MS)
        if (cancel && penGesture != PenGesture.NONE) penCancelledAt = SystemClock.uptimeMillis()
        removeCallbacks(holdToSnap)
        lastInkEvent?.recycle()
        lastInkEvent = null
        when (penGesture) {
            PenGesture.SNAPPED -> Unit
            PenGesture.INK -> inkStrokeId?.let { id ->
                if (cancel) {
                    wet.cancelStroke(id, ev)
                    pendingSpecs.remove(id)
                    autoShapeIds.remove(id)
                } else {
                    runCatching { wet.finishStroke(ev, penPointerId, id) }
                }
            }
            PenGesture.ERASE -> {
                erase?.finish()
                erase = null
            }
            PenGesture.LASSO -> if (!cancel) selectWithLasso()
            PenGesture.SCROLLBAR -> {
                val page = scrollBar.up(ev.y)
                if (page != 0 && !cancel) scrollScreens(page * 0.9f) else layoutScrollBar()
            }
            PenGesture.MOVE_SELECTION, PenGesture.SCALE_SELECTION -> if (cancel) cancelTransform() else commitTransform()
            PenGesture.PAN, PenGesture.NONE -> Unit
        }
        lasso.reset()
        inkStrokeId = null
        penGesture = PenGesture.NONE
        penPointerId = -1
        overlay.invalidate()
    }

    private fun onWetFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val shaped = ArrayList<Pair<StrokeEntry, Shape>>()
        val added = strokes.map { (id, stroke) ->
            val spec = pendingSpecs.remove(id) ?: penSpec
            val entry = document.entry(spec, stroke)
            val shape = snapShapes.remove(id) ?: if (autoShapeIds.contains(id)) {
                val line = InkOps.toPolyline(stroke.inputs)
                ShapeRecognizer.recognize(line.x, line.y)
            } else null
            autoShapeIds.remove(id)
            if (shape != null) shaped += entry to shape
            entry
        }
        document.apply(Edit(emptyList(), added))
        // The freehand stroke and its clean replacement are separate undo steps, so a wrong
        // guess is one undo away from what was actually drawn.
        for ((entry, shape) in shaped) {
            val clean = shapeStroke(entry, shape) ?: continue
            document.apply(Edit(listOf(entry), listOf(clean)))
        }
        wet.removeFinishedStrokes(strokes.keys)
        edited()
    }

    private fun rememberInkEvent(ev: MotionEvent) {
        lastInkEvent?.recycle()
        lastInkEvent = MotionEvent.obtain(ev)
    }

    private fun armHold() {
        removeCallbacks(holdToSnap)
        if (host.settings.holdToSnap) postDelayed(holdToSnap, HOLD_MS)
    }

    /** The pen has stayed put at the end of a stroke: snap it, if it's a recognizable shape. */
    private fun onHoldStill() {
        val id = inkStrokeId ?: return
        if (penGesture != PenGesture.INK) return
        val ev = lastInkEvent ?: return
        val path = inkPath.build()
        val shape = ShapeRecognizer.recognize(path.x, path.y, lenient = true) ?: return
        snapShapes[id] = shape
        runCatching { wet.finishStroke(ev, penPointerId, id) }
        penGesture = PenGesture.SNAPPED
        inkStrokeId = null
        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    /** A clean stroke tracing [shape], drawn with [raw]'s brush at its typical pressure. */
    private fun shapeStroke(raw: StrokeEntry, shape: Shape): StrokeEntry? {
        val (xs, ys) = ShapeRecognizer.outline(shape, step = 2f)
        if (xs.size < 2) return null
        val line = InkOps.toPolyline(raw.stroke.inputs)
        val pressures = line.p.filter { it >= 0f }.sorted()
        val p = if (pressures.isEmpty()) -1f else pressures[pressures.size / 2]
        // Generous time steps keep Ink's input smoothing from rounding off the corners.
        val poly = Polyline(xs, ys, LongArray(xs.size) { it * 8L }, FloatArray(xs.size) { p })
        val stroke = InkOps.rebuild(raw.spec, raw.stroke.inputs, poly) ?: return null
        return StrokeEntry(document.newId(), raw.z, raw.spec, stroke)
    }

    // Hover: fed from the activity, so it works for both real and synthesized pen events.

    private var hoverVisible = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverEffective = Effective.PEN

    /** [x], [y] in this view's coordinates; [ev] for button state. */
    fun onPenHover(ev: MotionEvent, x: Float, y: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_EXIT -> hoverVisible = false
            else -> {
                hoverVisible = x >= 0 && y >= 0 && x <= width && y <= height
                hoverX = x
                hoverY = y
                hoverEffective = resolve(ev)
                lastPenActivity = SystemClock.uptimeMillis()
                if (hoverVisible) {
                    shieldEdgesAt(y)
                    dropEdgeShieldAfter(EDGE_SHIELD_LINGER_MS)
                }
            }
        }
        overlay.invalidate()
    }

    fun hideHover() {
        if (!hoverVisible) return
        hoverVisible = false
        overlay.invalidate()
    }

    /** Held keys changed: refresh the hover preview so it shows the active tool. */
    fun onHoldsChanged() {
        if (hoverVisible) {
            hoverEffective = resolve(null)
            overlay.invalidate()
        }
    }

    // endregion

    // region Fingers: pan, pinch, fling, two-finger double-tap

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaled = true
            if (viewport.zoomBy(d.scaleFactor, d.focusX, d.focusY, document.contentBottom)) viewChanged()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var fingerDownX = 0f
    private var fingerDownY = 0f
    private var fingerMoved = false
    private var scaled = false
    private var maxPointers = 0
    private var multiDownAt = 0L
    private var lastTwoFingerTap = 0L
    private var fingerScrollbar = false
    private var flingLastX = 0
    private var flingLastY = 0

    private fun handleFingers(ev: MotionEvent) {
        if (penGesture != PenGesture.NONE) return // Palm while the pen is busy.
        if (SystemClock.uptimeMillis() - lastPenActivity < PALM_GRACE_MS && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchMode = TouchMode.NONE
            return
        }
        scaleDetector.onTouchEvent(ev)
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(ev)

        val (fx, fy) = focus(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDownX = ev.x; fingerDownY = ev.y
                fingerMoved = false; scaled = false; maxPointers = 1
                fingerScrollbar = scrollStrip.contains(ev.x, ev.y)
                if (fingerScrollbar) {
                    layoutScrollBar()
                    scrollBar.down(ev.y, viewport.scrollY, viewport.maxScrollY(document.contentBottom), slop = 12 * density)
                    // Only the thumb is special for fingers; elsewhere the strip pans like the page.
                    if (scrollBar.mode != ScrollBar.Mode.THUMB) { scrollBar.cancel(); fingerScrollbar = false }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, ev.pointerCount)
                if (ev.pointerCount == 2) multiDownAt = SystemClock.uptimeMillis()
                if (fingerScrollbar) { scrollBar.cancel(); fingerScrollbar = false }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.x - fingerDownX, ev.y - fingerDownY) > touchSlop) fingerMoved = true
                if (fingerScrollbar) {
                    val r = scrollBar.move(ev.y, touchSlop)
                    if (!r.scrollTo.isNaN() && viewport.scrollTo(r.scrollTo, document.contentBottom)) viewChanged()
                } else if (fingerMoved || ev.pointerCount > 1) {
                    val dx = fx - lastFocusX
                    val dy = fy - lastFocusY
                    if (viewport.scrollBy(-dx / viewport.scale, -dy / viewport.scale, document.contentBottom)) viewChanged()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> Unit
            MotionEvent.ACTION_UP -> {
                if (fingerScrollbar) {
                    scrollBar.up(ev.y); fingerScrollbar = false; layoutScrollBar(); overlay.invalidate()
                } else if (maxPointers == 1 && fingerMoved) {
                    val v = velocity!!
                    v.computeCurrentVelocity(1000)
                    fling(v.xVelocity, v.yVelocity)
                } else if (maxPointers == 2 && !fingerMoved && !scaled && SystemClock.uptimeMillis() - multiDownAt < 300) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTwoFingerTap < 450) {
                        lastTwoFingerTap = 0
                        host.onToggleFocus()
                    } else lastTwoFingerTap = now
                }
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (fingerScrollbar) { scrollBar.cancel(); fingerScrollbar = false }
                velocity?.recycle(); velocity = null
            }
        }
        // Re-anchor on every pointer change so adding or lifting a finger doesn't jump the page.
        val (nx, ny) = focus(ev, excludeUp = true)
        lastFocusX = nx
        lastFocusY = ny
    }

    private fun focus(ev: MotionEvent, excludeUp: Boolean = false): Pair<Float, Float> {
        val skip = if (excludeUp && ev.actionMasked == MotionEvent.ACTION_POINTER_UP) ev.actionIndex else -1
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            sx += ev.getX(i); sy += ev.getY(i); n++
        }
        return if (n == 0) ev.x to ev.y else (sx / n) to (sy / n)
    }

    private fun fling(vx: Float, vy: Float) {
        if (abs(vy) < 200 && abs(vx) < 200) return
        flingLastX = 0; flingLastY = 0
        scroller.fling(0, 0, -vx.toInt(), -vy.toInt(), Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
        removeCallbacks(flingStep)
        postOnAnimation(flingStep)
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val dx = scroller.currX - flingLastX
            val dy = scroller.currY - flingLastY
            flingLastX = scroller.currX
            flingLastY = scroller.currY
            if (viewport.scrollBy(dx / viewport.scale, dy / viewport.scale, document.contentBottom)) {
                viewChanged()
                postOnAnimation(this)
            } else {
                scroller.forceFinished(true)
            }
        }
    }

    // endregion

    // region Eraser

    private var erase: EraseSession? = null
    private var eraserX = 0f
    private var eraserY = 0f

    private inner class EraseSession {
        private val removedOriginals = LinkedHashMap<Long, StrokeEntry>()
        private val addedPieces = LinkedHashMap<Long, StrokeEntry>()
        private var lastX = Float.NaN
        private var lastY = Float.NaN

        private val radius get() = eraserSizeDp * density / 2f / viewport.scale

        /** Erases along the path from the previous point to ([x], [y]), in document units. */
        fun to(x: Float, y: Float) {
            val r = radius
            if (lastX.isNaN()) {
                at(x, y, r)
            } else {
                val d = hypot(x - lastX, y - lastY)
                val steps = max(1, ceil(d / (r * 0.5f)).toInt())
                for (i in 1..steps) {
                    val f = i.toFloat() / steps
                    at(lastX + (x - lastX) * f, lastY + (y - lastY) * f, r)
                }
            }
            lastX = x
            lastY = y
        }

        private fun at(cx: Float, cy: Float, r: Float) {
            val box = ImmutableBox.fromCenterAndDimensions(ImmutableVec(cx, cy), 2 * r, 2 * r)
            val hits = document.strokes.filter {
                it.intersects(cx - r, cy - r, cx + r, cy + r) && box.intersects(it.stroke.shape, AffineTransform.IDENTITY)
            }
            if (hits.isEmpty()) return
            val removed = ArrayList<StrokeEntry>()
            val added = ArrayList<StrokeEntry>()
            for (e in hits) {
                if (eraserMode == EraserMode.STROKE) {
                    removed += e
                    continue
                }
                val reach = r + e.spec.size * 0.35f
                val pieces = Geometry.eraseCircle(InkOps.toPolyline(e.stroke.inputs), cx, cy, reach) ?: continue
                removed += e
                for (p in pieces) {
                    val s = InkOps.rebuild(e.spec, e.stroke.inputs, p) ?: continue
                    added += StrokeEntry(document.newId(), e.z, e.spec, s)
                }
            }
            if (removed.isEmpty()) return
            document.applyLive(removed, added)
            for (e in removed) if (addedPieces.remove(e.id) == null) removedOriginals[e.id] = e
            for (e in added) addedPieces[e.id] = e
            dryView.invalidate()
        }

        fun finish() {
            document.record(Edit(removedOriginals.values.toList(), addedPieces.values.toList()))
            if (removedOriginals.isNotEmpty()) edited()
        }
    }

    // endregion

    // region Lasso & selection

    private val lasso = LassoPath()
    private var selection: List<StrokeEntry> = emptyList()
    private val selBounds = RectF()
    private var transforming = false
    private var selDx = 0f
    private var selDy = 0f
    private var selScale = 1f
    private var transformStartX = 0f
    private var transformStartY = 0f
    private val hiddenIds = HashSet<Long>()

    val hasSelection get() = selection.isNotEmpty()

    private class LassoPath {
        var pts = FloatArray(256)
        var n = 0
        fun reset() { n = 0 }
        fun add(x: Float, y: Float) {
            if (n >= 2 && pts[n - 2] == x && pts[n - 1] == y) return
            if (n + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
            pts[n++] = x; pts[n++] = y
        }
        fun polygon() = pts.copyOf(n)
    }

    private fun selectWithLasso() {
        val poly = lasso.polygon()
        if (poly.size < 6) return
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in poly.indices step 2) {
            l = min(l, poly[i]); r = max(r, poly[i]); t = min(t, poly[i + 1]); b = max(b, poly[i + 1])
        }
        selection = document.strokes.filter { e ->
            if (!e.intersects(l, t, r, b)) return@filter false
            val line = InkOps.toPolyline(e.stroke.inputs)
            if (line.size <= 2) {
                val bb = e.bounds ?: return@filter false
                Geometry.pointInPolygon((bb.xMin + bb.xMax) / 2, (bb.yMin + bb.yMax) / 2, poly)
            } else Geometry.fractionInside(line, poly) >= 0.5f
        }
        updateSelBounds()
        host.onEditorStateChanged()
    }

    private fun updateSelBounds() {
        if (selection.isEmpty()) { selBounds.setEmpty(); return }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (e in selection) {
            val bb = e.bounds ?: continue
            l = min(l, bb.xMin); t = min(t, bb.yMin); r = max(r, bb.xMax); b = max(b, bb.yMax)
        }
        selBounds.set(l, t, r, b)
    }

    fun clearSelection() {
        if (selection.isEmpty() && !transforming) return
        selection = emptyList()
        transforming = false
        hiddenIds.clear()
        selBounds.setEmpty()
        dryView.invalidate()
        overlay.invalidate()
        host.onEditorStateChanged()
    }

    private enum class SelHit { HANDLE, INSIDE, OUTSIDE }

    private fun selViewRect(out: RectF): RectF {
        val pad = 8 * density
        out.set(
            viewport.toViewX(selBounds.left) - pad, viewport.toViewY(selBounds.top) - pad,
            viewport.toViewX(selBounds.right) + pad, viewport.toViewY(selBounds.bottom) + pad,
        )
        return out
    }

    private val tmpRect = RectF()

    private fun hitSelection(x: Float, y: Float): SelHit {
        val r = selViewRect(tmpRect)
        if (hypot(x - r.right, y - r.bottom) < 22 * density) return SelHit.HANDLE
        return if (r.contains(x, y)) SelHit.INSIDE else SelHit.OUTSIDE
    }

    private fun beginTransform(x: Float, y: Float) {
        transforming = true
        transformStartX = x
        transformStartY = y
        selDx = 0f; selDy = 0f; selScale = 1f
        hiddenIds.clear()
        selection.forEach { hiddenIds += it.id }
        dryView.invalidate()
    }

    private fun updateTransform(x: Float, y: Float) {
        if (penGesture == PenGesture.MOVE_SELECTION) {
            selDx = (x - transformStartX) / viewport.scale
            selDy = (y - transformStartY) / viewport.scale
        } else {
            // Scale about the top-left corner, following the handle's diagonal distance.
            val ox = viewport.toViewX(selBounds.left)
            val oy = viewport.toViewY(selBounds.top)
            val d0 = hypot(transformStartX - ox, transformStartY - oy)
            val d1 = hypot(x - ox, y - oy)
            selScale = if (d0 > 1f) (d1 / d0).coerceIn(0.1f, 10f) else 1f
        }
        dryView.invalidate()
        overlay.invalidate()
    }

    private fun selMatrix(out: Matrix): Matrix {
        out.setScale(selScale, selScale, selBounds.left, selBounds.top)
        out.postTranslate(selDx, selDy)
        return out
    }

    private fun cancelTransform() {
        transforming = false
        hiddenIds.clear()
        selDx = 0f; selDy = 0f; selScale = 1f
        dryView.invalidate()
    }

    private fun commitTransform() {
        transforming = false
        hiddenIds.clear()
        if (selDx == 0f && selDy == 0f && selScale == 1f) { dryView.invalidate(); return }
        val s = selScale
        val tx = selBounds.left * (1 - s) + selDx
        val ty = selBounds.top * (1 - s) + selDy
        replaceSelection { e ->
            val spec = e.spec.copy(size = (e.spec.size * s).coerceAtLeast(0.1f))
            Stroke(Brushes.brush(spec), InkOps.transformed(e.stroke.inputs, s, tx, ty)).let { spec to it }
        }
        selDx = 0f; selDy = 0f; selScale = 1f
    }

    private fun replaceSelection(transform: (StrokeEntry) -> Pair<BrushSpec, Stroke>?) {
        val old = selection
        val new = old.mapNotNull { e ->
            val (spec, stroke) = runCatching { transform(e) }.getOrNull() ?: return@mapNotNull null
            StrokeEntry(document.newId(), e.z, spec, stroke)
        }
        document.apply(Edit(old, new))
        selection = new
        updateSelBounds()
        edited()
    }

    /** Removes every stroke, as one undoable step. */
    fun clearAll() {
        if (penGesture != PenGesture.NONE || document.strokes.isEmpty()) return
        clearSelection()
        document.apply(Edit(document.strokes.toList(), emptyList()))
        edited()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        document.apply(Edit(selection, emptyList()))
        selection = emptyList()
        selBounds.setEmpty()
        edited()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        val off = 24f
        val copies = selection.map { e ->
            StrokeEntry(document.newId(), document.newZ(), e.spec, Stroke(e.stroke.brush, InkOps.transformed(e.stroke.inputs, 1f, off, off)))
        }
        document.apply(Edit(emptyList(), copies))
        selection = copies
        updateSelBounds()
        edited()
    }

    fun recolorSelection(color: Int) {
        if (selection.isEmpty()) return
        replaceSelection { e ->
            // Highlighter strokes keep their translucency.
            val c = if (e.spec.kind == InkKind.HIGHLIGHTER) (color and 0x00FFFFFF) or (e.spec.color and 0xFF000000.toInt()) else color
            val spec = e.spec.copy(color = c)
            spec to e.stroke.copy(Brushes.brush(spec))
        }
    }

    // endregion

    // region Drawing

    private inner class DryView(context: Context) : View(context) {
        private val paperPaint = Paint()
        private val linePaint = Paint().apply { strokeWidth = 1f }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val selMatrix = Matrix()
        private val combined = Matrix()

        override fun onDraw(canvas: Canvas) {
            drawPaper(canvas)
            val vl = viewport.offsetX
            val vt = viewport.scrollY
            val vr = vl + viewport.visibleWidth
            val vb = vt + viewport.visibleHeight
            canvas.save()
            canvas.concat(docToView)
            for (e in document.strokes) {
                if (e.id in hiddenIds || !e.intersects(vl, vt, vr, vb)) continue
                renderer.draw(canvas, e.stroke, docToView)
            }
            canvas.restore()
            if (transforming && selection.isNotEmpty()) {
                selMatrix(selMatrix)
                combined.set(selMatrix)
                combined.postConcat(docToView)
                canvas.save()
                canvas.concat(combined)
                for (e in selection) renderer.draw(canvas, e.stroke, combined)
                canvas.restore()
            }
        }

        private fun drawPaper(canvas: Canvas) {
            paperPaint.color = if (darkPaper) PAPER_DARK else PAPER_LIGHT
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
            if (paper == Paper.PLAIN) return
            val spacing = LINE_SPACING
            val color = if (darkPaper) 0x33FFFFFF else 0x2A1E3A5F
            linePaint.color = color
            dotPaint.color = if (darkPaper) 0x55FFFFFF else 0x551E3A5F
            val top = viewport.scrollY
            val bottom = top + viewport.visibleHeight
            val first = floor(top / spacing).toInt()
            val last = ceil(bottom / spacing).toInt()
            linePaint.strokeWidth = max(1f, density * 0.6f)
            when (paper) {
                Paper.RULED -> for (k in max(1, first)..last) {
                    val y = viewport.toViewY(k * spacing)
                    canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                }
                Paper.GRID -> {
                    for (k in first..last) {
                        val y = viewport.toViewY(k * spacing)
                        canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                    }
                    var k = floor(viewport.offsetX / spacing).toInt()
                    while (k * spacing <= viewport.offsetX + viewport.visibleWidth) {
                        val x = viewport.toViewX(k * spacing)
                        canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
                        k++
                    }
                }
                Paper.DOTS -> {
                    val r = max(1.2f, density * 1.1f * viewport.zoom.pow(0.5f))
                    for (j in max(1, first)..last) {
                        val y = viewport.toViewY(j * spacing)
                        var k = max(1, floor(viewport.offsetX / spacing).toInt())
                        while (k * spacing < Viewport.DOC_WIDTH && k * spacing <= viewport.offsetX + viewport.visibleWidth) {
                            canvas.drawCircle(viewport.toViewX(k * spacing), y, r, dotPaint)
                            k++
                        }
                    }
                }
                Paper.PLAIN -> Unit
            }
        }
    }

    private inner class OverlayView(context: Context) : View(context) {
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        private val path = Path()
        private val rect = RectF()
        private val m = Matrix()

        override fun onDraw(canvas: Canvas) {
            val fg = if (darkPaper) Color.WHITE else 0xFF1E2430.toInt()
            dashPaint.strokeWidth = 1.5f * density
            ringPaint.strokeWidth = 1.2f * density

            // Lasso in progress.
            if (penGesture == PenGesture.LASSO && lasso.n >= 4) {
                path.reset()
                path.moveTo(viewport.toViewX(lasso.pts[0]), viewport.toViewY(lasso.pts[1]))
                var i = 2
                while (i < lasso.n) { path.lineTo(viewport.toViewX(lasso.pts[i]), viewport.toViewY(lasso.pts[i + 1])); i += 2 }
                dashPaint.color = ACCENT
                canvas.drawPath(path, dashPaint)
            }

            // Selection box and scale handle.
            if (selection.isNotEmpty()) {
                selViewRect(rect)
                if (transforming) {
                    selMatrix(m)
                    m.postConcat(docToView)
                    val pad = 8 * density
                    rect.set(selBounds)
                    m.mapRect(rect)
                    rect.inset(-pad, -pad)
                }
                dashPaint.color = ACCENT
                canvas.drawRect(rect, dashPaint)
                fillPaint.color = ACCENT
                canvas.drawCircle(rect.right, rect.bottom, 7 * density, fillPaint)
                fillPaint.color = Color.WHITE
                canvas.drawCircle(rect.right, rect.bottom, 3.5f * density, fillPaint)
            }

            // Eraser ring while erasing.
            if (penGesture == PenGesture.ERASE) {
                ringPaint.color = fg
                canvas.drawCircle(eraserX, eraserY, eraserSizeDp * density / 2f, ringPaint)
            }

            // Hover preview: where the pen will land, and how big the mark will be.
            if (hoverVisible && penGesture == PenGesture.NONE && !scrollStrip.contains(hoverX, hoverY)) {
                ringPaint.color = fg and 0xB0FFFFFF.toInt()
                when (hoverEffective) {
                    Effective.PEN, Effective.HIGHLIGHTER -> {
                        val spec = if (hoverEffective == Effective.PEN) penSpec else highlighterSpec
                        val r = max(1.5f * density, spec.size * viewport.scale / 2f)
                        fillPaint.color = (spec.color and 0x00FFFFFF) or 0x66000000
                        canvas.drawCircle(hoverX, hoverY, r, fillPaint)
                        canvas.drawCircle(hoverX, hoverY, r + 1.5f * density, ringPaint)
                    }
                    Effective.ERASER -> canvas.drawCircle(hoverX, hoverY, eraserSizeDp * density / 2f, ringPaint)
                    Effective.LASSO -> {
                        val a = 6 * density
                        canvas.drawLine(hoverX - a, hoverY, hoverX + a, hoverY, ringPaint)
                        canvas.drawLine(hoverX, hoverY - a, hoverX, hoverY + a, ringPaint)
                    }
                    Effective.PAN -> {
                        canvas.drawCircle(hoverX, hoverY, 9 * density, ringPaint)
                        val a = 4 * density
                        canvas.drawLine(hoverX, hoverY - a, hoverX, hoverY + a, ringPaint)
                    }
                }
            }

            drawScrollBar(canvas)
        }

        private fun drawScrollBar(canvas: Canvas) {
            val active = penGesture == PenGesture.SCROLLBAR || fingerScrollbar
            val hovering = hoverVisible && scrollStrip.contains(hoverX, hoverY)
            val w = (if (active || hovering) 8f else 4.5f) * density
            val cx = if (host.settings.scrollbarOnLeft) scrollStrip.left + 6 * density else scrollStrip.right - 6 * density
            val base = if (darkPaper) 0xFFFFFFFF.toInt() else 0xFF2A3140.toInt()
            trackPaint.color = (base and 0x00FFFFFF) or 0x14000000
            canvas.drawRoundRect(cx - w / 2, scrollBar.trackTop, cx + w / 2, scrollBar.trackBottom, w / 2, w / 2, trackPaint)
            thumbPaint.color = (base and 0x00FFFFFF) or (if (active) 0xA0000000.toInt() else if (hovering) 0x80000000.toInt() else 0x55000000)
            canvas.drawRoundRect(cx - w / 2, scrollBar.thumbTop, cx + w / 2, scrollBar.thumbBottom, w / 2, w / 2, thumbPaint)
        }
    }

    // endregion

    companion object {
        const val LINE_SPACING = 36f
        const val PAPER_LIGHT = 0xFFFFFFFF.toInt()
        const val PAPER_DARK = 0xFF1C1D21.toInt()
        const val ACCENT = 0xFF3D6DF2.toInt()
        private const val PALM_GRACE_MS = 400L
        private const val HOLD_MS = 500L
        private const val EDGE_SHIELD_HEIGHT_DP = 190f // Android allows 200dp per edge.
        private const val EDGE_SHIELD_WIDTH_DP = 60f
        private const val EDGE_SHIELD_MOVE_DP = 32f
        private const val EDGE_SHIELD_LINGER_MS = 1000L
        private const val PEN_BACK_WINDOW_MS = 2000L
        private const val HOLD_SLOP_DP = 4f

        /** Renders the top of a note for the notes list. */
        fun renderThumbnail(strokes: List<StrokeEntry>, paper: Paper, dark: Boolean, widthPx: Int): android.graphics.Bitmap {
            val heightPx = (widthPx * 1.25f).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (dark) PAPER_DARK else PAPER_LIGHT)
            val s = widthPx / Viewport.DOC_WIDTH
            if (paper == Paper.RULED || paper == Paper.GRID) {
                val p = Paint().apply { color = if (dark) 0x33FFFFFF else 0x2A1E3A5F; strokeWidth = 1f }
                var y = LINE_SPACING
                while (y * s < heightPx) { c.drawLine(0f, y * s, widthPx.toFloat(), y * s, p); y += LINE_SPACING }
            }
            val m = Matrix().apply { setScale(s, s) }
            val r = CanvasStrokeRenderer.create()
            c.concat(m)
            val docH = heightPx / s
            for (e in strokes) if (e.intersects(0f, 0f, Viewport.DOC_WIDTH, docH)) r.draw(c, e.stroke, m)
            return bmp
        }
    }
}
