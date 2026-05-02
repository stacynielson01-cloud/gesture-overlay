package com.gestureoverlay.gesture

import android.util.Log
import com.gestureoverlay.data.GestureConfig
import com.gestureoverlay.data.GestureProfile
import com.gestureoverlay.data.GestureResult
import com.gestureoverlay.service.GestureAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GestureController"

/**
 * GestureController is the central orchestrator between the overlay UI and the execution stack.
 *
 * Responsibilities:
 *  - Accept gesture trigger requests from FloatingButtonView
 *  - Delegate to ExecutionEngine with the current AccessibilityService reference
 *  - Manage loop lifecycle (start / stop per gesture, stop all)
 *  - Pre-warm the GestureRegistry on profile load
 *  - Provide result callbacks to callers for visual feedback
 */
@Singleton
class GestureController @Inject constructor(
    private val registry: GestureRegistry,
    private val engine: ExecutionEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Execute a gesture from a button press.
     *
     * @param config   The gesture configuration for the pressed button.
     * @param onResult Optional callback invoked on the calling thread with the dispatch result.
     */
    fun execute(
        config: GestureConfig,
        onResult: ((GestureResult) -> Unit)? = null
    ) {
        val service = GestureAccessibilityService.instance
        scope.launch {
            val result = engine.trigger(config, service)
            Log.d(TAG, "Gesture '${config.label}' result: $result")
            onResult?.invoke(result)
        }
    }

    /**
     * Stop a running loop gesture.
     */
    fun stopLoop(gestureId: String) {
        scope.launch { engine.stopLoop(gestureId) }
    }

    /**
     * Stop all running loops immediately.
     */
    fun stopAll() {
        scope.launch { engine.stopAll() }
    }

    /**
     * Returns whether a loop is currently active for the given gesture ID.
     */
    fun isLooping(gestureId: String, callback: (Boolean) -> Unit) {
        scope.launch {
            callback(engine.isLooping(gestureId))
        }
    }

    /**
     * Pre-warm GestureRegistry with all configs from a newly loaded profile.
     * Call this after loading or switching profiles to ensure zero first-tap latency.
     */
    fun preWarmProfile(profile: GestureProfile) {
        scope.launch {
            registry.invalidateAll()
            registry.preWarm(profile.gestures)
            Log.d(TAG, "Pre-warmed ${profile.gestures.size} gestures for profile '${profile.name}'")
        }
    }

    /**
     * Invalidate the cached GestureDescription for a specific config (e.g. after editing).
     */
    fun invalidateCache(gestureId: String) {
        scope.launch { registry.invalidate(gestureId) }
    }

    /**
     * Returns true if the AccessibilityService is currently connected and ready.
     */
    fun isAccessibilityConnected(): Boolean =
        GestureAccessibilityService.instance?.isConnected() == true

    /**
     * Execute a quick 3-stroke burst — example demonstrating multi-swipe composition.
     * All three strokes are dispatched in a single GestureDescription with 0/10/20ms offsets.
     */
    fun executeBurstExample() {
        val exampleConfig = com.gestureoverlay.data.GestureConfig(
            label = "Burst Example",
            mode = com.gestureoverlay.data.ExecutionMode.BURST,
            burstCount = 1,
            speedMultiplier = 1.0f,
            strokes = listOf(
                com.gestureoverlay.data.StrokeConfig(
                    startX = 540f, startY = 1200f,
                    endX   = 540f, endY   = 800f,
                    durationMs = 120L, startOffsetMs = 0L
                ),
                com.gestureoverlay.data.StrokeConfig(
                    startX = 400f, startY = 1200f,
                    endX   = 400f, endY   = 800f,
                    durationMs = 120L, startOffsetMs = 10L
                ),
                com.gestureoverlay.data.StrokeConfig(
                    startX = 680f, startY = 1200f,
                    endX   = 680f, endY   = 800f,
                    durationMs = 120L, startOffsetMs = 20L
                )
            )
        )
        execute(exampleConfig)
    }
}
