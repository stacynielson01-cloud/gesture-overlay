package com.gestureoverlay.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Execution mode for a gesture button.
 */
enum class ExecutionMode {
    SINGLE,     // Fire once per press
    LOOP,       // Repeat on interval until stopped
    BURST       // Fire N times rapidly then stop
}

/**
 * A single stroke definition: one swipe from (x1,y1) → (x2,y2).
 */
@Parcelize
data class StrokeConfig(
    val id: String = UUID.randomUUID().toString(),
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    /** Duration of this individual stroke in milliseconds. */
    val durationMs: Long = 150L,
    /** Offset from gesture dispatch time before this stroke begins (ms). */
    val startOffsetMs: Long = 0L
) : Parcelable

/**
 * Complete configuration for one floating gesture button.
 * A button can carry one or more strokes (burst mode).
 */
@Parcelize
data class GestureConfig(
    val id: String = UUID.randomUUID().toString(),
    /** Human-readable label shown on the floating button. */
    val label: String = "Swipe",
    /** All strokes executed when this button is pressed. */
    val strokes: List<StrokeConfig> = emptyList(),
    /** Execution mode: single, loop, or burst. */
    val mode: ExecutionMode = ExecutionMode.SINGLE,
    /** For BURST mode: how many total repetitions of the stroke set. */
    val burstCount: Int = 1,
    /** For LOOP mode: delay between full cycles (ms). */
    val loopIntervalMs: Long = 500L,
    /** Global speed multiplier applied to all stroke durations. 1.0 = normal. */
    val speedMultiplier: Float = 1.0f,
    /** Additional per-button delay before first stroke fires (ms). */
    val triggerDelayMs: Long = 0L,
    /** Enable coordinate jitter for human-like motion. */
    val jitterEnabled: Boolean = true,
    /** Maximum jitter radius in pixels. */
    val jitterRadiusPx: Float = 3f,
    /** Enable random timing variation. */
    val timingVariationEnabled: Boolean = true,
    /** Maximum random timing variation in ms. */
    val timingVariationMs: Long = 10L,
    /** X position of the floating button on screen (dp). */
    val buttonX: Int = 100,
    /** Y position of the floating button on screen (dp). */
    val buttonY: Int = 300,
    /** Button size in dp. */
    val buttonSizeDp: Int = 56,
    /** Whether button position is locked. */
    val positionLocked: Boolean = false
) : Parcelable

/**
 * A named profile grouping multiple gesture configs.
 */
@Parcelize
data class GestureProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default Profile",
    val gestures: List<GestureConfig> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

/**
 * Result of a gesture dispatch attempt.
 */
sealed class GestureResult {
    object Success : GestureResult()
    data class Failure(val reason: String) : GestureResult()
    object AccessibilityUnavailable : GestureResult()
    object Rejected : GestureResult()
}
