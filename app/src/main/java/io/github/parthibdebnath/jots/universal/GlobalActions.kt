package io.github.parthibdebnath.jots.universal

import android.view.KeyEvent
import kotlinx.serialization.Serializable

/**
 * What a tablet control does while you're in another app. These are things any app understands:
 * system navigation, standard keyboard shortcuts, scrolling and media keys.
 */
@Serializable
enum class GlobalAction(
    val label: String,
    val group: Group,
    val keyCode: Int = 0,
    val meta: Int = 0,
    val isHold: Boolean = false,
    val penOnly: Boolean = false,
) {
    NONE("Do nothing", Group.General),

    // Pen buttons only.
    PEN_BUTTON("Stylus button (as normal)", Group.Pen, penOnly = true),
    ERASER_END("Eraser while held", Group.Pen, penOnly = true),

    BACK("Back", Group.System, KeyEvent.KEYCODE_BACK),
    HOME("Home", Group.System, KeyEvent.KEYCODE_HOME),
    RECENTS("Recent apps", Group.System, KeyEvent.KEYCODE_APP_SWITCH),
    OPEN_JOTS("Open Jots", Group.System),
    SCREENSHOT("Screenshot", Group.System, KeyEvent.KEYCODE_SYSRQ),

    UNDO("Undo (Ctrl+Z)", Group.Shortcuts, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
    REDO("Redo (Ctrl+Y)", Group.Shortcuts, KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON),
    REDO_SHIFT("Redo (Ctrl+Shift+Z)", Group.Shortcuts, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
    COPY("Copy (Ctrl+C)", Group.Shortcuts, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
    CUT("Cut (Ctrl+X)", Group.Shortcuts, KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON),
    PASTE("Paste (Ctrl+V)", Group.Shortcuts, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
    SELECT_ALL("Select all (Ctrl+A)", Group.Shortcuts, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
    ZOOM_IN("Zoom in (Ctrl+=)", Group.Shortcuts, KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON),
    ZOOM_OUT("Zoom out (Ctrl+-)", Group.Shortcuts, KeyEvent.KEYCODE_MINUS, KeyEvent.META_CTRL_ON),
    BRUSH_SMALLER("Smaller brush ([)", Group.Shortcuts, KeyEvent.KEYCODE_LEFT_BRACKET),
    BRUSH_BIGGER("Bigger brush (])", Group.Shortcuts, KeyEvent.KEYCODE_RIGHT_BRACKET),
    ESCAPE("Escape", Group.Shortcuts, KeyEvent.KEYCODE_ESCAPE),
    ENTER("Enter", Group.Shortcuts, KeyEvent.KEYCODE_ENTER),

    SCROLL_UP("Scroll up", Group.Scroll),
    SCROLL_DOWN("Scroll down", Group.Scroll),
    PAGE_UP("Page up", Group.Scroll, KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("Page down", Group.Scroll, KeyEvent.KEYCODE_PAGE_DOWN),

    VOLUME_UP("Volume up", Group.Media, KeyEvent.KEYCODE_VOLUME_UP),
    VOLUME_DOWN("Volume down", Group.Media, KeyEvent.KEYCODE_VOLUME_DOWN),
    PLAY_PAUSE("Play / pause", Group.Media, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),

    // Held modifiers, for apps that use them (Space to pan, Ctrl/Shift/Alt with the pen).
    HOLD_CTRL("Hold: Ctrl", Group.Hold, KeyEvent.KEYCODE_CTRL_LEFT, isHold = true),
    HOLD_SHIFT("Hold: Shift", Group.Hold, KeyEvent.KEYCODE_SHIFT_LEFT, isHold = true),
    HOLD_ALT("Hold: Alt", Group.Hold, KeyEvent.KEYCODE_ALT_LEFT, isHold = true),
    HOLD_SPACE("Hold: Space", Group.Hold, KeyEvent.KEYCODE_SPACE, isHold = true),

    CYCLE_DIAL("Change what the dial does", Group.General),
    ;

    enum class Group(val label: String) {
        General("General"), Pen("Pen"), System("System"), Shortcuts("Keyboard shortcuts"),
        Scroll("Scrolling"), Media("Media"), Hold("While held"),
    }
}

@Serializable
enum class GlobalDialMode(val label: String) {
    SCROLL("Scroll"),
    SCROLL_SIDEWAYS("Scroll sideways"),
    VOLUME("Volume"),
    ZOOM("Zoom (Ctrl + / −)"),
    BRUSH("Brush size ([ / ])"),
    UNDO_REDO("Undo / redo"),
}

/** How the tablet behaves outside Jots. */
@Serializable
data class UniversalSettings(
    val enabled: Boolean = false,
    val pen: Map<Int, GlobalAction> = DEFAULT_PEN,
    val pad: Map<Int, GlobalAction> = DEFAULT_PAD,
    val dialModes: List<GlobalDialMode> = listOf(GlobalDialMode.SCROLL, GlobalDialMode.VOLUME, GlobalDialMode.ZOOM),
    val dialMode: GlobalDialMode = GlobalDialMode.SCROLL,
    val dialInvert: Boolean = false,
    /** Mouse-wheel notches sent per dial click when scrolling. */
    val scrollSpeed: Float = 1f,
    val showCursor: Boolean = true,
) {
    fun penAction(i: Int) = pen[i] ?: GlobalAction.NONE
    fun padAction(i: Int) = pad[i] ?: GlobalAction.NONE

    companion object {
        val DEFAULT_PEN = mapOf(0 to GlobalAction.PEN_BUTTON, 1 to GlobalAction.ERASER_END)
        val DEFAULT_PAD = mapOf(
            0 to GlobalAction.UNDO,
            1 to GlobalAction.REDO,
            2 to GlobalAction.BACK,
            3 to GlobalAction.HOME,
            4 to GlobalAction.RECENTS,
            5 to GlobalAction.PAGE_UP,
            6 to GlobalAction.PAGE_DOWN,
            7 to GlobalAction.OPEN_JOTS,
            8 to GlobalAction.CYCLE_DIAL,
        )
    }
}
