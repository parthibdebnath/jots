package io.github.parthibdebnath.jots.editor

import io.github.parthibdebnath.jots.input.Action
import io.github.parthibdebnath.jots.input.Bindings
import io.github.parthibdebnath.jots.input.ButtonId
import io.github.parthibdebnath.jots.settings.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ViewportTest {
    private fun vp(w: Int = 1000, h: Int = 2000, bottom: Float = 0f) = Viewport().apply { setSize(w, h, bottom) }

    @Test
    fun fitWidthByDefaultNoBorders() {
        val v = vp(1080, 2400)
        assertEquals(1.08f, v.scale, 1e-4f)
        assertEquals(Viewport.DOC_WIDTH, v.visibleWidth, 1e-3f)
        assertEquals(0f, v.offsetX, 0f)
        // Can't zoom out past fit-width.
        assertFalse(v.zoomBy(0.5f, 500f, 500f, 0f))
    }

    @Test
    fun zoomKeepsFocusPointAndClampsHorizontally() {
        val v = vp()
        val bottom = 10_000f
        val docX = v.toDocX(250f)
        val docY = v.toDocY(400f)
        assertTrue(v.zoomBy(2f, 250f, 400f, bottom))
        assertEquals(docX, v.toDocX(250f), 1e-3f)
        assertEquals(docY, v.toDocY(400f), 1e-3f)
        v.scrollBy(-10_000f, 0f, bottom)
        assertEquals(0f, v.offsetX, 0f)
        v.scrollBy(10_000f, 0f, bottom)
        assertEquals(Viewport.DOC_WIDTH - v.visibleWidth, v.offsetX, 1e-3f)
    }

    @Test
    fun scrollIsCappedBelowContentButNeverForcedUp() {
        val v = vp(1000, 2000) // visible height 2000 doc units
        val bottom = 5000f
        v.scrollBy(0f, 100_000f, bottom)
        assertEquals(bottom - 500f, v.scrollY, 1e-3f)
        // Ink at the bottom was erased: the view must stay put…
        v.clamp(100f)
        assertEquals(4500f, v.scrollY, 1e-3f)
        // …but can't go further down, while scrolling up still works.
        assertFalse(v.scrollBy(0f, 50f, 100f))
        assertTrue(v.scrollBy(0f, -50f, 100f))
        v.scrollBy(0f, -100_000f, 100f)
        assertEquals(0f, v.scrollY, 0f)
    }

    @Test
    fun conversionsRoundTrip() {
        val v = vp()
        v.zoomBy(3f, 100f, 100f, 9999f)
        v.scrollBy(50f, 700f, 9999f)
        assertEquals(321f, v.toViewX(v.toDocX(321f)), 1e-3f)
        assertEquals(654f, v.toViewY(v.toDocY(654f)), 1e-3f)
    }
}

class ScrollBarTest {
    private fun bar() = ScrollBar().apply { trackTop = 0f; trackBottom = 1000f }

    @Test
    fun thumbSizeAndPosition() {
        val b = bar()
        b.layout(scrollY = 0f, maxScroll = 3000f, visible = 1000f, minThumb = 10f)
        assertEquals(0f, b.thumbTop, 1e-3f)
        assertEquals(250f, b.thumbBottom, 1e-3f)
        b.layout(scrollY = 3000f, maxScroll = 3000f, visible = 1000f, minThumb = 10f)
        assertEquals(1000f, b.thumbBottom, 1e-3f)
        // Nothing to scroll: the thumb fills the track.
        b.layout(0f, 0f, 1000f, 10f)
        assertEquals(1000f, b.thumbBottom - b.thumbTop, 1e-3f)
    }

    @Test
    fun draggingTheThumbScrollsProportionally() {
        val b = bar()
        b.layout(0f, 3000f, 1000f, 10f)
        b.down(100f, scrollY = 0f, maxScroll = 3000f, slop = 0f)
        assertEquals(ScrollBar.Mode.THUMB, b.mode)
        // Thumb travel is 750 px for 3000 units.
        val r = b.move(475f, 8f)
        assertEquals(1500f, r.scrollTo, 1e-2f)
        assertEquals(0, b.up(475f))
    }

    @Test
    fun draggingTheTrackPansOneToOne() {
        val b = bar()
        b.layout(0f, 3000f, 1000f, 10f)
        b.down(600f, 0f, 3000f, 0f)
        assertEquals(ScrollBar.Mode.TAP, b.mode)
        assertEquals(0f, b.move(603f, 8f).panDeltaPx, 0f) // within slop: still a tap
        assertEquals(17f, b.move(620f, 8f).panDeltaPx, 1e-3f)
        assertEquals(ScrollBar.Mode.PAN, b.mode)
        assertEquals(10f, b.move(630f, 8f).panDeltaPx, 1e-3f)
        assertEquals(0, b.up(630f))
    }

