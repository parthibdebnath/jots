package io.github.parthibdebnath.jots

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.parthibdebnath.jots.editor.EditorView
import io.github.parthibdebnath.jots.input.ButtonId
import io.github.parthibdebnath.jots.tablet.PenFrame
import io.github.parthibdebnath.jots.tablet.PenInjector
import io.github.parthibdebnath.jots.tablet.TabletListener
import io.github.parthibdebnath.jots.tablet.TabletParams
import io.github.parthibdebnath.jots.ui.JotsRoot
import io.github.parthibdebnath.jots.universal.GlobalInput
import io.github.parthibdebnath.jots.ui.Navigator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as JotsApp
    private lateinit var injector: PenInjector
    private val nav = Navigator()

    /** The open editor, if any; hover events are forwarded to it. */
    var editorView: EditorView? = null

    /** "Hide all UI": no toolbar, no system bars, only the page and its scroll bar. */
    var focusMode by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        injector = PenInjector(this)
        setContent { JotsRoot(this, app, nav) }
        injector.attachCursor()

        lifecycleScope.launch {
            app.settings.state.collect { s -> injector.showCursor = s.showTabletCursor }
        }
        app.driver.onDeviceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.driver.onDeviceIntent(intent)
    }

    /** While Jots is on screen, the tablet drives Jots itself (see [PenInjector]). */
    private val inAppInput = object : TabletListener {
        override fun onPen(frame: PenFrame, params: TabletParams) = injector.onPen(frame, params, app.settings.value.mapping)
        override fun onPenButton(index: Int, pressed: Boolean) = Unit // Seen via the events' button state.
        override fun onPadKey(index: Int, pressed: Boolean) { app.router.onButton(ButtonId.Pad(index), pressed) }
        override fun onDial(delta: Int) = app.router.onDial(delta)
    }

    override fun onStart() {
        super.onStart()
        // Order matters: the other-apps mode first lets go of anything it was holding down.
        app.universal.onAppInFront(true)
        app.inAppInput = inAppInput
        app.driver.onForeground()
        GlobalInput.refresh()
        app.universal.sync()
    }

    override fun onStop() {
        injector.cancel()
        app.router.releaseAll()
        if (app.inAppInput === inAppInput) app.inAppInput = null
        app.universal.onAppInFront(false)
        app.driver.onBackground()
        super.onStop()
    }

    // region Focus mode

    fun toggleFocus() = setFocus(!focusMode)

    fun setFocus(on: Boolean) {
        if (focusMode == on) return
        focusMode = on
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // endregion

    // region Input plumbing

    private var dropFingerStream = false
    private val loc = IntArray(2)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val synthetic = injector.dispatching
        if (!synthetic) {
            // While the tablet pen is in use, real touches on the phone are palms or accidents.
            // They'd also break the synthetic gesture, since a view sees one stream at a time.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) dropFingerStream = injector.penActive
            if (dropFingerStream) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) dropFingerStream = false
                return true
            }
        }
        observePenButtons(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (isPenTool(ev)) {
                        observePenButtons(ev)
                        forwardHover(ev)
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun isPenTool(ev: MotionEvent): Boolean {
        val t = ev.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE
    }

    /** Barrel buttons from Android's own stylus events or ours; edges go to the router. */
    private fun observePenButtons(ev: MotionEvent) {
        if (!isPenTool(ev)) return
        val action = ev.actionMasked
        val bs = ev.buttonState
        val mouse = ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        var mask = 0
        if (bs and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 || (mouse && bs and MotionEvent.BUTTON_SECONDARY != 0)) mask = mask or 1
        if (bs and MotionEvent.BUTTON_STYLUS_SECONDARY != 0 || (mouse && bs and MotionEvent.BUTTON_TERTIARY != 0)) mask = mask or 2
        // Leaving range counts as letting go, or a hold could get stuck on.
        if (action == MotionEvent.ACTION_HOVER_EXIT) mask = 0
        app.router.onPenButtonState(mask)
    }

    private fun forwardHover(ev: MotionEvent) {
        val v = editorView ?: return
        if (!v.isAttachedToWindow) return
        v.getLocationInWindow(loc)
        val x = ev.x - loc[0]
        val y = ev.y - loc[1]
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT || x < 0 || y < 0 || x > v.width || y > v.height) v.hideHover()
        else v.onPenHover(ev, x, y)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (app.router.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // endregion
}
