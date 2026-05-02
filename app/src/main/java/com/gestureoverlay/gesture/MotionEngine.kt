package com.gestureoverlay.gesture

import android.graphics.Path
import com.gestureoverlay.data.StrokeConfig
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * MotionEngine: generates human-like gesture paths.
 *
 * Instead of perfectly linear swipes, it applies:
 *   - Cubic ease-in-out timing (embedded in path point density)
 *   - Slight arc deviation (quadratic Bézier approximation)
 *   - Coordinate jitter within a configurable radius
 *   - Timing variation via randomised offsets returned alongside each stroke
 */
object MotionEngine {

    /**
     * Build an Android [Path] for a swipe from (x1,y1) → (x2,y2).
     *
     * If [jitterEnabled], start and end points are perturbed by up to [jitterRadiusPx] pixels
     * in a random direction. A slight cubic deviation is added as a control-point offset
     * perpendicular to the swipe axis, creating a natural arc.
     *
     * @param stroke         Source stroke configuration.
     * @param jitterEnabled  Whether to randomise coordinates.
     * @param jitterRadiusPx Max pixel displacement for jitter.
     * @return               Fully configured [Path] ready for [StrokeDescription].
     */
    fun buildPath(
        stroke: StrokeConfig,
        jitterEnabled: Boolean = true,
        jitterRadiusPx: Float = 3f
    ): Path {
        val startX = stroke.startX + if (jitterEnabled) jitter(jitterRadiusPx) else 0f
        val startY = stroke.startY + if (jitterEnabled) jitter(jitterRadiusPx) else 0f
        val endX   = stroke.endX   + if (jitterEnabled) jitter(jitterRadiusPx) else 0f
        val endY   = stroke.endY   + if (jitterEnabled) jitter(jitterRadiusPx) else 0f

        val path = Path()
        path.moveTo(startX, startY)

        // Compute a perpendicular control point for a slight natural arc.
        // The arc magnitude is proportional to swipe length, capped at 30 px.
        val dx = endX - startX
        val dy = endY - startY
        val length = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val arcMagnitude = (length * 0.08f).coerceAtMost(30f)

        // Perpendicular unit vector
        val perpX = -dy / (length + Float.MIN_VALUE)
        val perpY =  dx / (length + Float.MIN_VALUE)

        // Randomly choose arc direction (left or right of travel)
        val side = if (Random.nextBoolean()) 1f else -1f

        val ctrlX = startX + dx * 0.5f + perpX * arcMagnitude * side
        val ctrlY = startY + dy * 0.5f + perpY * arcMagnitude * side

        path.quadTo(ctrlX, ctrlY, endX, endY)

        return path
    }

    /**
     * Apply a timing variation to an offset value.
     * Returns the offset adjusted by a random delta within [±maxVariationMs].
     */
    fun applyTimingVariation(
        baseOffsetMs: Long,
        variationEnabled: Boolean,
        maxVariationMs: Long
    ): Long {
        if (!variationEnabled || maxVariationMs <= 0L) return baseOffsetMs
        val delta = Random.nextLong(-maxVariationMs, maxVariationMs + 1)
        return (baseOffsetMs + delta).coerceAtLeast(0L)
    }

    /**
     * Apply speed multiplier to a duration.
     * Multiplier < 1.0 makes gestures faster; > 1.0 makes them slower.
     * Result is clamped to [10ms, 5000ms].
     */
    fun scaleDuration(durationMs: Long, multiplier: Float): Long {
        return (durationMs * multiplier).toLong().coerceIn(10L, 5000L)
    }

    /**
     * Cubic ease-in-out interpolation — available for callers that build
     * multi-point paths and need to distribute intermediate points.
     *
     * t ∈ [0,1] → value ∈ [0,1]
     */
    fun easeInOut(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - (-2f * t + 2f).let { it * it * it } / 2f
        }
    }

    /** Random float displacement ∈ [-radius, +radius]. */
    private fun jitter(radius: Float): Float =
        Random.nextFloat() * 2f * radius - radius

    /**
     * Build a multi-point path that follows cubic easing by sampling
     * [steps] intermediate positions along the stroke.
     * Produces a smoother gesture at the cost of a larger Path object.
     */
    fun buildEasedPath(
        stroke: StrokeConfig,
        steps: Int = 12,
        jitterEnabled: Boolean = true,
        jitterRadiusPx: Float = 3f
    ): Path {
        val startX = stroke.startX + if (jitterEnabled) jitter(jitterRadiusPx / 2f) else 0f
        val startY = stroke.startY + if (jitterEnabled) jitter(jitterRadiusPx / 2f) else 0f
        val endX   = stroke.endX   + if (jitterEnabled) jitter(jitterRadiusPx / 2f) else 0f
        val endY   = stroke.endY   + if (jitterEnabled) jitter(jitterRadiusPx / 2f) else 0f

        val path = Path()
        path.moveTo(startX, startY)

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val easedT = easeInOut(t)
            val x = startX + (endX - startX) * easedT +
                    if (jitterEnabled) jitter(jitterRadiusPx * 0.5f) else 0f
            val y = startY + (endY - startY) * easedT +
                    if (jitterEnabled) jitter(jitterRadiusPx * 0.5f) else 0f
            path.lineTo(x, y)
        }

        return path
    }
}
