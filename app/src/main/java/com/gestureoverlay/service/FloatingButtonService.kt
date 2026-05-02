package com.gestureoverlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gestureoverlay.MainActivity
import com.gestureoverlay.R
import com.gestureoverlay.data.GestureConfig
import com.gestureoverlay.data.GestureConfigRepository
import com.gestureoverlay.gesture.GestureController
import com.gestureoverlay.ui.FloatingButtonView
import com.gestureoverlay.ui.GesturePathOverlayView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FloatingButtonService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "gesture_overlay_service"

/**
 * FloatingButtonService is a foreground service that:
 *  - Creates and manages floating overlay buttons via [WindowManager].
 *  - Observes the active [GestureProfile] and rebuilds buttons when it changes.
 *  - Holds a [WakeLock] (partial) to keep the CPU alive during gesture loops.
 *  - Provides a [GesturePathOverlayView] for visual debug feedback.
 *
 * The service extends [LifecycleService] so it can safely collect [StateFlow]s
 * using [lifecycleScope] without manual lifecycle management.
 */
@AndroidEntryPoint
class FloatingButtonService : LifecycleService() {

    @Inject lateinit var controller: GestureController
    @Inject lateinit var repository: GestureConfigRepository

    private lateinit var windowManager: WindowManager
    private lateinit var wakeLock: PowerManager.WakeLock

    /** One FloatingButtonView per active GestureConfig. */
    private val buttonViews = mutableListOf<FloatingButtonView>()

    /** Full-screen transparent overlay for drawing gesture paths. */
    private var pathOverlay: GesturePathOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        acquireWakeLock()
        startForegroundWithNotification()
        observeActiveProfile()
        addPathOverlay()
        Log.i(TAG, "FloatingButtonService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        controller.stopAll()
        clearButtonViews()
        removePathOverlay()
        releaseWakeLock()
        Log.i(TAG, "FloatingButtonService destroyed")
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Profile observation
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeActiveProfile() {
        lifecycleScope.launch {
            repository.activeProfile.collectLatest { profile ->
                clearButtonViews()
                profile?.gestures?.forEach { config ->
                    addFloatingButton(config)
                }
                if (profile != null) {
                    controller.preWarmProfile(profile)
                }
                Log.d(TAG, "Rebuilt buttons for profile: ${profile?.name} (${profile?.gestures?.size} gestures)")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Floating button management
    // ─────────────────────────────────────────────────────────────────────────

    private fun addFloatingButton(config: GestureConfig) {
        val buttonView = FloatingButtonView(this, config, windowManager) { pressedConfig ->
            onGestureButtonPressed(pressedConfig)
        }
        buttonView.attach()
        buttonViews.add(buttonView)
    }

    private fun onGestureButtonPressed(config: GestureConfig) {
        val pathOverlayRef = pathOverlay
        controller.execute(config) { result ->
            pathOverlayRef?.flashGesture(config)
            Log.d(TAG, "Button '${config.label}' result: $result")
        }
    }

    private fun clearButtonViews() {
        buttonViews.forEach { it.detach() }
        buttonViews.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path debug overlay
    // ─────────────────────────────────────────────────────────────────────────

    private fun addPathOverlay() {
        val overlay = GesturePathOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        pathOverlay = overlay
    }

    private fun removePathOverlay() {
        pathOverlay?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
            pathOverlay = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps gesture overlay buttons running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wake lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GestureOverlay::ExecutionLock"
        )
        wakeLock.acquire(60 * 60 * 1000L /* 1 hour max */)
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        const val ACTION_STOP = "com.gestureoverlay.STOP_OVERLAY"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingButtonService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingButtonService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
