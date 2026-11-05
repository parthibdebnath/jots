package io.github.parthibdebnath.jots.editor

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch

/** Conversions between Ink strokes and plain [Polyline]s, and stroke rebuilding. */
object InkOps {

    fun toPolyline(batch: StrokeInputBatch): Polyline {
        val n = batch.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val t = LongArray(n)
        val p = FloatArray(n)
        val input = StrokeInput()
        for (i in 0 until n) {
            batch.populate(i, input)
            x[i] = input.x
            y[i] = input.y
            t[i] = input.elapsedTimeMillis
            p[i] = input.pressure
        }
        return Polyline(x, y, t, p)
    }

    /**
     * Builds a new stroke from [line], keeping the tool type and unit length of [template].
     * Returns null if Ink rejects every point.
     */
    fun rebuild(spec: BrushSpec, template: StrokeInputBatch, line: Polyline, sizeScale: Float = 1f): Stroke? {
        val tool = template.getToolType()
        val unit = if (template.hasStrokeUnitLength()) template.getStrokeUnitLengthCm() else StrokeInput.NO_STROKE_UNIT_LENGTH
        val hasPressure = template.hasPressure()
        val batch = MutableStrokeInputBatch()
        val t0 = if (line.size > 0) line.t[0] else 0L
        var lastT = 0L
        for (i in 0 until line.size) {
            val t = (line.t[i] - t0).coerceAtLeast(lastT)
            try {
                batch.add(
                    type = tool,
                    x = line.x[i],
                    y = line.y[i],
                    elapsedTimeMillis = t,
                    strokeUnitLengthCm = unit,
                    pressure = if (hasPressure) line.p[i].coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE,
                )
                lastT = t
            } catch (_: IllegalArgumentException) {
                // Duplicate or otherwise invalid point: skip it.
            }
        }
        if (batch.isEmpty()) return null
        return Stroke(Brushes.brush(spec, spec.size * sizeScale), batch)
    }

    /** A copy of [batch] with every point mapped through x*s+tx, y*s+ty. */
    fun transformed(batch: StrokeInputBatch, s: Float, tx: Float, ty: Float): MutableStrokeInputBatch {
        val out = MutableStrokeInputBatch()
        val input = StrokeInput()
        val tool = batch.getToolType()
        for (i in 0 until batch.size) {
            batch.populate(i, input)
            try {
                out.add(
                    type = tool,
                    x = input.x * s + tx,
                    y = input.y * s + ty,
                    elapsedTimeMillis = input.elapsedTimeMillis,
                    strokeUnitLengthCm = input.strokeUnitLengthCm,
                    pressure = input.pressure,
                    tiltRadians = input.tiltRadians,
                    orientationRadians = input.orientationRadians,
                )
            } catch (_: IllegalArgumentException) {
            }
        }
        return out
    }

    val STYLUS: InputToolType get() = InputToolType.STYLUS
}
