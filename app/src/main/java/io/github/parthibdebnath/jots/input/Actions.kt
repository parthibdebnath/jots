package io.github.parthibdebnath.jots.input

import kotlinx.serialization.Serializable

/**
 * Everything a pen button, tablet key or keyboard key can be bound to.
 *
 * "Hold" actions last only while the button is held (the classic "hold the barrel button to
 * erase"). Everything else fires once, on press.
 */
@Serializable
enum class Action(val label: String, val group: Group, val isHold: Boolean = false) {
    NONE("Do nothing", Group.General),

    HOLD_ERASER("Hold: eraser", Group.Hold, isHold = true),
    HOLD_LASSO("Hold: lasso select", Group.Hold, isHold = true),
    HOLD_PAN("Hold: drag to scroll", Group.Hold, isHold = true),
    HOLD_HIGHLIGHTER("Hold: highlighter", Group.Hold, isHold = true),

    TOOL_PEN("Pen", Group.Tools),
    TOOL_HIGHLIGHTER("Highlighter", Group.Tools),
    TOOL_ERASER("Eraser", Group.Tools),
    TOOL_LASSO("Lasso select", Group.Tools),
    TOGGLE_ERASER("Toggle eraser / last tool", Group.Tools),
    TOGGLE_AUTO_SHAPE("Auto shape on / off", Group.Tools),

    UNDO("Undo", Group.Edit),
    REDO("Redo", Group.Edit),
    DELETE_SELECTION("Delete selection", Group.Edit),

    TOGGLE_FOCUS("Hide / show all UI", Group.View),
    FIT_WIDTH("Reset zoom (fit width)", Group.View),
    ZOOM_IN("Zoom in", Group.View),
    ZOOM_OUT("Zoom out", Group.View),
    PAGE_UP("Scroll up one screen", Group.View),
    PAGE_DOWN("Scroll down one screen", Group.View),
    SCROLL_UP("Scroll up a little", Group.View),
    SCROLL_DOWN("Scroll down a little", Group.View),

    NEXT_COLOR("Next colour", Group.Brush),
    PREV_COLOR("Previous colour", Group.Brush),
    SIZE_UP("Thicker", Group.Brush),
    SIZE_DOWN("Thinner", Group.Brush),

    CYCLE_DIAL("Change what the dial does", Group.General),
    ;

    enum class Group(val label: String) {
        General("General"), Hold("While held"), Tools("Tools"), Edit("Edit"), View("View"), Brush("Brush")
    }
}

/** What turning the dial does. */
@Serializable
enum class DialMode(val label: String) {
    SCROLL("Scroll"),
    ZOOM("Zoom"),
    BRUSH_SIZE("Brush size"),
    UNDO_REDO("Undo / redo"),
    COLOR("Colour"),
}

/** Identifies one physical control. */
@Serializable
sealed interface ButtonId {
    /** Pen barrel button; 0 = the lower one (nearest the tip), 1 = the upper one. */
    @Serializable
    data class Pen(val index: Int) : ButtonId

    /** Frame key on the tablet, numbered by bit position in the vendor report, from 0. */
    @Serializable
    data class Pad(val index: Int) : ButtonId

    /** An Android key code, from a keyboard or a tablet in its generic mode. */
    @Serializable
    data class Key(val keyCode: Int) : ButtonId
}

@Serializable
data class Bindings(
    val pen: Map<Int, Action> = DEFAULT_PEN,
    val pad: Map<Int, Action> = DEFAULT_PAD,
    val keys: Map<Int, Action> = emptyMap(),
    /** The dial modes that the "change dial" action cycles through, in order. */
    val dialModes: List<DialMode> = DialMode.entries.toList().take(3),
    val dialInvert: Boolean = false,
) {
    fun actionFor(id: ButtonId): Action = when (id) {
        is ButtonId.Pen -> pen[id.index] ?: Action.NONE
        is ButtonId.Pad -> pad[id.index] ?: Action.NONE
        is ButtonId.Key -> keys[id.keyCode] ?: Action.NONE
    }

    fun with(id: ButtonId, action: Action): Bindings = when (id) {
        is ButtonId.Pen -> copy(pen = pen + (id.index to action))
        is ButtonId.Pad -> copy(pad = pad + (id.index to action))
        is ButtonId.Key -> copy(keys = if (action == Action.NONE) keys - id.keyCode else keys + (id.keyCode to action))
    }

    companion object {
        const val PAD_KEYS = 9

        val DEFAULT_PEN = mapOf(0 to Action.HOLD_ERASER, 1 to Action.HOLD_PAN)

        // The Q620M's nine keys: two columns of four, plus the dial's centre button.
        val DEFAULT_PAD = mapOf(
            0 to Action.UNDO,
            1 to Action.REDO,
            2 to Action.TOOL_PEN,
            3 to Action.TOOL_ERASER,
            4 to Action.TOOL_HIGHLIGHTER,
            5 to Action.TOOL_LASSO,
            6 to Action.TOGGLE_FOCUS,
            7 to Action.FIT_WIDTH,
            8 to Action.CYCLE_DIAL,
        )
    }
}
