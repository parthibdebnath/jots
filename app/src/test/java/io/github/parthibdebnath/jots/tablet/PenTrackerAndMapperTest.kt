package io.github.parthibdebnath.jots.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenTrackerTest {
    private val frames = mutableListOf<PenFrame>()
    private val buttons = mutableListOf<Pair<Int, Boolean>>()
    private val t = PenTracker(8191, { frames += it }, { i, p -> buttons += i to p })

    private fun pen(x: Int, tip: Boolean = false, p: Int = 0, b1: Boolean = false, b2: Boolean = false) =
        HuionReport.Pen(x, 100, if (tip) p.coerceAtLeast(1) else 0, tip, b1, b2, 0, 0)

    private fun phases() = frames.map { it.phase }

    @Test
    fun hoverDownMoveUpSequence() {
        t.onReport(pen(1), 0)
        t.onReport(pen(2), 5)
        t.onReport(pen(3, tip = true, p = 4000), 10)
        t.onReport(pen(4, tip = true, p = 8191), 15)
        t.onReport(pen(5), 20)
        assertEquals(
            listOf(PenPhase.HOVER_ENTER, PenPhase.HOVER_MOVE, PenPhase.HOVER_EXIT, PenPhase.DOWN, PenPhase.MOVE, PenPhase.UP, PenPhase.HOVER_ENTER),
            phases(),
        )
        assertEquals(1f, frames[4].pressure, 0.0001f)
        assertEquals(4000f / 8191, frames[3].pressure, 0.0001f)
    }

    @Test
    fun firstReportWithTipDownGoesStraightToDown() {
        t.onReport(pen(1, tip = true, p = 100), 0)
        assertEquals(listOf(PenPhase.DOWN), phases())
    }

    @Test
    fun repeatedIdenticalReportsAreDropped() {
        t.onReport(pen(1), 0)
        t.onReport(pen(1), 5)
        t.onReport(pen(1), 10)
        assertEquals(listOf(PenPhase.HOVER_ENTER), phases())
    }

    @Test
    fun silenceEndsHoverButLongerForContact() {
        t.onReport(pen(1), 0)
        assertFalse(t.onTimeout(t.hoverTimeoutMs - 1))
        assertTrue(t.onTimeout(t.hoverTimeoutMs))
        assertEquals(PenPhase.HOVER_EXIT, frames.last().phase)
        assertFalse(t.inRange)

        frames.clear()
        t.onReport(pen(1, tip = true, p = 50), 1000)
        assertFalse(t.onTimeout(1000 + t.hoverTimeoutMs))
        assertTrue(t.onTimeout(1000 + t.contactTimeoutMs))
        assertEquals(listOf(PenPhase.DOWN, PenPhase.UP), phases())
    }

    @Test
    fun buttonEdgesAndReleaseOnLeave() {
        t.onReport(pen(1), 0)
        t.onReport(pen(1, b1 = true), 5)
        t.onReport(pen(2, b1 = true, b2 = true), 10)
        t.onReport(pen(3, b2 = true), 15)
        t.forceOut()
        assertEquals(listOf(0 to true, 1 to true, 0 to false, 1 to false), buttons)
        // Re-entering with the button still held reports a fresh press.
        t.onReport(pen(4, b2 = true), 100)
        assertEquals(1 to true, buttons.last())
    }
}

class TabletMapperTest {
    private val params = TabletParams(maxX = 1600, maxY = 1000, maxPressure = 8191, resolution = 5080)
    private val out = FloatArray(2)

    @Test
    fun stretchMapsCornersToCorners() {
        val m = TabletMapping(keepAspect = false)
        TabletMapper.map(0, 0, params, 1080f, 2400f, m, out)
        assertEquals(0f, out[0], 0.01f); assertEquals(0f, out[1], 0.01f)
        TabletMapper.map(1600, 1000, params, 1080f, 2400f, m, out)
        assertEquals(1080f, out[0], 0.01f); assertEquals(2400f, out[1], 0.01f)
    }

    @Test
    fun keepAspectUsesCentredStripForPortraitPhone() {
        val m = TabletMapping(keepAspect = true, anchor = 0.5f)
        val r = TabletMapper.activeRegion(params, 1000f, 2000f, m)
        // Tablet 1.6:1, screen 0.5:1 -> strip width 0.5/1.6 of the tablet.
        assertEquals(0.3125f, r[2], 1e-4f)
        assertEquals(1f, r[3], 1e-4f)
        assertEquals((1 - 0.3125f) / 2, r[0], 1e-4f)
        // The strip's centre maps to the screen's centre; outside it clamps to the edge.
        TabletMapper.map(800, 500, params, 1000f, 2000f, m, out)
        assertEquals(500f, out[0], 0.5f); assertEquals(1000f, out[1], 0.5f)
        TabletMapper.map(0, 500, params, 1000f, 2000f, m, out)
        assertEquals(0f, out[0], 0.01f)
    }

    @Test
    fun rotation90TurnsLeftEdgeIntoTop() {
        val m = TabletMapping(rotation = 90, keepAspect = false)
        // The tablet's top-left corner ends up at the top-right when turned clockwise.
        TabletMapper.map(0, 0, params, 1000f, 2000f, m, out)
        assertEquals(1000f, out[0], 0.01f); assertEquals(0f, out[1], 0.01f)
        TabletMapper.map(1600, 0, params, 1000f, 2000f, m, out)
        assertEquals(1000f, out[0], 0.01f); assertEquals(2000f, out[1], 0.01f)
    }

    @Test
    fun rotationUsesRotatedAspect() {
        // Turned 90°, the tablet is 1000 wide x 1600 tall: 0.625, wider than a 0.5 screen.
        val r = TabletMapper.activeRegion(params, 1000f, 2000f, TabletMapping(rotation = 90))
        assertEquals(0.8f, r[2], 1e-4f)
        assertEquals(1f, r[3], 1e-4f)
    }

    @Test
    fun anchorAndScale() {
        val r = TabletMapper.activeRegion(params, 1000f, 2000f, TabletMapping(anchor = 0f, areaScale = 0.5f))
        assertEquals(0f, r[0], 1e-4f)
        assertEquals(0.15625f, r[2], 1e-4f)
        assertEquals(0.25f, r[1], 1e-4f) // the scale-only shrink is centred vertically
        assertEquals(0.5f, r[3], 1e-4f)
    }

    @Test
    fun normalizesRotation() {
        assertEquals(270, TabletMapper.normRotation(-90))
        assertEquals(0, TabletMapper.normRotation(360))
        assertEquals(90, TabletMapper.normRotation(100))
    }
}
