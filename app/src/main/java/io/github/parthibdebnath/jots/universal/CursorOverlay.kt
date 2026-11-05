package io.github.parthibdebnath.jots.universal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * The pen pointer over other apps. Android stops drawing its own once Jots owns the tablet, and
 * without one you can't see where a hovering pen is.
 *
 * The window never takes touches or focus, and is drawn at 80% opacity: Android 12+ only lets
 * touches pass through another app's overlay at or below that, so it can't block anything.
 */
class CursorOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: Dot? = null

    val canShow: Boolean get() = Settings.canDrawOverlays(context)

    fun show() {
        if (view != null || !canShow) return
        val v = Dot(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = 0.8f
            title = "Jots pen pointer"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (t: Throwable) {
            Log.w("CursorOverlay", "Couldn't add the pointer", t)
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
    }

    /** [x], [y] in display coordinates; null hides the dot. */
    fun moveTo(x: Float?, y: Float?) {
        view?.set(x, y)
    }

    private class Dot(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFFFFFFFF.toInt() }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0xFF202020.toInt() }
        private var x: Float? = null
        private var y: Float? = null
        private val loc = IntArray(2)

        fun set(nx: Float?, ny: Float?) {
            if (nx == x && ny == y) return
            x = nx; y = ny
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val px = x ?: return
            val py = y ?: return
            // The window may not start at the display's corner (cutouts, bars); compensate.
            getLocationOnScreen(loc)
            val cx = px - loc[0]
            val cy = py - loc[1]
            val r = 4.5f * d
            canvas.drawCircle(cx, cy, r + 1.2f * d, outer)
            canvas.drawCircle(cx, cy, r, inner)
        }
    }
}
