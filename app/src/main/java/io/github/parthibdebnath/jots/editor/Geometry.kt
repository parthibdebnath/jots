package io.github.parthibdebnath.jots.editor

import kotlin.math.hypot
import kotlin.math.sqrt

/** A polyline with per-point time and pressure, detached from Ink so it can be unit-tested. */
class Polyline(
    val x: FloatArray,
    val y: FloatArray,
    val t: LongArray,
    val p: FloatArray,
) {
    val size get() = x.size

    fun length(): Float {
        var l = 0f
        for (i in 1 until size) l += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return l
    }

    class Builder {
        private val xs = ArrayList<Float>()
        private val ys = ArrayList<Float>()
        private val ts = ArrayList<Long>()
        private val ps = ArrayList<Float>()
        val size get() = xs.size

        fun add(x: Float, y: Float, t: Long, p: Float) {
            // Ink rejects consecutive duplicates; they carry no information anyway.
            if (xs.isNotEmpty() && xs.last() == x && ys.last() == y) return
            xs += x; ys += y; ts += t; ps += p
        }

        fun build() = Polyline(xs.toFloatArray(), ys.toFloatArray(), ts.toLongArray(), ps.toFloatArray())
    }
}

object Geometry {

    /**
     * Cuts the part of [line] that lies inside the circle ([cx], [cy], [r]) out of it, returning
     * the pieces left outside. Cut points are placed exactly on the circle, with time and pressure
     * interpolated, so the erased gap has a clean edge. Returns null if nothing was inside.
     */
    fun eraseCircle(line: Polyline, cx: Float, cy: Float, r: Float): List<Polyline>? {
        val n = line.size
        if (n == 0) return null
        val r2 = r * r
        if (n == 1) {
            val d = sq(line.x[0] - cx) + sq(line.y[0] - cy)
            return if (d <= r2) emptyList() else null
        }

        val pieces = ArrayList<Polyline>()
        var cur: Polyline.Builder? = null
        var touched = false

        fun startWith(i: Int) {
            cur = Polyline.Builder().also { it.add(line.x[i], line.y[i], line.t[i], line.p[i]) }
        }

        fun close() {
            cur?.let { if (it.size >= 2) pieces += it.build() }
            cur = null
        }

        fun lerpAdd(b: Polyline.Builder, i: Int, f: Float) {
            val j = i + 1
            b.add(
                line.x[i] + (line.x[j] - line.x[i]) * f,
                line.y[i] + (line.y[j] - line.y[i]) * f,
                line.t[i] + ((line.t[j] - line.t[i]) * f).toLong(),
                line.p[i] + (line.p[j] - line.p[i]) * f,
            )
        }

        // Whether point 0 starts outside: decided by the first segment's interval below.
        for (i in 0 until n - 1) {
            val ax = line.x[i]; val ay = line.y[i]
            val dx = line.x[i + 1] - ax; val dy = line.y[i + 1] - ay
            val a = dx * dx + dy * dy
            val fx = ax - cx; val fy = ay - cy
            val c = fx * fx + fy * fy - r2

            // Parameter interval [t1, t2] of the segment that lies inside the circle.
            var t1 = 1f
            var t2 = 0f
            if (a == 0f) {
                if (c <= 0f) { t1 = 0f; t2 = 1f }
            } else {
                val b = 2f * (fx * dx + fy * dy)
                val disc = b * b - 4f * a * c
                if (disc > 0f) {
                    val s = sqrt(disc)
                    t1 = ((-b - s) / (2f * a)).coerceAtLeast(0f)
                    t2 = ((-b + s) / (2f * a)).coerceAtMost(1f)
                }
            }

            if (t1 > t2) {
                // Segment entirely outside.
                if (cur == null) startWith(i)
                cur!!.add(line.x[i + 1], line.y[i + 1], line.t[i + 1], line.p[i + 1])
                continue
            }
            touched = true
            if (t1 > 0f) {
                if (cur == null) startWith(i)
                lerpAdd(cur!!, i, t1)
            }
            close()
            if (t2 < 1f) {
                val b = Polyline.Builder()
                lerpAdd(b, i, t2)
                b.add(line.x[i + 1], line.y[i + 1], line.t[i + 1], line.p[i + 1])
                cur = b
            }
        }
        close()
        if (!touched) return null
        // Drop slivers left by floating-point noise at the circle's edge.
        return pieces.filter { it.length() > MIN_PIECE }
    }

    /** Even-odd point-in-polygon. [poly] is x0,y0,x1,y1,... */
    fun pointInPolygon(px: Float, py: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Fraction (0..1) of the points of [line] that lie inside [poly]. */
    fun fractionInside(line: Polyline, poly: FloatArray): Float {
        if (line.size == 0) return 0f
        var k = 0
        for (i in 0 until line.size) if (pointInPolygon(line.x[i], line.y[i], poly)) k++
        return k.toFloat() / line.size
    }

    private fun sq(v: Float) = v * v

    const val MIN_PIECE = 0.05f
}
