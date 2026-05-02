package com.gestureoverlay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.gestureoverlay.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GestureA11yService"

/**
 * GestureAccessibilityService is the Android system entry point for gesture injection.
 *
 * Key design decisions:
 *  - A companion-object [instance] reference allows [ExecutionEngine] to call
 *    [dispatchGestureDescription] without a tight binding to the service lifecycle.
 *  - [isConnected] is backed by an atomic flag, not the service lifecycle, to avoid
 *    race conditions during service startup/teardown.
 *  - The service does NOT handle any accessibility events (canRetrieveWindowContent=false
 *    in config) — it exists solely as a gesture injector.
 *
 * Callers must always null-check [instance] before use.
 */
class GestureAccessibilityService : AccessibilityService() {

    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        _connectionState.value = true
        Log.i(TAG, "AccessibilityService connected")

        // Notify any waiting UI that service is now available
        sendBroadcast(Intent(ACTION_CONNECTED).setPackage(packageName))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        instance = null
        _connectionState.value = false
        Log.i(TAG, "AccessibilityService disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        _connectionState.value = false
        Log.i(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — this service is gesture-only.
    }

    /**
     * Returns true if this service instance is currently connected to the system.
     */
    fun isConnected(): Boolean = connected

    /**
     * Dispatch a pre-built [GestureDescription] to the Android input system.
     *
     * @param description  The gesture to inject.
     * @param callback     Called on completion or cancellation by the system.
     * @param handler      Handler on which [callback] methods are invoked.
     * @return             true if the gesture was accepted by the framework, false otherwise.
     *
     * Notes:
     *  - This returns false if a gesture is already in progress (framework-level lock).
     *    [ExecutionEngine] serialises calls via its [dispatchMutex] to prevent this.
     *  - On Android 11+ the system may silently reject gestures in certain secure windows.
     *    Callers should check the [GestureResultCallback.onCancelled] path.
     */
    fun dispatchGestureDescription(
        description: GestureDescription,
        callback: GestureResultCallback,
        handler: Handler
    ): Boolean {
        if (!connected) {
            Log.w(TAG, "dispatchGestureDescription called while not connected")
            return false
        }
        return dispatchGesture(description, callback, handler)
    }

    companion object {
        /** Live reference to the currently bound service, or null if not connected. */
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        private val _connectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

        const val ACTION_CONNECTED = "com.gestureoverlay.ACCESSIBILITY_CONNECTED"

        /**
         * Navigate the user to system accessibility settings so they can enable this service.
         */
        fun openAccessibilitySettings(service: android.app.Service) {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            service.startActivity(intent)
        }
    }
}
