package com.comedyplatform.mictracker.ui.stats

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Pure math for the hand-drawn charts: choosing "nice" axis ticks and
 * formatting tick labels. No Compose imports, so it's easy to test.
 */
object ChartMath {

    /**
     * A "nice" step (1, 2 or 5 × a power of ten) that splits [range] into
     * about [targetSteps] pieces: niceStep(7.0, 4) = 2.0, niceStep(0.8, 4) = 0.2.
     */
    fun niceStep(range: Double, targetSteps: Int): Double {
        if (range <= 0.0 || targetSteps <= 0) return 1.0
        val rough = range / targetSteps
        val magnitude = 10.0.pow(floor(log10(rough)))
        val normalized = rough / magnitude
        val nice = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /**
     * Ticks from 0 up to (at least) [maxValue] for count axes:
     * niceTicks(7.0) = [0, 2, 4, 6, 8]; niceTicks(1.0) = [0, 1]; niceTicks(0.0) = [0, 1].
     *
     * @param integerSteps counts can't be fractional, so steps are rounded up to whole numbers
     */
    fun niceTicks(maxValue: Double, targetSteps: Int = 4, integerSteps: Boolean = true): List<Double> {
        val top = if (maxValue.isNaN() || maxValue <= 0.0) 1.0 else maxValue
        var step = niceStep(top, targetSteps)
        if (integerSteps) step = max(1.0, ceil(step))
        val count = ceil(top / step - 1e-9).toInt().coerceAtLeast(1)
        return (0..count).map { it * step }
    }

    /** [count] evenly spaced values from [min] to [max] inclusive (just [min] when count ≤ 1). */
    fun evenlySpaced(min: Double, max: Double, count: Int): List<Double> {
        if (count <= 1 || max <= min) return listOf(min)
        val step = (max - min) / (count - 1)
        return (0 until count).map { min + it * step }
    }

    /**
     * How many labels to skip so they don't overlap: show every Nth label when
     * each label needs [neededPx] but only [slotPx] is available per item.
     */
    fun labelStride(slotPx: Float, neededPx: Float): Int {
        if (slotPx <= 0f) return 1
        return ceil(neededPx / slotPx).toInt().coerceAtLeast(1)
    }

    /** "3" for whole numbers, "2.5" otherwise. */
    fun formatNumber(value: Double): String {
        val rounded = value.roundToLong()
        return if (kotlin.math.abs(value - rounded) < 1e-9) {
            rounded.toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }
}
