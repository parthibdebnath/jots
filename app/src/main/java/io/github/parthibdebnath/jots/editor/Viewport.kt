package io.github.parthibdebnath.jots.editor

import android.graphics.Matrix
import kotlin.math.max

/**
 * The camera onto a note.
 *
 * A note is a strip [DOC_WIDTH] units wide and endlessly tall. At zoom 1 the strip exactly fills
 * the view's width, so there is never a border to the left or right: that is the "fill the
 * screen" default, and zoom never goes below it. [offsetX] and [scrollY] are the document
 * coordinates of the view's top-left corner.
 */
class Viewport {
    var viewW = 1f
        private set
    var viewH = 1f
        private set
    var zoom = 1f
        private set
    var offsetX = 0f
        private set
    var scrollY = 0f
        private set

    /** View pixels per document unit. */
    val scale: Float get() = viewW / DOC_WIDTH * zoom
    val visibleWidth: Float get() = viewW / scale
    val visibleHeight: Float get() = viewH / scale

    fun setSize(w: Int, h: Int, contentBottom: Float) {
        viewW = max(1, w).toFloat()
        viewH = max(1, h).toFloat()
        clamp(contentBottom)
    }

    fun restore(zoom: Float, offsetX: Float, scrollY: Float, contentBottom: Float) {
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        this.offsetX = offsetX
        this.scrollY = scrollY
        clamp(contentBottom)
    }

    /** How far down the view may go: until the last ink is a quarter of the way down the screen. */
    fun maxScrollY(contentBottom: Float): Float = max(0f, contentBottom - visibleHeight * 0.25f)

    fun toDocX(viewX: Float) = viewX / scale + offsetX
    fun toDocY(viewY: Float) = viewY / scale + scrollY
    fun toViewX(docX: Float) = (docX - offsetX) * scale
    fun toViewY(docY: Float) = (docY - scrollY) * scale

    /** Scrolls by [dy] document units. Returns true if anything moved. */
    fun scrollBy(dx: Float, dy: Float, contentBottom: Float): Boolean {
        val ox = offsetX
        val sy = scrollY
        offsetX += dx
        scrollY = limitScroll(sy + dy, sy, contentBottom)
        clamp(contentBottom)
        return ox != offsetX || sy != scrollY
    }

    fun scrollTo(y: Float, contentBottom: Float): Boolean {
        val sy = scrollY
        scrollY = limitScroll(y, sy, contentBottom)
        clamp(contentBottom)
        return sy != scrollY
    }

    /**
     * Moving down is capped at [maxScrollY], but never forces the view up: if ink below was just
     * erased, the page must not jump out from under the pen.
     */
    private fun limitScroll(target: Float, current: Float, contentBottom: Float): Float {
        val cap = max(maxScrollY(contentBottom), current)
        return target.coerceIn(0f, cap)
    }

    /** Zooms by [factor], keeping the document point under view pixel ([px], [py]) in place. */
    fun zoomBy(factor: Float, px: Float, py: Float, contentBottom: Float): Boolean {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return false
        val docX = toDocX(px)
        val docY = toDocY(py)
        val sy = scrollY
        zoom = newZoom
        offsetX = docX - px / scale
        scrollY = limitScroll(docY - py / scale, sy, contentBottom)
        clamp(contentBottom)
        return true
    }

    fun resetZoom(contentBottom: Float): Boolean {
        if (zoom == 1f && offsetX == 0f) return false
        // Keep the line at the top of the screen where it is.
        zoom = 1f
        offsetX = 0f
        clamp(contentBottom)
        return true
    }

    /** Keeps the view inside the page horizontally and below the top. */
    @Suppress("UNUSED_PARAMETER")
    fun clamp(contentBottom: Float) {
        zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        offsetX = offsetX.coerceIn(0f, max(0f, DOC_WIDTH - visibleWidth))
        if (scrollY < 0f) scrollY = 0f
    }

    fun docToView(out: Matrix): Matrix {
        out.setScale(scale, scale)
        out.postTranslate(-offsetX * scale, -scrollY * scale)
        return out
    }

    fun viewToDoc(out: Matrix): Matrix {
        out.setTranslate(offsetX * scale, scrollY * scale)
        out.postScale(1f / scale, 1f / scale)
        // (x + offsetX*scale) / scale = x/scale + offsetX
        return out
    }

    companion object {
        const val DOC_WIDTH = 1000f
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}
