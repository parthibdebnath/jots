package io.github.parthibdebnath.jots.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ShapeRecognizerTest {
    private val rnd = Random(7)

    /** A hand-drawn version of a path: wobble plus an imperfect close. */
    private fun drawn(pts: List<Pair<Float, Float>>, wobble: Float = 1.5f): Pair<FloatArray, FloatArray> {
        val x = FloatArray(pts.size) { pts[it].first + (rnd.nextFloat() - 0.5f) * 2 * wobble }
        val y = FloatArray(pts.size) { pts[it].second + (rnd.nextFloat() - 0.5f) * 2 * wobble }
        return x to y
    }

    private fun polyPath(vararg corners: Pair<Float, Float>, perEdge: Int = 20, close: Float = 0.97f): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val last = i == corners.size - 1
            val steps = if (last) (perEdge * close).toInt() else perEdge
            for (k in 0 until steps) {
                val f = k.toFloat() / perEdge
                out += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
        }
        return out
    }

    private fun ellipsePath(cx: Float, cy: Float, rx: Float, ry: Float, rot: Float = 0f, sweep: Double = 1.95 * PI) =
        (0..80).map { i ->
            val t = sweep * i / 80
            val ex = rx * cos(t).toFloat(); val ey = ry * sin(t).toFloat()
            (cx + ex * cos(rot) - ey * sin(rot)) to (cy + ex * sin(rot) + ey * cos(rot))
        }

    private fun recognize(p: Pair<FloatArray, FloatArray>, lenient: Boolean = false) = ShapeRecognizer.recognize(p.first, p.second, lenient)

    @Test
    fun straightLineSnapsToHorizontal() {
        val pts = (0..40).map { (100f + it * 10f) to (300f + it * 0.25f) }
        val s = recognize(drawn(pts)) as Shape.Line
        assertEquals(s.y0, s.y1, 0.01f)
        assertEquals(400f, s.x1 - s.x0, 6f)
    }

    @Test
    fun diagonalLineKeepsItsAngle() {
        val pts = (0..40).map { (it * 10f) to (it * 6f) }
        val s = recognize(drawn(pts)) as Shape.Line
        assertEquals(6f / 10f, (s.y1 - s.y0) / (s.x1 - s.x0), 0.03f)
    }

    @Test
    fun circleBecomesExactCircle() {
        val s = recognize(drawn(ellipsePath(300f, 300f, 100f, 95f), wobble = 3f)) as Shape.Ellipse
        assertEquals(s.rx, s.ry, 0f)
        assertEquals(98f, s.rx, 6f)
        assertEquals(300f, s.cx, 5f)
    }

    @Test
    fun ellipseStaysAnEllipseAndIsMadeUpright() {
        val s = recognize(drawn(ellipsePath(300f, 300f, 160f, 80f, rot = 0.05f), wobble = 2f)) as Shape.Ellipse
        assertTrue(s.rx / s.ry > 1.6f || s.ry / s.rx > 1.6f)
        val a = ((s.angle % (PI / 2).toFloat()) + (PI / 2).toFloat()) % (PI / 2).toFloat()
        assertTrue("angle $a", a < 0.001f || abs(a - PI / 2) < 0.001f)
    }

    @Test
    fun rectangleIsSquaredUp() {
        val pts = polyPath(100f to 100f, 400f to 104f, 398f to 300f, 102f to 296f)
        val s = recognize(drawn(pts)) as Shape.Polygon
        assertEquals(Shape.Polygon.Kind.RECTANGLE, s.kind)
        // Upright: edges horizontal/vertical.
        assertEquals(s.ys[0], s.ys[1], 0.01f)
        assertEquals(s.xs[1], s.xs[2], 0.01f)
    }

    @Test
    fun nearSquareBecomesSquare() {
        val pts = polyPath(100f to 100f, 300f to 100f, 300f to 290f, 100f to 290f)
        val s = recognize(drawn(pts)) as Shape.Polygon
        assertEquals(Shape.Polygon.Kind.SQUARE, s.kind)
        assertEquals(abs(s.xs[1] - s.xs[0]), abs(s.ys[2] - s.ys[1]), 0.01f)
    }

    @Test
    fun triangle() {
        val pts = polyPath(200f to 100f, 350f to 350f, 50f to 350f, perEdge = 25)
        val s = recognize(drawn(pts)) as Shape.Polygon
        assertEquals(Shape.Polygon.Kind.TRIANGLE, s.kind)
        // Vertices land near the drawn corners.
        val near = listOf(200f to 100f, 350f to 350f, 50f to 350f).all { (cx, cy) ->
            s.xs.indices.any { abs(s.xs[it] - cx) < 15 && abs(s.ys[it] - cy) < 15 }
        }
        assertTrue(near)
    }

    @Test
    fun pentagon() {
        val corners = (0 until 5).map { k ->
            val t = -PI / 2 + 2 * PI * k / 5
            (300f + 150f * cos(t).toFloat()) to (300f + 150f * sin(t).toFloat())
        }
        val s = recognize(drawn(polyPath(*corners.toTypedArray(), perEdge = 18))) as Shape.Polygon
        assertEquals(Shape.Polygon.Kind.PENTAGON, s.kind)
    }

    @Test
    fun handwritingIsLeftAlone() {
        // A zigzag (like "www") and an open curve (like "c") are not shapes.
        val zig = (0..40).map { (it * 8f) to (if (it % 8 < 4) (it % 4) * 20f else (4 - it % 4) * 20f) }
        assertNull(recognize(drawn(zig)))
        val arc = ellipsePath(200f, 200f, 60f, 60f, sweep = 1.2 * PI)
        assertNull(recognize(drawn(arc)))
        // Loops on loops, like cursive "ll".
        val loops = (0..200).map { i -> val t = i / 200.0 * 6 * PI; (i * 2f + 30f * cos(t).toFloat()) to (40f * sin(t).toFloat()) }
        assertNull(recognize(drawn(loops)))
    }

    @Test
    fun tinyMarksAreIgnored() {
        val tick = listOf(0f to 0f, 4f to 6f, 12f to -8f)
        assertNull(recognize(drawn(tick, wobble = 0.2f)))
    }

    @Test
    fun lenientAcceptsARougherCircle() {
        val rough = drawn(ellipsePath(300f, 300f, 100f, 100f, sweep = 1.7 * PI), wobble = 9f)
        assertTrue(recognize(rough, lenient = true) is Shape.Ellipse)
    }

    @Test
    fun outlineIsDenseAndClosed() {
        val (x, y) = ShapeRecognizer.outline(Shape.Polygon(floatArrayOf(0f, 100f, 100f, 0f), floatArrayOf(0f, 0f, 100f, 100f), Shape.Polygon.Kind.SQUARE), 5f)
        assertEquals(x.first(), x.last(), 0.001f)
        assertEquals(y.first(), y.last(), 0.001f)
        assertTrue(x.size >= 80)
        val (cx, _) = ShapeRecognizer.outline(Shape.Ellipse(0f, 0f, 50f, 50f, 0f), 5f)
        assertTrue(cx.size >= 60)
    }
}
