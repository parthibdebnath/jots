package io.github.parthibdebnath.jots.universal

import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.MainActivity
import io.github.parthibdebnath.jots.tablet.PenFrame
import io.github.parthibdebnath.jots.tablet.PenPhase
import io.github.parthibdebnath.jots.tablet.TabletListener
import io.github.parthibdebnath.jots.tablet.TabletMapper
import io.github.parthibdebnath.jots.tablet.TabletParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface UniversalStatus {
    data object Off : UniversalStatus
    data object On : UniversalStatus
    data class Failed(val reason: String) : UniversalStatus
}

/**
 * "Use the tablet in other apps". While on, a foreground service keeps the tablet after Jots leaves
 * the screen, and this turns its input into system-wide stylus, key and scroll events.
 *
 * Inside Jots nothing changes: the in-app path handles the tablet whenever Jots is in front. If
 * injection ever fails (Shizuku stopped, permission revoked), the mode turns itself off, which
 * hands the tablet back to Android so the pen keeps working.
 *
 * Main thread only.
 */
class UniversalController(private val app: JotsApp) : TabletListener {
    private val _status = MutableStateFlow<UniversalStatus>(UniversalStatus.Off)
    val status: StateFlow<UniversalStatus> = _status

    private val cursor = CursorOverlay(app)
    private val display = app.getSystemService(DisplayManager::class.java)
    private val size = Point()
    private val mapped = FloatArray(2)
    private val scratch = FloatArray(4)

    private var serviceRunning = false
    /** Set once a failure is being handled, so a burst of failing events reports it only once. */
    private var failing = false
    private var appInFront = false
    private var lastPenAt = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    private val pen = UniversalPen { out -> if (!GlobalInput.pen(out)) fail("Lost the connection to Shizuku") }

    /** Pad keys and pen buttons whose action is a held key, by control. */
    private val heldKeys = HashMap<String, Int>()

    private val settings get() = app.settings.value.universal

    init {
        // Keep the setting on: the mode resumes by itself once Shizuku is running again.
        GlobalInput.onLost = { if (serviceRunning) fail("Shizuku stopped; this resumes once it's running again", disable = false) }
    }

    /** Whether the mode should run now; call when the setting or Shizuku's state changes. */
    fun sync() {
        val want = settings.enabled && app.settings.value.driverEnabled
        val ready = GlobalInput.state.value == ShizukuState.READY
        when {
            want && !serviceRunning && ready -> start()
            want && !ready -> {
                if (serviceRunning) stop()
                _status.value = UniversalStatus.Failed(GlobalInput.state.value.label)
            }
            !want && serviceRunning -> stop()
            !want -> if (_status.value !is UniversalStatus.Failed) _status.value = UniversalStatus.Off
        }
    }

