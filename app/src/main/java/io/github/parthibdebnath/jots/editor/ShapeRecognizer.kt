package io.github.parthibdebnath.jots.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A recognized shape, in document units. */
sealed interface Shape {
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Shape

    /** Rotated ellipse; [angle] in radians. A circle has rx == ry. */
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float) : Shape

    /** Closed polygon through [xs]/[ys] (not repeating the first point). */
    data class Polygon(val xs: FloatArray, val ys: FloatArray, val kind: Kind) : Shape {
        enum class Kind { TRIANGLE, RECTANGLE, SQUARE, QUADRILATERAL, PENTAGON, HEXAGON }

        override fun equals(other: Any?) = other is Polygon && kind == other.kind && xs.contentEquals(other.xs) && ys.contentEquals(other.ys)
        override fun hashCode() = 31 * xs.contentHashCode() + ys.contentHashCode()
    }
}

/**
 * Turns a hand-drawn stroke into a clean line, circle, ellipse, triangle, rectangle or polygon,
 * the way Samsung Notes' "auto shape" does, and tidies the result: near-horizontal/vertical/45°
 * lines are straightened, near-round ellipses become circles, near-square rectangles become
 * squares, and near-upright shapes are made upright.
 */
object ShapeRecognizer {

    /**
     * Returns the shape [x]/[y] most likely is, or null if it doesn't look like one.
     * [lenient] is used when the user explicitly asked (held the pen still), so rougher drawings
     * still snap.
     */
    fun recognize(x: FloatArray, y: FloatArray, lenient: Boolean = false): Shape? {
        if (x.size < 2) return null
        val (px, py) = resample(x, y, 64) ?: return null
        val n = px.size
        val length = pathLength(px, py)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minX = min(minX, px[i]); maxX = max(maxX, px[i]); minY = min(minY, py[i]); maxY = max(maxY, py[i]) }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < MIN_SIZE) return null
        val slack = if (lenient) 1.6f else 1f

        // Line: the path barely wanders from the chord between its ends.
        val chord = hypot(px[n - 1] - px[0], py[n - 1] - py[0])
        if (chord > 0f && chord / length > 1f - 0.06f * slack) {
            val dev = maxDistanceToSegment(px, py, px[0], py[0], px[n - 1], py[n - 1])
            if (dev < chord * 0.06f * slack) return tidyLine(px[0], py[0], px[n - 1], py[n - 1])
        }

        // Everything else is a closed shape: the ends must meet.
        val gap = hypot(px[n - 1] - px[0], py[n - 1] - py[0])
        if (gap > length * 0.18f * slack) return null
        if (length < diag * 1.8f) return null // a scribble back and forth, not an outline

        val corners = findCorners(px, py)
        val polygon = if (corners.size in 3..6) fitPolygon(px, py, corners) else null
        val polyErr = polygon?.let { polygonError(px, py, it.first, it.second) / diag } ?: Float.MAX_VALUE

        val ellipse = fitEllipse(px, py)
        val ellErr = ellipse?.let { ellipseError(px, py, it) } ?: Float.MAX_VALUE

        val polyOk = polyErr < 0.045f * slack
        val ellOk = ellErr < 0.09f * slack
        return when {
            polyOk && (!ellOk || polyErr * 2f < ellErr) -> tidyPolygon(polygon!!.first, polygon.second)
            ellOk -> tidyEllipse(ellipse!!)
            else -> null
        }
    }

    /** Points along [s] for drawing it as a stroke, about [step] document units apart. */
    fun outline(s: Shape, step: Float): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float, includeEnd: Boolean) {
            val k = max(1, (hypot(x1 - x0, y1 - y0) / step).roundToInt())
            for (i in 0 until k + (if (includeEnd) 1 else 0)) {
                val f = i.toFloat() / k
                xs += x0 + (x1 - x0) * f; ys += y0 + (y1 - y0) * f
            }
        }
        when (s) {
            is Shape.Line -> seg(s.x0, s.y0, s.x1, s.y1, true)
            is Shape.Polygon -> {
                val m = s.xs.size
                for (i in 0 until m) {
                    val j = (i + 1) % m
                    seg(s.xs[i], s.ys[i], s.xs[j], s.ys[j], includeEnd = i == m - 1)
                }
            }
            is Shape.Ellipse -> {
                val perimeter = (PI * (3 * (s.rx + s.ry) - sqrt((3 * s.rx + s.ry) * (s.rx + 3 * s.ry).toDouble()))).toFloat()
                val k = max(24, (perimeter / step).roundToInt())
                val c = cos(s.angle); val sn = sin(s.angle)
                for (i in 0..k) {
                    // Start at the top, like a hand-drawn circle usually does.
                    val t = (-PI / 2 + 2 * PI * i / k).toFloat()
                    val ex = s.rx * cos(t); val ey = s.ry * sin(t)
                    xs += s.cx + ex * c - ey * sn; ys += s.cy + ex * sn + ey * c
                }
            }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    // region Fitting

    private fun tidyLine(x0: Float, y0: Float, x1: Float, y1: Float): Shape.Line {
        val len = hypot(x1 - x0, y1 - y0)
        val a = atan2(y1 - y0, x1 - x0)
        val snapped = snapAngle(a, PI.toFloat() / 4, SNAP_DEG)
        if (snapped == a) return Shape.Line(x0, y0, x1, y1)
        // Keep the midpoint where it was, so the line doesn't drift.
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        val dx = cos(snapped) * len / 2; val dy = sin(snapped) * len / 2
        return Shape.Line(mx - dx, my - dy, mx + dx, my + dy)
    }

    private fun tidyEllipse(e: Shape.Ellipse): Shape.Ellipse {
        val ratio = min(e.rx, e.ry) / max(e.rx, e.ry)
        if (ratio > 0.84f) {
            val r = (e.rx + e.ry) / 2
            return Shape.Ellipse(e.cx, e.cy, r, r, 0f)
        }
        return e.copy(angle = snapAngle(e.angle, PI.toFloat() / 2, SNAP_DEG))
    }

    private fun tidyPolygon(xs: FloatArray, ys: FloatArray): Shape.Polygon {
        val m = xs.size
        if (m == 4) {
            // Rectangle if every corner is near 90°.
            var square = true
            for (i in 0 until 4) {
                val a = interiorAngle(xs, ys, i)
                if (abs(a - 90f) > 15f) { square = false; break }
            }
            if (square) {
                val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
                val angle = snapAngle(atan2(ys[1] - ys[0], xs[1] - xs[0]), PI.toFloat() / 2, SNAP_DEG)
                val w = (hypot(xs[1] - xs[0], ys[1] - ys[0]) + hypot(xs[3] - xs[2], ys[3] - ys[2])) / 2
                var h = (hypot(xs[2] - xs[1], ys[2] - ys[1]) + hypot(xs[0] - xs[3], ys[0] - ys[3])) / 2
                var hw = w
                val isSquare = min(w, h) / max(w, h) > 0.88f
                if (isSquare) { val s = (w + h) / 2; hw = s; h = s }
                val c = cos(angle); val s = sin(angle)
                // Same winding as the drawing.
                val sign = if (signedArea(xs, ys) >= 0) 1f else -1f
                val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
                val ox = FloatArray(4); val oy = FloatArray(4)
                for ((i, k) in corners.withIndex()) {
                    val lx = k.first * hw / 2; val ly = k.second * sign * h / 2
                    ox[i] = cx + lx * c - ly * s; oy[i] = cy + lx * s + ly * c
                }
                return Shape.Polygon(ox, oy, if (isSquare) Shape.Polygon.Kind.SQUARE else Shape.Polygon.Kind.RECTANGLE)
            }
        }
        val kind = when (m) {
            3 -> Shape.Polygon.Kind.TRIANGLE
            4 -> Shape.Polygon.Kind.QUADRILATERAL
            5 -> Shape.Polygon.Kind.PENTAGON
            else -> Shape.Polygon.Kind.HEXAGON
        }
        return Shape.Polygon(xs, ys, kind)
    }

    /** Corner indices on the resampled closed path, by how sharply it turns over a short window. */
    private fun findCorners(px: FloatArray, py: FloatArray): List<Int> {
        val n = px.size - 1 // last point ~ first point
        val w = 3
        val turn = FloatArray(n)
        for (i in 0 until n) {
            val a = (i - w + n) % n
            val b = (i + w) % n
            val a1 = atan2(py[i] - py[a], px[i] - px[a])
            val a2 = atan2(py[b] - py[i], px[b] - px[i])
            turn[i] = abs(wrap(a2 - a1))
        }
        val threshold = (40.0 * PI / 180).toFloat()
        val corners = ArrayList<Int>()
        for (i in 0 until n) {
            if (turn[i] < threshold) continue
            var isMax = true
            for (d in -w..w) {
                if (d == 0) continue
                val j = (i + d + n) % n
                if (turn[j] > turn[i] || (turn[j] == turn[i] && j < i)) { isMax = false; break }
            }
            if (isMax) corners += i
        }
        return corners
    }

    /** Polygon through the corners, each vertex refined as the intersection of fitted edges. */
    private fun fitPolygon(px: FloatArray, py: FloatArray, corners: List<Int>): Pair<FloatArray, FloatArray>? {
        val m = corners.size
        val n = px.size - 1
        // Fit a line (point + direction) to each run of points between consecutive corners.
        val lines = Array(m) { k ->
            val from = corners[k]
            val to = corners[(k + 1) % m]
            val idx = ArrayList<Int>()
            var i = from
            while (i != to) { idx += i; i = (i + 1) % n }
            idx += to
            // Trim the ends, where the corner rounding lives.
            val core = if (idx.size > 6) idx.subList(2, idx.size - 2) else idx
            fitLine(core.map { px[it] }, core.map { py[it] })
        }
        val xs = FloatArray(m); val ys = FloatArray(m)
        for (k in 0 until m) {
            val l1 = lines[(k - 1 + m) % m]; val l2 = lines[k]
            val p = intersect(l1, l2)
            if (p == null) { xs[k] = px[corners[k]]; ys[k] = py[corners[k]] } else { xs[k] = p.first; ys[k] = p.second }
        }
        // A wild intersection means the fit is wrong.
        for (k in 0 until m) if (hypot(xs[k] - px[corners[k]], ys[k] - py[corners[k]]) > hypot(px.max() - px.min(), py.max() - py.min()) * 0.25f) return null
        return xs to ys
    }

    private class FitLine(val x: Float, val y: Float, val dx: Float, val dy: Float)

    private fun fitLine(xs: List<Float>, ys: List<Float>): FitLine {
        val mx = xs.average().toFloat(); val my = ys.average().toFloat()
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in xs.indices) { val dx = xs[i] - mx; val dy = ys[i] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val angle = 0.5f * atan2(2 * sxy, sxx - syy)
        return FitLine(mx, my, cos(angle), sin(angle))
    }

    private fun intersect(a: FitLine, b: FitLine): Pair<Float, Float>? {
        val den = a.dx * b.dy - a.dy * b.dx
        if (abs(den) < 1e-4f) return null
        val t = ((b.x - a.x) * b.dy - (b.y - a.y) * b.dx) / den
        return (a.x + a.dx * t) to (a.y + a.dy * t)
    }

    private fun fitEllipse(px: FloatArray, py: FloatArray): Shape.Ellipse? {
        val n = px.size - 1
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += px[i]; my += py[i] }
        mx /= n; my /= n
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until n) { val dx = px[i] - mx; val dy = py[i] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        sxx /= n; syy /= n; sxy /= n
        val angle = 0.5f * atan2(2 * sxy, sxx - syy)
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0f, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
        if (l2 <= 0f) return null
        // Points spread evenly round an ellipse have variance r²/2 along each axis.
        return Shape.Ellipse(mx, my, sqrt(2 * l1), sqrt(2 * l2), angle)
    }

    private fun ellipseError(px: FloatArray, py: FloatArray, e: Shape.Ellipse): Float {
        val c = cos(-e.angle); val s = sin(-e.angle)
        var err = 0f
        val n = px.size - 1
        for (i in 0 until n) {
            val dx = px[i] - e.cx; val dy = py[i] - e.cy
            val lx = dx * c - dy * s; val ly = dx * s + dy * c
            err += abs(sqrt((lx / e.rx) * (lx / e.rx) + (ly / e.ry) * (ly / e.ry)) - 1f)
        }
        return err / n
    }

    private fun polygonError(px: FloatArray, py: FloatArray, xs: FloatArray, ys: FloatArray): Float {
        var err = 0f
        val m = xs.size
        for (i in px.indices) {
            var best = Float.MAX_VALUE
            for (k in 0 until m) {
                val j = (k + 1) % m
                best = min(best, distanceToSegment(px[i], py[i], xs[k], ys[k], xs[j], ys[j]))
            }
            err += best
        }
        return err / px.size
    }

    // endregion

    // region Helpers

    private fun resample(x: FloatArray, y: FloatArray, count: Int): Pair<FloatArray, FloatArray>? {
        val total = pathLength(x, y)
        if (total <= 0f) return null
        val step = total / (count - 1)
        val ox = FloatArray(count); val oy = FloatArray(count)
        ox[0] = x[0]; oy[0] = y[0]
        var k = 1
        var acc = 0f
        var i = 1
        var prevX = x[0]; var prevY = y[0]
        while (i < x.size && k < count) {
            val d = hypot(x[i] - prevX, y[i] - prevY)
            if (acc + d >= step && d > 0f) {
                val f = (step - acc) / d
                val nx = prevX + (x[i] - prevX) * f
                val ny = prevY + (y[i] - prevY) * f
                ox[k] = nx; oy[k] = ny; k++
                prevX = nx; prevY = ny
                acc = 0f
            } else {
                acc += d
                prevX = x[i]; prevY = y[i]
                i++
            }
        }
        while (k < count) { ox[k] = x.last(); oy[k] = y.last(); k++ }
        return ox to oy
    }

    private fun pathLength(x: FloatArray, y: FloatArray): Float {
        var l = 0f
        for (i in 1 until x.size) l += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return l
    }

    private fun maxDistanceToSegment(px: FloatArray, py: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        var m = 0f
        for (i in px.indices) m = max(m, distanceToSegment(px[i], py[i], x0, y0, x1, y1))
        return m
    }

    private fun distanceToSegment(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (x0 + dx * t), y - (y0 + dy * t))
    }

    private fun interiorAngle(xs: FloatArray, ys: FloatArray, i: Int): Float {
        val m = xs.size
        val a = (i - 1 + m) % m; val b = (i + 1) % m
        val a1 = atan2(ys[a] - ys[i], xs[a] - xs[i])
        val a2 = atan2(ys[b] - ys[i], xs[b] - xs[i])
        return (abs(wrap(a2 - a1)) * 180 / PI).toFloat()
    }

    private fun signedArea(xs: FloatArray, ys: FloatArray): Float {
        var a = 0f
        for (i in xs.indices) { val j = (i + 1) % xs.size; a += xs[i] * ys[j] - xs[j] * ys[i] }
        return a / 2
    }

    /** Snaps [a] to the nearest multiple of [unit] if within [toleranceDeg]. */
    private fun snapAngle(a: Float, unit: Float, toleranceDeg: Float): Float {
        val k = (a / unit).roundToInt()
        val target = k * unit
        return if (abs(wrap(a - target)) * 180 / PI < toleranceDeg) wrap(target) else a
    }

    private fun wrap(a: Float): Float {
        var v = a
        while (v > PI) v -= (2 * PI).toFloat()
        while (v < -PI) v += (2 * PI).toFloat()
        return v
    }

    // endregion

    /** Smaller than this (document units, ~3 mm on a phone) is a dot or a tick, never a shape. */
    private const val MIN_SIZE = 24f
    private const val SNAP_DEG = 7f
}
