package io.github.parthibdebnath.jots.tablet

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Replays pen frames from [TabletDriver] as ordinary stylus [MotionEvent]s into our own window.
 *
 * Because the driver detaches the tablet from Android, the system no longer shows a cursor or
 * delivers pen events. Synthesizing them means every part of the app (the canvas, the toolbar,
 * the settings screens) reacts to the tablet pen exactly as it would to a built-in stylus. We also
 * draw our own cursor, since Android's is gone.
 */
class PenInjector(private val activity: Activity) {

    /** True while we're dispatching an event we made, so the activity can tell it apart. */
    var dispatching = false
        private set

    /** The pen is in range or touching; used to ignore palms and fingers meanwhile. */
    var penActive = false
        private set

    var lastX = 0f
        private set
    var lastY = 0f
        private set

    private var downTime = 0L
    private var down = false
    private val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
    private val coords = arrayOf(MotionEvent.PointerCoords())
    private val mapped = FloatArray(2)
    private val scratch = FloatArray(4)

    private val cursor = CursorView(activity)
    private var cursorAttached = false
    var showCursor = true

    fun attachCursor() {
        if (cursorAttached) return
        activity.addContentView(cursor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cursorAttached = true
    }

    fun onPen(frame: PenFrame, params: TabletParams, mapping: TabletMapping) {
        val root = activity.window?.decorView ?: return
        if (root.width == 0 || root.height == 0) return
        TabletMapper.map(frame.x, frame.y, params, root.width.toFloat(), root.height.toFloat(), mapping, mapped, scratch)
        lastX = mapped[0]
        lastY = mapped[1]

        val buttons = (if (frame.barrel1) MotionEvent.BUTTON_STYLUS_PRIMARY else 0) or
            (if (frame.barrel2) MotionEvent.BUTTON_STYLUS_SECONDARY else 0)

        when (frame.phase) {
            PenPhase.HOVER_ENTER -> { penActive = true; hover(MotionEvent.ACTION_HOVER_ENTER, frame, buttons) }
            PenPhase.HOVER_MOVE -> hover(MotionEvent.ACTION_HOVER_MOVE, frame, buttons)
            PenPhase.HOVER_EXIT -> {
                hover(MotionEvent.ACTION_HOVER_EXIT, frame, buttons)
                penActive = false
            }
            PenPhase.DOWN -> {
                penActive = true
                down = true
                downTime = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, frame, buttons)
            }
            PenPhase.MOVE -> if (down) touch(MotionEvent.ACTION_MOVE, frame, buttons) else {
                // Joined mid-stroke (Jots just came to the front with the pen down): start one.
                penActive = true
                down = true
                downTime = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, frame, buttons)
            }
            PenPhase.UP -> if (down) {
                touch(MotionEvent.ACTION_UP, frame, buttons)
                down = false
                penActive = false
            }
        }
        // Shown while hovering only: on the page the ink itself shows where the tip is.
        val hovering = frame.phase == PenPhase.HOVER_ENTER || frame.phase == PenPhase.HOVER_MOVE
        cursor.update(visible = showCursor && hovering, x = lastX, y = lastY, pressed = false)
    }

    /** Ends an in-flight synthetic gesture, e.g. when the driver is released mid-stroke. */
    fun cancel() {
        if (down) {
            val ev = obtain(MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            dispatch(ev, touch = true)
            down = false
        }
        penActive = false
        cursor.update(false, 0f, 0f, false)
    }

    private fun hover(action: Int, f: PenFrame, buttons: Int) {
        val ev = obtain(action, 0f, tiltOf(f), buttons, hoverTime = true)
        dispatch(ev, touch = false)
    }

    private fun touch(action: Int, f: PenFrame, buttons: Int) {
        val pressure = if (action == MotionEvent.ACTION_UP) f.pressure.coerceAtLeast(0f) else f.pressure.coerceIn(0.001f, 1f)
        val ev = obtain(action, pressure, tiltOf(f), buttons)
        dispatch(ev, touch = true)
    }

    private fun obtain(action: Int, pressure: Float, tilt: Float, buttons: Int, hoverTime: Boolean = false): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val c = coords[0]
        c.clear()
        c.x = lastX
        c.y = lastY
        c.pressure = pressure
        c.size = 0f
        c.setAxisValue(MotionEvent.AXIS_TILT, tilt)
        return MotionEvent.obtain(
            if (hoverTime) now else downTime, now, action, 1, props, coords,
            0, buttons, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_STYLUS, 0,
        )
    }

    private fun dispatch(ev: MotionEvent, touch: Boolean) {
        dispatching = true
        try {
            if (touch) activity.dispatchTouchEvent(ev) else activity.dispatchGenericMotionEvent(ev)
        } finally {
            dispatching = false
            ev.recycle()
        }
    }

    private fun tiltOf(f: PenFrame): Float {
        if (f.tiltX == 0 && f.tiltY == 0) return 0f
        val tx = tan(Math.toRadians(f.tiltX.toDouble()))
        val ty = tan(Math.toRadians(f.tiltY.toDouble()))
        return atan(sqrt(tx * tx + ty * ty)).toFloat()
    }

    /** A small pointer, since Android hides its own once we own the tablet. */
    private class CursorView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFFFFFFFF.toInt() }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0xFF202020.toInt() }
        private var x = 0f
        private var y = 0f
        private var show = false
        private var pressed = false

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun update(visible: Boolean, x: Float, y: Float, pressed: Boolean) {
            if (visible == show && x == this.x && y == this.y && pressed == this.pressed) return
            show = visible; this.x = x; this.y = y; this.pressed = pressed
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent?) = false
        override fun dispatchGenericMotionEvent(event: MotionEvent?) = false

        override fun onDraw(canvas: Canvas) {
            if (!show) return
            val r = (if (pressed) 2.5f else 4f) * d
            canvas.drawCircle(x, y, r + 1f * d, outer)
            canvas.drawCircle(x, y, r, inner)
        }
    }

    companion object {
        /** Not a real input device; marks events as ours in logs. */
        const val DEVICE_ID = 0x4A4F
    }
}