    private fun start() {
        failing = false
        if (!GlobalInput.prepare()) {
            _status.value = UniversalStatus.Failed(GlobalInput.state.value.label)
            return
        }
        try {
            ContextCompat.startForegroundService(app, Intent(app, UniversalService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't start the service", t)
            _status.value = UniversalStatus.Failed("Android didn't allow it to start; open Jots and try again")
        }
    }

    private fun stop() {
        app.stopService(Intent(app, UniversalService::class.java))
    }

    /** Turns the mode off after a problem, and says why. */
    private fun fail(reason: String, disable: Boolean = true) {
        if (failing) return
        failing = true
        Log.w(TAG, "Stopping: $reason")
        if (disable) app.settings.update { it.copy(universal = it.universal.copy(enabled = false)) }
        stop()
        _status.value = UniversalStatus.Failed(reason)
        Toast.makeText(app, "Tablet in other apps turned off: $reason", Toast.LENGTH_LONG).show()
    }

    // region Service lifecycle

    internal fun onServiceStarted() {
        if (serviceRunning) return
        if (!GlobalInput.prepare()) {
            fail(GlobalInput.state.value.label)
            return
        }
        serviceRunning = true
        failing = false
        _status.value = UniversalStatus.On
        app.driver.hold(HOLDER)
        updateCursor()
    }

    internal fun onServiceStopped() {
        if (!serviceRunning) return
        serviceRunning = false
        releaseEverything()
        cursor.hide()
        app.driver.letGo(HOLDER)
        if (_status.value is UniversalStatus.On) _status.value = UniversalStatus.Off
    }

    val isActive: Boolean get() = serviceRunning && !appInFront && !failing

    /** Jots came to the front (in-app handling takes over) or left it. */
    fun onAppInFront(front: Boolean) {
        if (appInFront == front) return
        appInFront = front
        if (front) releaseEverything()
        updateCursor()
    }

    fun onSettingsChanged() = updateCursor()

    private fun updateCursor() {
        if (serviceRunning && !appInFront && settings.showCursor) cursor.show() else cursor.hide()
    }

    private fun releaseEverything() {
        pen.cancel()
        for (code in heldKeys.values) GlobalInput.release(code)
        heldKeys.clear()
        GlobalInput.releaseAll()
        cursor.moveTo(null, null)
    }

    // endregion

    // region Tablet input (only while active)

    override fun onPen(frame: PenFrame, params: TabletParams) {
        if (!isActive) return
        @Suppress("DEPRECATION")
        display.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size) ?: return
        if (size.x <= 0 || size.y <= 0) return
        TabletMapper.map(frame.x, frame.y, params, size.x.toFloat(), size.y.toFloat(), app.settings.value.mapping, mapped, scratch)
        lastX = mapped[0]; lastY = mapped[1]; lastPenAt = SystemClock.uptimeMillis()

        val s = settings
        var buttons = 0
        var eraser = false
        if (frame.barrel1) when (s.penAction(0)) {
            GlobalAction.PEN_BUTTON -> buttons = buttons or 1
            GlobalAction.ERASER_END -> eraser = true
            else -> Unit
        }
        if (frame.barrel2) when (s.penAction(1)) {
            GlobalAction.PEN_BUTTON -> buttons = buttons or 2
            GlobalAction.ERASER_END -> eraser = true
            else -> Unit
        }
        pen.onFrame(frame, lastX, lastY, buttons, eraser)
        val hovering = frame.phase == PenPhase.HOVER_ENTER || frame.phase == PenPhase.HOVER_MOVE
        if (hovering) cursor.moveTo(lastX, lastY) else cursor.moveTo(null, null)
    }

    override fun onPenButton(index: Int, pressed: Boolean) {
        if (!isActive) return
        control("pen$index", settings.penAction(index), pressed)
    }

    override fun onPadKey(index: Int, pressed: Boolean) {
        if (!isActive) return
        control("pad$index", settings.padAction(index), pressed)
    }

    override fun onDial(delta: Int) {
        if (!isActive || delta == 0) return
        val s = settings
        val steps = if (s.dialInvert) -delta else delta
        val ok = when (s.dialMode) {
            GlobalDialMode.SCROLL -> scrollAtPen(-steps * s.scrollSpeed, 0f)
            GlobalDialMode.SCROLL_SIDEWAYS -> scrollAtPen(0f, steps * s.scrollSpeed)
            GlobalDialMode.VOLUME -> repeatTap(steps, GlobalAction.VOLUME_UP, GlobalAction.VOLUME_DOWN)
            GlobalDialMode.ZOOM -> repeatTap(steps, GlobalAction.ZOOM_IN, GlobalAction.ZOOM_OUT)
            GlobalDialMode.BRUSH -> repeatTap(steps, GlobalAction.BRUSH_BIGGER, GlobalAction.BRUSH_SMALLER)
            GlobalDialMode.UNDO_REDO -> repeatTap(steps, GlobalAction.REDO, GlobalAction.UNDO)
        }
        if (!ok) fail("Lost the connection to Shizuku")
    }

    private fun repeatTap(steps: Int, up: GlobalAction, down: GlobalAction): Boolean {
        val a = if (steps > 0) up else down
        repeat(kotlin.math.abs(steps)) { if (!GlobalInput.tap(a.keyCode, a.meta)) return false }
        return true
    }

    private fun scrollAtPen(v: Float, h: Float): Boolean {
        // Scroll where the pen is, or mid-screen if it's been away a while.
        val recent = SystemClock.uptimeMillis() - lastPenAt < 3000 && !lastX.isNaN()
        @Suppress("DEPRECATION")
        display.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        val x = if (recent) lastX else size.x / 2f
        val y = if (recent) lastY else size.y / 2f
        return GlobalInput.scroll(x, y, v, h)
    }

    private fun control(id: String, action: GlobalAction, pressed: Boolean) {
        if (action.penOnly) return // Applied to the pen events themselves, in onPen.
        if (action.isHold) {
            if (pressed) {
                if (heldKeys.put(id, action.keyCode) == null && !GlobalInput.press(action.keyCode)) fail("Lost the connection to Shizuku")
            } else {
                heldKeys.remove(id)?.let { GlobalInput.release(it) }
            }
            return
        }
        if (!pressed) return
        val ok = when (action) {
            GlobalAction.NONE, GlobalAction.PEN_BUTTON, GlobalAction.ERASER_END -> true
            GlobalAction.CYCLE_DIAL -> { cycleDial(); true }
            GlobalAction.OPEN_JOTS -> { openJots(); true }
            GlobalAction.SCROLL_UP -> scrollAtPen(3 * settings.scrollSpeed, 0f)
            GlobalAction.SCROLL_DOWN -> scrollAtPen(-3 * settings.scrollSpeed, 0f)
            else -> GlobalInput.tap(action.keyCode, action.meta)
        }
        if (!ok) fail("Lost the connection to Shizuku")
    }

    private fun cycleDial() {
        val s = settings
        val modes = s.dialModes.ifEmpty { listOf(GlobalDialMode.SCROLL) }
        val next = modes[(modes.indexOf(s.dialMode) + 1).mod(modes.size)]
        app.settings.update { it.copy(universal = it.universal.copy(dialMode = next)) }
        Toast.makeText(app, "Dial: ${next.label}", Toast.LENGTH_SHORT).show()
    }

    private fun openJots() {
        // Allowed from the background because the pointer overlay permission is granted; if it
        // isn't, Android may ignore this, which is harmless.
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    // endregion

    companion object {
        private const val TAG = "Universal"
        const val HOLDER = "universal"
    }
}
