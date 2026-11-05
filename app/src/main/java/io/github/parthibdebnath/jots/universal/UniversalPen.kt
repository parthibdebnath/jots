package io.github.parthibdebnath.jots.universal

import io.github.parthibdebnath.jots.tablet.PenFrame
import io.github.parthibdebnath.jots.tablet.PenPhase

/** One stylus event to inject, in display coordinates. Android-free so it can be unit-tested. */
data class PenOut(
    val action: Action,
    val x: Float,
    val y: Float,
    val pressure: Float,
    /** Bit 0: stylus primary button, bit 1: stylus secondary button. */
    val buttons: Int,
    val eraser: Boolean,
) {
    enum class Action { HOVER_ENTER, HOVER_MOVE, HOVER_EXIT, DOWN, MOVE, UP, CANCEL }
}

/**
 * Turns the driver's pen frames into a stream that Android's input dispatcher accepts for any
 * app, whatever order the frames come in:
 * - hover always starts with ENTER and ends with EXIT, and exits before the tip goes down;
 * - a stroke always starts with DOWN, even if we only join it halfway (e.g. the user switched away
 *   from Jots mid-stroke), and ends with UP or CANCEL;
 * - switching to the eraser while hovering re-enters with the new tool, as a real pen's eraser
 *   end does; mid-stroke, the tool stays what it was when the tip landed.
 */
class UniversalPen(private val emit: (PenOut) -> Unit) {
    private enum class State { OUT, HOVER, DOWN }

    private var state = State.OUT
    private var eraser = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastButtons = 0

    val isDown get() = state == State.DOWN
    val inRange get() = state != State.OUT

    /**
     * [x], [y]: the frame mapped to display coordinates. [buttons]/[eraserHeld]: what the pen's
     * barrel buttons are currently set to do (see [UniversalSettings.pen]).
     */
    fun onFrame(f: PenFrame, x: Float, y: Float, buttons: Int, eraserHeld: Boolean) {
        lastX = x; lastY = y; lastButtons = buttons
        when (f.phase) {
            PenPhase.HOVER_ENTER, PenPhase.HOVER_MOVE -> {
                if (state == State.DOWN) out(PenOut.Action.UP, 0f)
                if (state == State.HOVER && eraserHeld != eraser) {
                    out(PenOut.Action.HOVER_EXIT, 0f)
                    state = State.OUT
                }
                if (state != State.HOVER) {
                    eraser = eraserHeld
                    out(PenOut.Action.HOVER_ENTER, 0f)
                    state = State.HOVER
                }
                out(PenOut.Action.HOVER_MOVE, 0f)
            }
            PenPhase.HOVER_EXIT -> {
                if (state == State.DOWN) out(PenOut.Action.UP, 0f)
                if (state == State.HOVER) out(PenOut.Action.HOVER_EXIT, 0f)
                state = State.OUT
            }
            PenPhase.DOWN, PenPhase.MOVE -> {
                val p = f.pressure.coerceIn(0.001f, 1f)
                if (state == State.HOVER) {
                    out(PenOut.Action.HOVER_EXIT, 0f)
                    state = State.OUT
                }
                if (state == State.OUT) {
                    eraser = eraserHeld
                    out(PenOut.Action.DOWN, p)
                    state = State.DOWN
                } else {
                    out(PenOut.Action.MOVE, p)
                }
            }
            PenPhase.UP -> {
                if (state == State.DOWN) out(PenOut.Action.UP, 0f)
                if (state == State.HOVER) out(PenOut.Action.HOVER_EXIT, 0f)
                // The tracker reports hover again next if the pen is still in range.
                state = State.OUT
            }
        }
    }

    /** Ends whatever is in flight without completing it (Jots took over, or the mode stopped). */
    fun cancel() {
        when (state) {
            State.DOWN -> out(PenOut.Action.CANCEL, 0f)
            State.HOVER -> out(PenOut.Action.HOVER_EXIT, 0f)
            State.OUT -> Unit
        }
        state = State.OUT
    }

    private fun out(a: PenOut.Action, pressure: Float) = emit(PenOut(a, lastX, lastY, pressure, lastButtons, eraser))
}
