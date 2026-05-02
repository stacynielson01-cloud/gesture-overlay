package com.gestureoverlay.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.gestureoverlay.data.GestureConfig
import com.gestureoverlay.data.StrokeConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GestureRegistry maintains a precompiled in-memory cache of [GestureDescription] objects.
 *
 * Pre-building GestureDescriptions avoids per-dispatch allocation overhead during loops,
 * keeping trigger latency well below 10ms for cached entries.
 *
 * Cache invalidation occurs whenever a [GestureConfig] is updated or replaced.
 */
@Singleton
class GestureRegistry @Inject constructor() {

    private val mutex = Mutex()

    /** Cache: gestureConfigId → prebuilt GestureDescription */
    private val cache = HashMap<String, GestureDescription>(16)

    /**
     * Returns a [GestureDescription] for [config], building and caching it on first access.
     * The description incorporates current jitter/timing settings from [config].
     *
     * Thread-safe via [Mutex].
     */
    suspend fun getOrBuild(config: GestureConfig): GestureDescription = mutex.withLock {
        cache[config.id] ?: buildDescription(config).also { cache[config.id] = it }
    }

    /**
     * Force-rebuild [GestureDescription] for [config], replacing any cached entry.
     * Call this whenever a gesture's parameters change.
     */
    suspend fun invalidate(configId: String) = mutex.withLock {
        cache.remove(configId)
    }

    /** Invalidate all cached descriptions. */
    suspend fun invalidateAll() = mutex.withLock {
        cache.clear()
    }

    /**
     * Pre-warm the cache for a list of configs. Call during profile load so
     * first-trigger latency is zero.
     */
    suspend fun preWarm(configs: List<GestureConfig>) {
        configs.forEach { getOrBuild(it) }
    }

    /**
     * Build a [GestureDescription] from a [GestureConfig].
     *
     * Each [StrokeConfig] inside the config becomes one [GestureDescription.StrokeDescription].
     * Jitter and timing variation are applied here at build time. For LOOP mode, callers
     * should call [invalidate] between iterations if live randomisation is required —
     * otherwise the cached (slightly randomised) description is reused, which is faster.
     */
    internal fun buildDescription(config: GestureConfig): GestureDescription {
        val builder = GestureDescription.Builder()

        config.strokes.forEach { stroke ->
            val path: Path = if (config.jitterEnabled) {
                MotionEngine.buildEasedPath(
                    stroke = stroke,
                    jitterEnabled = true,
                    jitterRadiusPx = config.jitterRadiusPx
                )
            } else {
                MotionEngine.buildPath(stroke, jitterEnabled = false)
            }

            val startOffset = MotionEngine.applyTimingVariation(
                baseOffsetMs = stroke.startOffsetMs,
                variationEnabled = config.timingVariationEnabled,
                maxVariationMs = config.timingVariationMs
            )

            val duration = MotionEngine.scaleDuration(stroke.durationMs, config.speedMultiplier)

            builder.addStroke(
                GestureDescription.StrokeDescription(path, startOffset, duration)
            )
        }

        return builder.build()
    }

    /**
     * Build a [GestureDescription] without using the cache — used when live randomisation
     * must produce a fresh path every dispatch (LOOP mode with jitter enabled).
     */
    fun buildFresh(config: GestureConfig): GestureDescription = buildDescription(config)
}
