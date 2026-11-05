package io.github.parthibdebnath.jots.settings

import android.content.Context
import io.github.parthibdebnath.jots.editor.BrushSpec
import io.github.parthibdebnath.jots.editor.InkKind
import io.github.parthibdebnath.jots.editor.PenType
import io.github.parthibdebnath.jots.input.Bindings
import io.github.parthibdebnath.jots.input.DialMode
import io.github.parthibdebnath.jots.notes.Paper
import io.github.parthibdebnath.jots.tablet.TabletMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class EraserMode(val label: String) { PARTIAL("Erase area"), STROKE("Erase whole strokes") }

@Serializable
data class AppSettings(
    // Tablet
    val driverEnabled: Boolean = true,
    val mapping: TabletMapping = TabletMapping(),
    val bindings: Bindings = Bindings(),
    val showTabletCursor: Boolean = true,

    // Pen & touch
    val fingerDrawing: Boolean = false,
    val scrollbarOnLeft: Boolean = false,
    /** Fraction of the screen height moved per dial detent in scroll mode. */
    val dialScrollStep: Float = 0.08f,

    // New notes
    val defaultPaper: Paper = Paper.RULED,
    val defaultDarkPaper: Boolean = false,

    // Tool state, remembered between sessions
    val pen: BrushSpec = BrushSpec(kind = InkKind.PEN, penType = PenType.FOUNTAIN, size = 3.5f),
    val highlighter: BrushSpec = BrushSpec(kind = InkKind.HIGHLIGHTER, color = 0x66FFD54F, size = 22f),
    val eraserMode: EraserMode = EraserMode.PARTIAL,
    val eraserSize: Float = 14f,
    val palette: List<Int> = DEFAULT_PALETTE,
    val highlighterPalette: List<Int> = DEFAULT_HIGHLIGHTS,
    val dialMode: DialMode = DialMode.SCROLL,

    val focusHintShown: Boolean = false,
) {
    companion object {
        val DEFAULT_PALETTE = listOf(
            0xFF1B1B1F, 0xFF1E5BD8, 0xFFD32F2F, 0xFF2E7D32, 0xFF7B1FA2, 0xFFEF6C00, 0xFF6D4C41, 0xFFFFFFFF,
        ).map { it.toInt() }
        val DEFAULT_HIGHLIGHTS = listOf(
            0x66FFD54F, 0x6681D4FA, 0x66A5D6A7, 0x66F48FB1, 0x66FFAB91,
        )
    }
}

/** Settings as one JSON blob in SharedPreferences, exposed as a flow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
    } ?: AppSettings()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    private companion object {
        const val KEY = "app_settings_v1"
    }
}
