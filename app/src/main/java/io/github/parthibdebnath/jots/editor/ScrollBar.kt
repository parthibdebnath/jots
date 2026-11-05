package io.github.parthibdebnath.jots.editor

import kotlin.math.abs
import kotlin.math.max

/**
 * The always-there scroll bar on the edge of the page, built to be used with the pen.
 *
 * - Grab the thumb and drag: the page scrolls proportionally (fast, for long notes).
 * - Drag anywhere else on the strip: the page follows the pen 1:1, like dragging a finger.
 * - Tap above or below the thumb: jump one screen.
 *
 * All values are view pixels except where noted.
 */
class ScrollBar {
    enum class Mode { NONE, THUMB, PAN, TAP }

    var trackTop = 0f
    var trackBottom = 0f

    /** Thumb geometry, valid after [layout]. */
    var thumbTop = 0f
        private set
    var thumbBottom = 0f
        private set

    var mode = Mode.NONE
        private set

    private var downY = 0f
    private var lastY = 0f
    private var startScroll = 0f
    private var frozenMax = 0f
    private var frozenThumbLen = 0f
    private var moved = false

    /**
     * Places the thumb for scroll position [scrollY] out of [maxScroll], with [visible] of the
     * page showing (all document units).
     */
    fun layout(scrollY: Float, maxScroll: Float, visible: Float, minThumb: Float) {
        val track = max(1f, trackBottom - trackTop)
        if (mode == Mode.THUMB) {
            // Don't resize the thumb under the pen while dragging it.
            val travel = max(0f, track - frozenThumbLen)
            val f = if (frozenMax > 0f) (scrollY / frozenMax).coerceIn(0f, 1f) else 0f
            thumbTop = trackTop + travel * f
            thumbBottom = thumbTop + frozenThumbLen
            return
        }
        val total = maxScroll + visible
        val len = if (total <= 0f) track else (track * visible / total).coerceIn(minThumb.coerceAtMost(track), track)
        val travel = max(0f, track - len)
        val f = if (maxScroll > 0f) (scrollY / maxScroll).coerceIn(0f, 1f) else 0f
        thumbTop = trackTop + travel * f
        thumbBottom = thumbTop + len
    }

    /** Starts a gesture at [y]; [slop] widens the thumb's hit area. */
    fun down(y: Float, scrollY: Float, maxScroll: Float, slop: Float) {
        downY = y
        lastY = y
        startScroll = scrollY
        moved = false
        mode = if (y >= thumbTop - slop && y <= thumbBottom + slop) {
            frozenMax = maxScroll
            frozenThumbLen = thumbBottom - thumbTop
            Mode.THUMB
        } else Mode.TAP
    }

    /**
     * Continues the gesture. Returns the new scroll position (document units) for THUMB, or the
     * scroll delta in *view pixels* for PAN via [panDeltaPx]; see [Result].
     */
    fun move(y: Float, touchSlop: Float): Result {
        val r = when (mode) {
            Mode.THUMB -> {
                val track = max(1f, trackBottom - trackTop)
                val travel = max(1f, track - frozenThumbLen)
                val target = startScroll + (y - downY) * frozenMax / travel
                Result(scrollTo = target.coerceIn(0f, max(frozenMax, startScroll)))
            }
            Mode.TAP -> {
                if (abs(y - downY) > touchSlop) {
                    mode = Mode.PAN
                    Result(panDeltaPx = y - lastY)
                } else Result()
            }
            Mode.PAN -> Result(panDeltaPx = y - lastY)
            Mode.NONE -> Result()
        }
        if (abs(y - downY) > touchSlop) moved = true
        lastY = y
        return r
    }

    /** Ends the gesture. A tap returns -1 (page up) or +1 (page down), else 0. */
    fun up(y: Float): Int {
        val m = mode
        mode = Mode.NONE
        if (m != Mode.TAP || moved) return 0
        return when {
            y < thumbTop -> -1
            y > thumbBottom -> 1
            else -> 0
        }
    }

    fun cancel() {
        mode = Mode.NONE
    }

    class Result(val scrollTo: Float = Float.NaN, val panDeltaPx: Float = 0f)
}
