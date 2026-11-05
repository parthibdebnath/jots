package io.github.parthibdebnath.jots.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuionProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesParamsDescriptor() {
        // bLength, type, maxX (le24) 53340, maxY (le24) 33020, maxP (le16) 8191, res (le16) 5080, ...
        val raw = bytes(
            0x13, 0x03,
            0x5C, 0xD0, 0x00,
            0xFC, 0x80, 0x00,
            0xFF, 0x1F,
            0xD8, 0x13,
            0x03, 0x0D, 0x10, 0x01, 0x04, 0x3C, 0x3E,
        )
        val p = HuionProtocol.parseParams(raw)!!
        assertEquals(53340, p.maxX)
        assertEquals(33020, p.maxY)
        assertEquals(8191, p.maxPressure)
        assertEquals(5080, p.resolution)
        assertEquals(266.7f, p.widthMm, 0.1f)
        assertEquals(165.1f, p.heightMm, 0.1f)
    }

    @Test
    fun rejectsTextDescriptorAndShortOnes() {
        val name = "HUION_T222_2210".toByteArray(Charsets.UTF_16LE)
        val raw = byteArrayOf((name.size + 2).toByte(), 3) + name
        assertNull(HuionProtocol.parseParams(raw))
        assertNull(HuionProtocol.parseParams(ByteArray(10)))
    }

    @Test
    fun parsesPenReportWithHighResolutionBytes() {
        // tip + barrel2, x = 0x01_2345, y = 0x00_6789, pressure 0x0FA0, tilt +10, -20 (y flipped)
        val r = HuionProtocol.parse(bytes(0x08, 0x85, 0x45, 0x23, 0x89, 0x67, 0xA0, 0x0F, 0x01, 0x00, 10, 20), 12) as HuionReport.Pen
        assertEquals(0x012345, r.x)
        assertEquals(0x6789, r.y)
        assertEquals(0x0FA0, r.pressure)
        assertTrue(r.tip)
        assertFalse(r.barrel1)
        assertTrue(r.barrel2)
        assertEquals(10, r.tiltX)
        assertEquals(-20, r.tiltY)
    }

    @Test
    fun penHoverWithLowerButton() {
        val r = HuionProtocol.parse(bytes(0x08, 0x82, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 12) as HuionReport.Pen
        assertFalse(r.tip)
        assertTrue(r.barrel1)
        assertFalse(r.barrel2)
    }

    @Test
    fun parsesFrameKeys() {
        // Keys 1 and 9 down.
        val r = HuionProtocol.parse(bytes(0x08, 0xE0, 0x01, 0x01, 0x01, 0x01, 0, 0, 0, 0, 0, 0), 12)
        assertEquals(HuionReport.Keys(0x101), r)
        val none = HuionProtocol.parse(bytes(0x08, 0xE0, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0), 12)
        assertEquals(HuionReport.Keys(0), none)
    }

    @Test
    fun parsesDial() {
        assertEquals(HuionReport.Dial(1), HuionProtocol.parse(bytes(0x08, 0xF1, 0x01, 0x01, 0, 0x01, 0, 0, 0, 0, 0, 0), 12))
        assertEquals(HuionReport.Dial(-1), HuionProtocol.parse(bytes(0x08, 0xF1, 0x01, 0x01, 0, 0x02, 0, 0, 0, 0, 0, 0), 12))
        assertEquals(HuionReport.Dial(-1), HuionProtocol.parse(bytes(0x08, 0xF1, 0x01, 0x01, 0, 0xFF, 0, 0, 0, 0, 0, 0), 12))
    }

    @Test
    fun ignoresOtherReports() {
        assertNull(HuionProtocol.parse(bytes(0x07, 0x80, 0, 0, 0, 0, 0, 0), 8))
        assertNull(HuionProtocol.parse(bytes(0x08, 0x80, 0, 0), 4))
        assertNull(HuionProtocol.parse(bytes(0x08, 0xE5, 0, 0, 0, 0, 0, 0), 8))
    }

    @Test
    fun decodesStringDescriptor() {
        val s = "HUION_T222_221018"
        val raw = byteArrayOf((s.length * 2 + 2).toByte(), 3) + s.toByteArray(Charsets.UTF_16LE)
        assertEquals(s, HuionProtocol.decodeStringDescriptor(raw, raw.size))
    }
}
