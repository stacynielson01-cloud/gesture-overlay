package com.gestureoverlay.gesture

import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.gestureoverlay.data.ExecutionMode
import com.gestureoverlay.data.GestureConfig
import com.gestureoverlay.data.GestureResult
import com.gestureoverlay.service.GestureAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExecutionEngine"

/**
 * ExecutionEngine manages gesture scheduling, loop control, and dispatch serialisation.
 *
 * Design principles:
 * - A dedicated [HandlerThread] serialises all dispatch calls so the main thread is never blocked.
 * - A [Mutex] prevents concurrent dispatchGesture calls on the same [GestureAccessibilityService].
 * - Each active loop runs in an independent [Job] tracked by [activeJobs], keyed by gesture ID.
 * - Debounce is enforced per gesture: rapid presses within [DEBOUNCE_MS] are dropped.
 */
@Singleton
class ExecutionEngine @Inject constructor(
    private val registry: GestureRegistry
) {
    private val dispatchThread = HandlerThread("GestureDispatchThread").also { it.start() }
    private val dispatchHandler = Handler(dispatchThread.looper)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Unhandled coroutine error", t)
        }
    )

    private val dispatchMutex = Mutex()
    private val activeJobs = HashMap<String, Job>()
    private val lastTriggerTime = HashMap<String, Long>()
    private val jobsMutex = Mutex()

    /** Set to true while any gesture is actively being dispatched. */
    val isDispatching = AtomicBoolean(false)

    /**
     * Trigger execution of [config] according to its [ExecutionMode].
     * Debounce is applied: calls within [DEBOUNCE_MS] of the previous call for the same
     * gesture ID are silently dropped.
     *
     * @param config  The gesture to execute.
     * @param service The bound [GestureAccessibilityService]; null → no-op with logged error.
     * @return        Immediate [GestureResult] indicating whether execution was accepted.
     */
    suspend fun trigger(
        config: GestureConfig,
        service: GestureAccessibilityService?
    ): GestureResult {
        if (service == null || !service.isConnected()) {
            Log.w(TAG, "AccessibilityService unavailable for gesture ${config.id}")
            return GestureResult.AccessibilityUnavailable
        }

        // Debounce check
        val now = SystemClock.elapsedRealtime()
        jobsMutex.withLock {
            val last = lastTriggerTime[config.id] ?: 0L
            if (now - last < DEBOUNCE_MS) {
                Log.d(TAG, "Debounce drop for gesture ${config.label}")
                return GestureResult.Failure("Debounced")
            }
            lastTriggerTime[config.id] = now
        }

        return when (config.mode) {
            ExecutionMode.SINGLE -> executeSingle(config, service)
            ExecutionMode.BURST  -> executeBurst(config, service)
            ExecutionMode.LOOP   -> {
                startLoop(config, service)
                GestureResult.Success
            }
        }
    }

    /**
     * Stop a currently running LOOP for the given gesture ID.
     */
    suspend fun stopLoop(gestureId: String) = jobsMutex.withLock {
        activeJobs[gestureId]?.cancel()
        activeJobs.remove(gestureId)
        Log.d(TAG, "Loop stopped for gesture $gestureId")
    }

    /** Stop all active loops immediately. */
    suspend fun stopAll() = jobsMutex.withLock {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        Log.d(TAG, "All loops stopped")
    }

    /** Returns true if a loop is currently running for [gestureId]. */
    suspend fun isLooping(gestureId: String): Boolean = jobsMutex.withLock {
        activeJobs[gestureId]?.isActive == true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal execution strategies
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeSingle(
        config: GestureConfig,
        service: GestureAccessibilityService
    ): GestureResult {
        val delay = config.triggerDelayMs
        if (delay > 0) delay(delay)

        val description = if (config.jitterEnabled || config.timingVariationEnabled) {
            // Fresh path each time for live randomisation
            registry.buildFresh(config)
        } else {
            registry.getOrBuild(config)
        }

        return dispatchBlocking(description, service)
    }

    private suspend fun executeBurst(
        config: GestureConfig,
        service: GestureAccessibilityService
    ): GestureResult {
        var lastResult: GestureResult = GestureResult.Success
        repeat(config.burstCount) { iteration ->
            if (iteration > 0) {
                // Small inter-burst gap so Android doesn't coalesce touches
                delay(BURST_GAP_MS)
            }
            val description = registry.buildFresh(config)
            lastResult = dispatchBlocking(description, service)
            if (lastResult is GestureResult.Failure || lastResult == GestureResult.Rejected) {
                Log.w(TAG, "Burst aborted at iteration $iteration: $lastResult")
                return lastResult
            }
        }
        return lastResult
    }

    private fun startLoop(
        config: GestureConfig,
        service: GestureAccessibilityService
    ) {
        scope.launch {
            jobsMutex.withLock {
                // Cancel any existing loop for this gesture
                activeJobs[config.id]?.cancel()
                activeJobs[config.id] = coroutineContext[Job]!!
            }

            Log.d(TAG, "Loop started for gesture ${config.label}, interval=${config.loopIntervalMs}ms")
            try {
                while (isActive) {
                    val description = registry.buildFresh(config)
                    val result = dispatchBlocking(description, service)

                    if (result == GestureResult.AccessibilityUnavailable) {
                        Log.e(TAG, "Loop terminated: accessibility lost")
                        break
                    }

                    val interval = MotionEngine.applyTimingVariation(
                        baseOffsetMs = config.loopIntervalMs,
                        variationEnabled = config.timingVariationEnabled,
                        maxVariationMs = config.timingVariationMs * 2
                    )
                    delay(interval)
                }
            } finally {
                jobsMutex.withLock { activeJobs.remove(config.id) }
                Log.d(TAG, "Loop ended for gesture ${config.label}")
            }
        }.also { job ->
            scope.launch {
                jobsMutex.withLock { activeJobs[config.id] = job }
            }
        }
    }

    /**
     * Dispatch a gesture synchronously on the [dispatchThread], suspending the caller
     * until the gesture callback completes. The [dispatchMutex] ensures only one gesture
     * is in-flight at a time.
     */
    private suspend fun dispatchBlocking(
        description: GestureDescription,
        service: GestureAccessibilityService
    ): GestureResult = dispatchMutex.withLock {
        suspendCancellableCoroutine { cont ->
            isDispatching.set(true)

            val callback = object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    isDispatching.set(false)
                    if (cont.isActive) cont.resume(GestureResult.Success) {}
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    isDispatching.set(false)
                    if (cont.isActive) cont.resume(GestureResult.Rejected) {}
                }
            }

            dispatchHandler.post {
                val dispatched = service.dispatchGestureDescription(description, callback, dispatchHandler)
                if (!dispatched) {
                    isDispatching.set(false)
                    if (cont.isActive) {
                        cont.resume(GestureResult.Failure("dispatchGesture returned false")) {}
                    }
                }
            }

            cont.invokeOnCancellation {
                isDispatching.set(false)
            }
        }
    }

    fun destroy() {
        scope.cancel()
        dispatchThread.quitSafely()
    }

    companion object {
        private const val DEBOUNCE_MS = 80L
        private const val BURST_GAP_MS = 16L
    }
}
