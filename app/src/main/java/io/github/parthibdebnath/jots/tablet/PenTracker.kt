package io.github.parthibdebnath.jots.tablet

/** What the pen did, as the app needs to hear it. */
enum class PenPhase { HOVER_ENTER, HOVER_MOVE, HOVER_EXIT, DOWN, MOVE, UP }

/** One pen sample after state tracking. Coordinates are raw tablet units. */
data class PenFrame(
    val phase: PenPhase,
    val x: Int,
    val y: Int,
    /** 0..1, already normalized by the tablet's maximum. */
    val pressure: Float,
    val barrel1: Boolean,
    val barrel2: Boolean,
    val tiltX: Int,
    val tiltY: Int,
)

/**
 * Turns the tablet's raw pen reports into a well-formed hover/down/move/up sequence.
 *
 * Huion tablets don't report proximity in vendor mode: reports simply stop arriving when the pen
 * leaves. Like hid-uclogic, we treat a silence of [hoverTimeoutMs] as "out of range". While the tip
 * is down we wait longer ([contactTimeoutMs]) before giving up, so a slow, steady line isn't cut.
 */
class PenTracker(
    private val maxPressure: Int,
    private val emit: (PenFrame) -> Unit,
    private val onButton: (index: Int, pressed: Boolean) -> Unit = { _, _ -> },
) {
    var hoverTimeoutMs = 120L
    var contactTimeoutMs = 600L

    var inRange = false
        private set
    var down = false
        private set

    private var lastReportAt = 0L
    private var last: HuionReport.Pen? = null

    /** When the pen should be considered gone if nothing else arrives, or -1 if it's out of range. */
    val deadline: Long
        get() = if (!inRange) -1L else lastReportAt + if (down) contactTimeoutMs else hoverTimeoutMs

    fun onReport(r: HuionReport.Pen, now: Long) {
        lastReportAt = now
        val prev = last
        last = r

        // Button edges first, so a "hold for eraser" is already in effect when the tip lands.
        val wasB1 = prev != null && inRange && prev.barrel1
        val wasB2 = prev != null && inRange && prev.barrel2
        if (r.barrel1 != wasB1) onButton(0, r.barrel1)
        if (r.barrel2 != wasB2) onButton(1, r.barrel2)

        if (!inRange) {
            inRange = true
            if (r.tip) {
                down = true
                emit(frame(PenPhase.DOWN, r))
            } else {
                emit(frame(PenPhase.HOVER_ENTER, r))
            }
            return
        }

        when {
            r.tip && !down -> {
                emit(frame(PenPhase.HOVER_EXIT, r))
                down = true
                emit(frame(PenPhase.DOWN, r))
            }
            !r.tip && down -> {
                emit(frame(PenPhase.UP, r))
                down = false
                emit(frame(PenPhase.HOVER_ENTER, r))
            }
            prev != null && samePlace(prev, r) -> Unit // The tablet repeats itself while still.
            else -> emit(frame(if (down) PenPhase.MOVE else PenPhase.HOVER_MOVE, r))
        }
    }

    /** Call when [deadline] passes with no new report. Returns true if the pen was dropped. */
    fun onTimeout(now: Long): Boolean {
        if (!inRange || now < deadline) return false
        forceOut()
        return true
    }

    /** Ends whatever the pen was doing (tablet unplugged, app backgrounded). */
    fun forceOut() {
        val r = last ?: return
        if (!inRange) return
        if (down) {
            emit(frame(PenPhase.UP, r))
            down = false
        } else {
            emit(frame(PenPhase.HOVER_EXIT, r))
        }
        if (r.barrel1) onButton(0, false)
        if (r.barrel2) onButton(1, false)
        inRange = false
    }

    private fun samePlace(a: HuionReport.Pen, b: HuionReport.Pen) =
        a.x == b.x && a.y == b.y && a.pressure == b.pressure && a.barrel1 == b.barrel1 &&
            a.barrel2 == b.barrel2 && a.tiltX == b.tiltX && a.tiltY == b.tiltY

    private fun frame(phase: PenPhase, r: HuionReport.Pen) = PenFrame(
        phase = phase,
        x = r.x,
        y = r.y,
        pressure = if (phase == PenPhase.DOWN || phase == PenPhase.MOVE) {
            (r.pressure.toFloat() / maxPressure).coerceIn(0f, 1f)
        } else 0f,
        barrel1 = r.barrel1,
        barrel2 = r.barrel2,
        tiltX = r.tiltX,
        tiltY = r.tiltY,
    )
}
