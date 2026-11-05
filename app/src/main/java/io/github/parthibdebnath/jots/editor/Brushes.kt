@file:OptIn(ExperimentalInkCustomBrushApi::class)
@file:Suppress("RestrictedApi")

package io.github.parthibdebnath.jots.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import kotlinx.serialization.Serializable

/** The inking tools that leave strokes behind. */
@Serializable
enum class InkKind { PEN, HIGHLIGHTER }

@Serializable
enum class PenType(val label: String) {
    FOUNTAIN("Fountain"),
    BALLPOINT("Ballpoint"),
    MONOLINE("Fineliner"),
}

@Serializable
enum class PressureCurve(val label: String) {
    SOFT("Soft"),
    NORMAL("Normal"),
    FIRM("Firm"),
}

/**
 * Everything needed to rebuild a stroke's [Brush]. Saved with every stroke, so a note looks the
 * same after reopening even if the defaults change.
 */
@Serializable
data class BrushSpec(
    val kind: InkKind = InkKind.PEN,
    val penType: PenType = PenType.FOUNTAIN,
    val curve: PressureCurve = PressureCurve.NORMAL,
    val color: Int = 0xFF1B1B1F.toInt(),
    /** In document units (the page is [Viewport.DOC_WIDTH] wide). */
    val size: Float = 4f,
) {
    fun withSize(s: Float) = copy(size = s.coerceIn(Brushes.MIN_SIZE, Brushes.MAX_SIZE))
}

object Brushes {
    const val MIN_SIZE = 0.6f
    const val MAX_SIZE = 80f

    /** Ink's tolerance, in document units. Small enough to stay crisp at the maximum zoom. */
    const val EPSILON = 0.05f

    private val families = HashMap<Long, BrushFamily>()

    fun brush(spec: BrushSpec, sizeOverride: Float = spec.size): Brush =
        Brush.createWithColorIntArgb(family(spec), spec.color, sizeOverride.coerceAtLeast(0.1f), EPSILON)

    @Synchronized
    fun family(spec: BrushSpec): BrushFamily {
        val key = spec.kind.ordinal * 100L + spec.penType.ordinal * 10L + spec.curve.ordinal
        return families.getOrPut(key) { build(spec) }
    }

    private fun build(spec: BrushSpec): BrushFamily = when (spec.kind) {
        InkKind.HIGHLIGHTER -> StockBrushes.highlighter(SelfOverlap.DISCARD)
        InkKind.PEN -> when (spec.penType) {
            PenType.BALLPOINT -> StockBrushes.pressurePen()
            PenType.MONOLINE -> StockBrushes.marker()
            PenType.FOUNTAIN -> fountain(spec.curve)
        }
    }

    /**
     * A pen whose width follows pressure across the whole range, unlike the stock pressure pen,
     * which only swells at the very top. This is what a drawing-tablet user expects.
     */
    private fun fountain(curve: PressureCurve): BrushFamily {
        val response = when (curve) {
            PressureCurve.SOFT -> EasingFunction.CubicBezier(0.1f, 0.55f, 0.45f, 1f)
            PressureCurve.NORMAL -> EasingFunction.Predefined.LINEAR
            PressureCurve.FIRM -> EasingFunction.CubicBezier(0.55f, 0f, 0.9f, 0.45f)
        }
        val tip = BrushTip(
            behaviors = listOf(
                StockBrushes.predictionFadeOutBehavior,
                BrushBehavior(
                    source = BrushBehavior.Source.NORMALIZED_PRESSURE,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 0f,
                    sourceValueRangeEnd = 1f,
                    targetModifierRangeStart = 0.25f,
                    targetModifierRangeEnd = 1.3f,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseCurve = response,
                    responseTimeMillis = 12L,
                    enabledToolTypes = setOf(InputToolType.STYLUS),
                ),
                // A slight taper as the pen lifts, like ink running out of the nib.
                BrushBehavior(
                    source = BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 2f,
                    sourceValueRangeEnd = 0f,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = 0.8f,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                ),
            ),
        )
        return BrushFamily(
            tip = tip,
            paint = BrushPaint(),
            clientBrushFamilyId = "jots.fountain.${curve.name.lowercase()}",
            inputModel = BrushFamily.SlidingWindowModel(),
        )
    }
}
