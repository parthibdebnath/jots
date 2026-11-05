package io.github.parthibdebnath.jots.tablet

/**
 * The wire protocol of Huion tablets in their "full" (vendor) mode.
 *
 * Out of the box a Huion tablet only speaks a reduced protocol through its generic HID report
 * descriptor: the pen works, but frame buttons, dials and the second barrel button either don't
 * show up or come through as garbage. Reading USB string descriptor 200 switches the firmware into
 * the full mode (this is what the Windows driver does, and what Linux's hid-uclogic and
 * OpenTabletDriver replicate). In that mode every report arrives on interface 0 with report ID
 * 0x08, and byte 1 says what kind of report it is:
 *
 * ```
 *  pen   : 08 FL XL XH YL YH PL PH XX YY TX TY   FL bit0 tip, bit1 barrel 1, bit2 barrel 2
 *  keys  : 08 E0 01 01 B0 B1 B2 ...               B0..B2 bitmap of pressed frame keys
 *  strip : 08 F0 01 ?? ?? VV ...                  touch strip position
 *  dial  : 08 F1 01 ?? ?? DD ...                  DD 1 = one detent clockwise, 2 = anticlockwise
 * ```
 *
 * X and Y are 24-bit: the low 16 bits sit at bytes 2-5 and the high bytes were bolted on later at
 * bytes 8 and 9 ("fragmented hi-res"). Tilt is signed degrees, with Y flipped.
 *
 * References: linux/drivers/hid/hid-uclogic-{core,params,rdesc}.c and OpenTabletDriver's
 * GianoReportParser, which is the parser OTD uses for the Inspiroy Dial Q620M.
 */
object HuionProtocol {
    const val VENDOR_ID = 0x256C

    /** Report ID of every vendor-mode report. */
    const val REPORT_ID = 0x08

    /** String descriptor holding the pen parameters; reading it enables the full mode. */
    const val PARAMS_STRING_INDEX = 200

    /** String descriptor holding the firmware name, e.g. "HUION_T222_221018". */
    const val FIRMWARE_STRING_INDEX = 201

    /** Defaults for the Inspiroy Dial Q620M, used if the parameter descriptor can't be parsed. */
    val Q620M_DEFAULTS = TabletParams(maxX = 53340, maxY = 33020, maxPressure = 8191, resolution = 5080)

    /**
     * Parses the raw string descriptor 200 (including its 2-byte header).
     * Returns null if it's missing, too short, or is just a UTF-16 text string (some tablets answer
     * every unknown string index with their model name).
     */
    fun parseParams(raw: ByteArray, length: Int = raw.size): TabletParams? {
        val len = minOf(length, raw.size)
        if (len < 18) return null
        // Plain ASCII-as-UTF-16LE means it's a name, not parameters.
        var i = 2
        while (i + 1 < len && raw[i] in 0x20..0x7e && raw[i + 1].toInt() == 0) i += 2
        if (i + 1 >= len) return null

        val maxX = le24(raw, 2)
        val maxY = le24(raw, 5)
        val maxPressure = le16(raw, 8)
        val resolution = le16(raw, 10)
        if (maxX <= 0 || maxY <= 0 || maxPressure <= 0) return null
        return TabletParams(maxX, maxY, maxPressure, resolution)
    }

    /**
     * Decodes one input report. [data] may be a reused buffer; only the first [length] bytes are
     * looked at. Returns null for anything that isn't a vendor-mode report we understand.
     */
    fun parse(data: ByteArray, length: Int): HuionReport? {
        if (length < 8 || (data[0].toInt() and 0xff) != REPORT_ID) return null
        val kind = data[1].toInt() and 0xff

        // Frame reports have bits 5 and 6 of byte 1 set; pen reports never do.
        if (kind and 0x60 == 0x60) {
            return when (kind) {
                0xE0 -> {
                    var mask = (data[4].toInt() and 0xff) or ((data[5].toInt() and 0xff) shl 8)
                    if (length > 6) mask = mask or ((data[6].toInt() and 0xff) shl 16)
                    HuionReport.Keys(mask)
                }
                0xF1 -> {
                    val delta = when (val v = data[5].toInt() and 0xff) {
                        0 -> 0
                        1 -> 1
                        2 -> -1
                        // A few models report a signed step instead of a direction code.
                        else -> v.toByte().toInt().coerceIn(-8, 8)
                    }
                    HuionReport.Dial(delta)
                }
                0xF0 -> HuionReport.Strip(data[5].toInt() and 0xff)
                else -> null
            }
        }

        val xLow = le16(data, 2)
        val yLow = le16(data, 4)
        val pressure = le16(data, 6)
        val hiRes = length >= 10
        val x = if (hiRes) xLow or ((data[8].toInt() and 0xff) shl 16) else xLow
        val y = if (hiRes) yLow or ((data[9].toInt() and 0xff) shl 16) else yLow
        val tiltX = if (length >= 12) data[10].toInt() else 0
        val tiltY = if (length >= 12) -data[11].toInt() else 0
        return HuionReport.Pen(
            x = x,
            y = y,
            pressure = pressure,
            tip = kind and 0x01 != 0,
            barrel1 = kind and 0x02 != 0,
            barrel2 = kind and 0x04 != 0,
            tiltX = tiltX,
            tiltY = tiltY,
        )
    }

    /** Decodes a UTF-16LE USB string descriptor (with its 2-byte header) to text. */
    fun decodeStringDescriptor(raw: ByteArray, length: Int): String? {
        if (length < 2) return null
        val declared = raw[0].toInt() and 0xff
        val end = minOf(length, declared.takeIf { it >= 2 } ?: length, raw.size)
        if (end <= 2) return ""
        return String(raw, 2, (end - 2) and 1.inv(), Charsets.UTF_16LE).trimEnd('\u0000')
    }

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le24(b: ByteArray, at: Int): Int =
        le16(b, at) or ((b[at + 2].toInt() and 0xff) shl 16)
}

data class TabletParams(val maxX: Int, val maxY: Int, val maxPressure: Int, val resolution: Int) {
    /** Physical size of the active area in millimetres, if the resolution (lines/inch) is known. */
    val widthMm: Float get() = if (resolution > 0) maxX * 25.4f / resolution else 0f
    val heightMm: Float get() = if (resolution > 0) maxY * 25.4f / resolution else 0f
}

sealed interface HuionReport {
    data class Pen(
        val x: Int,
        val y: Int,
        val pressure: Int,
        val tip: Boolean,
        val barrel1: Boolean,
        val barrel2: Boolean,
        val tiltX: Int,
        val tiltY: Int,
    ) : HuionReport

    /** Bit n set = frame key n+1 held down. */
    data class Keys(val mask: Int) : HuionReport

    /** Detents turned since the last report; positive is clockwise. */
    data class Dial(val delta: Int) : HuionReport

    data class Strip(val position: Int) : HuionReport
}
