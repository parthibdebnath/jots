package io.github.parthibdebnath.jots.input

import android.view.KeyEvent
import io.github.parthibdebnath.jots.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whatever screen currently reacts to buttons (the editor, when a note is open). */
interface ActionTarget {
    fun perform(action: Action)
    fun onDial(mode: DialMode, steps: Int)
    fun onHoldsChanged()
    fun onDialModeChanged(mode: DialMode)
}

/**
 * Turns presses of pen buttons, tablet keys and keyboard keys into [Action]s, according to the
 * user's [Bindings]. Main thread only.
 */
class ActionRouter(private val settings: SettingsStore) {
    var target: ActionTarget? = null

    /**
     * When set, the next button press is reported here instead of being acted on: this is how the
     * mapping screen asks "press the button you want to change".
     */
    var learner: ((ButtonId) -> Unit)? = null

    private val holdsByButton = HashMap<ButtonId, Action>()
    val heldActions: Set<Action> get() = holdsByButton.values.toSet()

    /** The last control touched, for the live test on the tablet screen. */
    private val _lastInput = MutableStateFlow<InputEcho?>(null)
    val lastInput: StateFlow<InputEcho?> = _lastInput

    /** Pad keys currently down (bit n = key n), for the live test. */
    private val _padMask = MutableStateFlow(0)
    val padMask: StateFlow<Int> = _padMask

    private val _penButtons = MutableStateFlow(0)
    val penButtons: StateFlow<Int> = _penButtons

    private val _dialTotal = MutableStateFlow(0)
    val dialTotal: StateFlow<Int> = _dialTotal

    private var echoSeq = 0L

    /** Returns true if the press was used. */
    fun onButton(id: ButtonId, pressed: Boolean): Boolean {
        when (id) {
            is ButtonId.Pad -> _padMask.value = if (pressed) _padMask.value or (1 shl id.index) else _padMask.value and (1 shl id.index).inv()
            is ButtonId.Pen -> _penButtons.value = if (pressed) _penButtons.value or (1 shl id.index) else _penButtons.value and (1 shl id.index).inv()
            is ButtonId.Key -> Unit
        }
        if (pressed) _lastInput.value = InputEcho(id, ++echoSeq)

        val learn = learner
        if (learn != null) {
            if (pressed) learn(id)
            // Drop any hold so learning can't leave a tool stuck on.
            if (holdsByButton.remove(id) != null) target?.onHoldsChanged()
            return true
        }

        val action = settings.value.bindings.actionFor(id)
        if (!pressed) {
            if (holdsByButton.remove(id) != null) target?.onHoldsChanged()
            return action != Action.NONE
        }
        if (action == Action.NONE) return false
        if (action.isHold) {
            holdsByButton[id] = action
            target?.onHoldsChanged()
        } else {
            perform(action)
        }
        return true
    }

    fun perform(action: Action) {
        if (action == Action.CYCLE_DIAL) {
            val s = settings.value
            val modes = s.bindings.dialModes.ifEmpty { listOf(DialMode.SCROLL) }
            val next = modes[(modes.indexOf(s.dialMode) + 1).mod(modes.size)]
            settings.update { it.copy(dialMode = next) }
            target?.onDialModeChanged(next)
            return
        }
        target?.perform(action)
    }

    fun onDial(delta: Int) {
        _dialTotal.value += delta
        _lastInput.value = InputEcho(null, ++echoSeq, dial = delta)
        if (learner != null) return
        val s = settings.value
        target?.onDial(s.dialMode, if (s.bindings.dialInvert) -delta else delta)
    }

    /**
     * Pen barrel buttons as seen in a [android.view.MotionEvent]'s button state, from Android's own
     * stylus support or from our driver. Only edges are acted on.
     */
    private var lastPenMask = 0

    fun onPenButtonState(mask: Int) {
        val changed = mask xor lastPenMask
        lastPenMask = mask
        if (changed == 0) return
        for (i in 0..1) if (changed and (1 shl i) != 0) onButton(ButtonId.Pen(i), mask and (1 shl i) != 0)
    }

    /** Returns true to consume the key. */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER) return false
        val bound = settings.value.bindings.keys.containsKey(code)
        if (learner == null && !bound) return false
        if (event.repeatCount > 0) return true
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> { onButton(ButtonId.Key(code), true); true }
            KeyEvent.ACTION_UP -> { onButton(ButtonId.Key(code), false); true }
            else -> false
        }
    }

    /** Forget all holds, e.g. when the screen changes. */
    fun releaseAll() {
        if (holdsByButton.isEmpty()) return
        holdsByButton.clear()
        target?.onHoldsChanged()
    }
}

/** One input event, for echoing on screen. [seq] makes repeated presses distinct. */
data class InputEcho(val button: ButtonId?, val seq: Long, val dial: Int = 0)
