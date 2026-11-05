package io.github.parthibdebnath.jots.universal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/** Whether Shizuku can lend us input injection. */
enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku isn't installed"),
    NOT_RUNNING("Shizuku isn't running"),
    TOO_OLD("Shizuku is too old: update it"),
    NO_PERMISSION("Jots needs Shizuku permission"),
    READY("Ready"),
}

/**
 * Injects input events into whatever app is on screen, using the system input manager reached
 * through Shizuku (so the calls carry the shell's INJECT_EVENTS permission). Main thread only.
 */
object GlobalInput {
    private const val TAG = "GlobalInput"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4277
    private const val INJECT_MODE_ASYNC = 0

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state

    /** Called when Shizuku goes away while we're relying on it. */
    var onLost: (() -> Unit)? = null

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayId: Method? = null
    private var appContext: Context? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        reset()
        refresh()
        onLost?.invoke()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        _state.value = when {
            !isInstalled(ctx) -> ShizukuState.NOT_INSTALLED
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.isPreV11() }.getOrDefault(true) -> ShizukuState.TOO_OLD
            runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.READY
        }
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }.onFailure { Log.w(TAG, "request failed", it) }
    }

    private fun isInstalled(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    private fun reset() {
        inputManager = null
        injectMethod = null
    }

    /** Sets up the input manager proxy. Returns false if Shizuku can't be used right now. */
    fun prepare(): Boolean {
        refresh()
        if (_state.value != ShizukuState.READY) return false
        if (injectMethod != null) return true
        return try {
            val raw = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE) ?: return false
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(raw))!!
            injectMethod = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            inputManager = im
            setDisplayId = runCatching { MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }.getOrNull()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't reach the input manager", t)
            reset()
            false
        }
    }

    /** Injects [ev] (and recycles motion events). Returns false if injection is broken. */
    fun inject(ev: InputEvent): Boolean {
        val m = injectMethod
        val im = inputManager
        try {
            if (m == null || im == null) return false
            if (ev is MotionEvent) setDisplayId?.let { runCatching { it.invoke(ev, 0) } }
            m.invoke(im, ev, INJECT_MODE_ASYNC)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "Injection failed", t)
            reset()
            return false
        } finally {
            if (ev is MotionEvent) ev.recycle()
        }
    }

    // region Event builders

    private val props = arrayOf(MotionEvent.PointerProperties())
    private val coords = arrayOf(MotionEvent.PointerCoords())
    private var strokeDownTime = 0L

    fun pen(o: PenOut): Boolean {
        val action = when (o.action) {
            PenOut.Action.HOVER_ENTER -> MotionEvent.ACTION_HOVER_ENTER
            PenOut.Action.HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE
            PenOut.Action.HOVER_EXIT -> MotionEvent.ACTION_HOVER_EXIT
            PenOut.Action.DOWN -> MotionEvent.ACTION_DOWN
            PenOut.Action.MOVE -> MotionEvent.ACTION_MOVE
            PenOut.Action.UP -> MotionEvent.ACTION_UP
            PenOut.Action.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        val now = SystemClock.uptimeMillis()
        if (o.action == PenOut.Action.DOWN) strokeDownTime = now
        val touching = o.action == PenOut.Action.DOWN || o.action == PenOut.Action.MOVE ||
            o.action == PenOut.Action.UP || o.action == PenOut.Action.CANCEL
        props[0].clear()
        props[0].id = 0
        props[0].toolType = if (o.eraser) MotionEvent.TOOL_TYPE_ERASER else MotionEvent.TOOL_TYPE_STYLUS
        val c = coords[0]
        c.clear()
        c.x = o.x
        c.y = o.y
        c.pressure = o.pressure
        c.size = 0f
        val buttons = (if (o.buttons and 1 != 0) MotionEvent.BUTTON_STYLUS_PRIMARY else 0) or
            (if (o.buttons and 2 != 0) MotionEvent.BUTTON_STYLUS_SECONDARY else 0)
        val ev = MotionEvent.obtain(
            if (touching) strokeDownTime else now, now, action, 1, props, coords,
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return inject(ev)
    }

    /** A mouse-wheel notch at ([x], [y]); positive [v] scrolls content up (wheel away from you). */
    fun scroll(x: Float, y: Float, v: Float, h: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        props[0].clear()
        props[0].id = 0
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE
        val c = coords[0]
        c.clear()
        c.x = x
        c.y = y
        c.setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        c.setAxisValue(MotionEvent.AXIS_HSCROLL, h)
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        return inject(ev)
    }

    private fun key(action: Int, code: Int, meta: Int, downTime: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(downTime, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
        return inject(ev)
    }

    /** Presses and releases [code], with any modifiers in [meta] held around it, like a keyboard. */
    fun tap(code: Int, meta: Int = 0): Boolean {
        val t = SystemClock.uptimeMillis()
        val mods = modifierKeys(meta)
        var ok = true
        var m = 0
        for ((k, flag) in mods) { m = m or flag; ok = ok && key(KeyEvent.ACTION_DOWN, k, m, t) }
        ok = ok && key(KeyEvent.ACTION_DOWN, code, meta, t)
        ok = ok && key(KeyEvent.ACTION_UP, code, meta, t)
        for ((k, flag) in mods.reversed()) { m = m and flag.inv(); ok = ok && key(KeyEvent.ACTION_UP, k, m, t) }
        return ok
    }

    private val heldDown = HashMap<Int, Long>()

    fun press(code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        heldDown[code] = t
        return key(KeyEvent.ACTION_DOWN, code, metaFor(code), t)
    }

    fun release(code: Int): Boolean {
        val t = heldDown.remove(code) ?: return true
        return key(KeyEvent.ACTION_UP, code, 0, t)
    }

    /** Lets go of every held key (mode stopping, Jots coming to the front). */
    fun releaseAll() {
        for (code in heldDown.keys.toList()) release(code)
    }

    private fun modifierKeys(meta: Int): List<Pair<Int, Int>> = buildList {
        if (meta and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        if (meta and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        if (meta and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
    }

    private fun metaFor(code: Int) = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        else -> 0
    }

    // endregion
}