    @Test
    fun tapBelowThumbPagesDown() {
        val b = bar()
        b.layout(0f, 3000f, 1000f, 10f)
        b.down(800f, 0f, 3000f, 0f)
        assertEquals(1, b.up(800f))
        b.layout(3000f, 3000f, 1000f, 10f)
        b.down(100f, 3000f, 3000f, 0f)
        assertEquals(-1, b.up(100f))
    }
}

class GeometryTest {
    private fun line(vararg pts: Float): Polyline {
        val n = pts.size / 2
        return Polyline(
            FloatArray(n) { pts[2 * it] }, FloatArray(n) { pts[2 * it + 1] },
            LongArray(n) { it * 10L }, FloatArray(n) { 0.5f },
        )
    }

    @Test
    fun erasingTheMiddleSplitsInTwoWithCleanEdges() {
        val l = line(0f, 0f, 100f, 0f)
        val pieces = Geometry.eraseCircle(l, 50f, 0f, 10f)!!
        assertEquals(2, pieces.size)
        assertEquals(40f, pieces[0].x.last(), 1e-3f)
        assertEquals(60f, pieces[1].x.first(), 1e-3f)
        assertEquals(0f, pieces[0].x.first(), 0f)
        assertEquals(100f, pieces[1].x.last(), 0f)
        // Times are interpolated at the cut.
        assertEquals(4L, pieces[0].t.last())
    }

    @Test
    fun missReturnsNullAndFullCoverEmpties() {
        val l = line(0f, 0f, 10f, 0f, 20f, 0f)
        assertNull(Geometry.eraseCircle(l, 10f, 50f, 5f))
        assertEquals(0, Geometry.eraseCircle(l, 10f, 0f, 30f)!!.size)
    }

    @Test
    fun erasingAnEndShortensTheStroke() {
        val l = line(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f)
        val pieces = Geometry.eraseCircle(l, 30f, 0f, 5f)!!
        assertEquals(1, pieces.size)
        assertEquals(25f, pieces[0].x.last(), 1e-3f)
    }

    @Test
    fun cutsAcrossASegmentThatOnlyPassesThroughTheCircle() {
        // Both points far outside; the segment passes straight through the eraser.
        val l = line(-100f, 0f, 100f, 0f)
        val pieces = Geometry.eraseCircle(l, 0f, 0f, 10f)!!
        assertEquals(2, pieces.size)
        for (p in pieces) for (i in 0 until p.size) assertTrue(hypot(p.x[i], p.y[i]) >= 10f - 1e-3f)
    }

    @Test
    fun singlePoint() {
        assertNotNull(Geometry.eraseCircle(line(1f, 1f), 0f, 0f, 5f))
        assertNull(Geometry.eraseCircle(line(10f, 10f), 0f, 0f, 5f))
    }

    @Test
    fun pointInPolygonAndFraction() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(Geometry.pointInPolygon(5f, 5f, square))
        assertFalse(Geometry.pointInPolygon(15f, 5f, square))
        val l = line(1f, 1f, 2f, 2f, 3f, 3f, 20f, 20f)
        assertEquals(0.75f, Geometry.fractionInside(l, square), 1e-6f)
    }

    @Test
    fun builderDropsDuplicates() {
        val b = Polyline.Builder()
        b.add(1f, 1f, 0, 0f)
        b.add(1f, 1f, 5, 0f)
        b.add(2f, 1f, 6, 0f)
        assertEquals(2, b.size)
    }
}

class BindingsTest {
    @Test
    fun defaultsAndOverrides() {
        val b = Bindings()
        assertEquals(Action.HOLD_ERASER, b.actionFor(ButtonId.Pen(0)))
        assertEquals(Action.UNDO, b.actionFor(ButtonId.Pad(0)))
        assertEquals(Action.NONE, b.actionFor(ButtonId.Key(42)))
        val c = b.with(ButtonId.Key(42), Action.REDO).with(ButtonId.Pad(0), Action.ZOOM_IN)
        assertEquals(Action.REDO, c.actionFor(ButtonId.Key(42)))
        assertEquals(Action.ZOOM_IN, c.actionFor(ButtonId.Pad(0)))
        assertEquals(Action.NONE, c.with(ButtonId.Key(42), Action.NONE).actionFor(ButtonId.Key(42)))
    }

    @Test
    fun settingsSurviveJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val s = AppSettings(bindings = Bindings().with(ButtonId.Key(24), Action.PAGE_UP), fingerDrawing = true)
        val back = json.decodeFromString<AppSettings>(json.encodeToString(AppSettings.serializer(), s))
        assertEquals(s, back)
        // Unknown fields from a newer version are ignored, missing ones take defaults.
        val partial = json.decodeFromString<AppSettings>("""{"fingerDrawing":true,"future":1}""")
        assertTrue(partial.fingerDrawing)
        assertEquals(Bindings(), partial.bindings)
    }
}
