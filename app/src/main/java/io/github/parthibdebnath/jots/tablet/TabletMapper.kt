package io.github.parthibdebnath.jots.tablet

import kotlinx.serialization.Serializable

/** How the tablet's surface is laid onto the app window. */
@Serializable
data class TabletMapping(
    /** How far the tablet is physically turned clockwise relative to the phone: 0, 90, 180, 270. */
    val rotation: Int = 0,
    /** Use only the part of the tablet with the screen's proportions, so circles stay round. */
    val keepAspect: Boolean = true,
    /** Where that part sits along the tablet's spare axis: 0 = left/top, 0.5 = centre, 1 = right/bottom. */
    val anchor: Float = 0.5f,
    /** Shrink the active area further (0.3..1), e.g. to cut down hand travel. */
    val areaScale: Float = 1f,
)

/**
 * Maps raw tablet coordinates to window coordinates. Allocation-free; [out] receives x, y.
 */
object TabletMapper {

    /**
     * The active region of the tablet in its own normalized frame, after rotation:
     * returns [left, top, width, height] in the user's (rotated) frame, each 0..1.
     */
    fun activeRegion(
        params: TabletParams,
        targetW: Float,
        targetH: Float,
        mapping: TabletMapping,
        out: FloatArray = FloatArray(4),
    ): FloatArray {
        val rotated = normRotation(mapping.rotation) % 180 != 0
        val tabW = if (rotated) params.maxY.toFloat() else params.maxX.toFloat()
        val tabH = if (rotated) params.maxX.toFloat() else params.maxY.toFloat()
        var aspectW = 1f
        var aspectH = 1f
        if (mapping.keepAspect && targetW > 0f && targetH > 0f) {
            val tabletAspect = tabW / tabH
            val screenAspect = targetW / targetH
            if (tabletAspect > screenAspect) aspectW = screenAspect / tabletAspect
            else aspectH = tabletAspect / screenAspect
        }
        val scale = mapping.areaScale.coerceIn(0.3f, 1f)
        val w = aspectW * scale
        val h = aspectH * scale
        val anchor = mapping.anchor.coerceIn(0f, 1f)
        // The axis trimmed for aspect ratio follows the anchor; a shrink from areaScale alone is
        // centred.
        out[0] = (1f - w) * (if (aspectW < 1f) anchor else 0.5f)
        out[1] = (1f - h) * (if (aspectH < 1f) anchor else 0.5f)
        out[2] = w
        out[3] = h
        return out
    }

    fun map(
        rawX: Int,
        rawY: Int,
        params: TabletParams,
        targetW: Float,
        targetH: Float,
        mapping: TabletMapping,
        out: FloatArray,
        scratch: FloatArray = FloatArray(4),
    ) {
        val u = (rawX.toFloat() / params.maxX).coerceIn(0f, 1f)
        val v = (rawY.toFloat() / params.maxY).coerceIn(0f, 1f)
        // Into the user's frame: the tablet turned clockwise means its left edge is now on top.
        val a: Float
        val b: Float
        when (normRotation(mapping.rotation)) {
            90 -> { a = 1f - v; b = u }
            180 -> { a = 1f - u; b = 1f - v }
            270 -> { a = v; b = 1f - u }
            else -> { a = u; b = v }
        }
        val r = activeRegion(params, targetW, targetH, mapping, scratch)
        out[0] = ((a - r[0]) / r[2]).coerceIn(0f, 1f) * targetW
        out[1] = ((b - r[1]) / r[3]).coerceIn(0f, 1f) * targetH
    }

    fun normRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360 / 90 * 90
}
