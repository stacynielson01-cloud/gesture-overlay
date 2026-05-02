package com.gestureoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gestureoverlay.data.ExecutionMode
import com.gestureoverlay.data.GestureConfig
import com.gestureoverlay.data.GestureConfigRepository
import com.gestureoverlay.data.GestureProfile
import com.gestureoverlay.data.StrokeConfig
import com.gestureoverlay.gesture.GestureController
import com.gestureoverlay.service.FloatingButtonService
import com.gestureoverlay.service.GestureAccessibilityService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainActivity: permission gateway and control panel.
 *
 * Responsibilities:
 *  1. Check and request SYSTEM_ALERT_WINDOW (overlay) permission.
 *  2. Check AccessibilityService status and direct user to settings.
 *  3. Start / stop FloatingButtonService.
 *  4. Seed a default example profile on first launch.
 *
 * The UI is intentionally minimal — the floating buttons are the primary interface.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var repository: GestureConfigRepository
    @Inject lateinit var controller: GestureController

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnStartOverlay: Button
    private lateinit var btnStopOverlay: Button

    private val accessibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupClickListeners()
        observeProfile()
        seedDefaultProfileIfEmpty()

        registerReceiver(
            accessibilityReceiver,
            IntentFilter(GestureAccessibilityService.ACTION_CONNECTED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        unregisterReceiver(accessibilityReceiver)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvOverlayStatus      = findViewById(R.id.tv_overlay_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        btnOverlay           = findViewById(R.id.btn_enable_overlay)
        btnAccessibility     = findViewById(R.id.btn_enable_accessibility)
        btnStartOverlay      = findViewById(R.id.btn_start_overlay)
        btnStopOverlay       = findViewById(R.id.btn_stop_overlay)
    }

    private fun setupClickListeners() {
        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnStartOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                btnOverlay.performClick()
                return@setOnClickListener
            }
            FloatingButtonService.start(this)
        }

        btnStopOverlay.setOnClickListener {
            FloatingButtonService.stop(this)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val overlayOk = hasOverlayPermission()
        val a11yOk    = GestureAccessibilityService.instance?.isConnected() == true

        tvOverlayStatus.text = if (overlayOk)
            getString(R.string.status_overlay_ok)
        else
            getString(R.string.status_overlay_missing)

        tvOverlayStatus.setTextColor(
            getColor(if (overlayOk) R.color.status_ok else R.color.status_error)
        )

        tvAccessibilityStatus.text = if (a11yOk)
            getString(R.string.status_accessibility_ok)
        else
            getString(R.string.status_accessibility_missing)

        tvAccessibilityStatus.setTextColor(
            getColor(if (a11yOk) R.color.status_ok else R.color.status_error)
        )

        btnOverlay.isEnabled      = !overlayOk
        btnStartOverlay.isEnabled = overlayOk
    }

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    // ─────────────────────────────────────────────────────────────────────────
    // Profile seeding
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeProfile() {
        lifecycleScope.launch {
            repository.activeProfile.collectLatest { profile ->
                // Profile available: UI could display button count, name, etc.
            }
        }
    }

    /**
     * On first launch, seed two example gestures so the user sees something immediately:
     *   Button 1 → single upward swipe (center of screen)
     *   Button 2 → 3-stroke burst (three simultaneous upward swipes)
     */
    private fun seedDefaultProfileIfEmpty() {
        lifecycleScope.launch {
            if (repository.profiles.value.isNotEmpty()) return@launch

            val singleSwipe = GestureConfig(
                label = "Swipe Up",
                mode = ExecutionMode.SINGLE,
                buttonX = 60, buttonY = 400,
                strokes = listOf(
                    StrokeConfig(
                        startX = 540f, startY = 1400f,
                        endX   = 540f, endY   = 600f,
                        durationMs = 180L, startOffsetMs = 0L
                    )
                ),
                jitterEnabled = true,
                jitterRadiusPx = 3f,
                timingVariationEnabled = true,
                timingVariationMs = 8L
            )

            // 3-stroke burst: three parallel upward swipes, offset 0/10/20ms
            val burstSwipe = GestureConfig(
                label = "Burst x3",
                mode = ExecutionMode.BURST,
                burstCount = 1,
                buttonX = 60, buttonY = 500,
                strokes = listOf(
                    StrokeConfig(
                        startX = 400f, startY = 1400f,
                        endX   = 400f, endY   = 600f,
                        durationMs = 150L, startOffsetMs = 0L
                    ),
                    StrokeConfig(
                        startX = 540f, startY = 1400f,
                        endX   = 540f, endY   = 600f,
                        durationMs = 150L, startOffsetMs = 10L
                    ),
                    StrokeConfig(
                        startX = 680f, startY = 1400f,
                        endX   = 680f, endY   = 600f,
                        durationMs = 150L, startOffsetMs = 20L
                    )
                ),
                jitterEnabled = true,
                jitterRadiusPx = 4f,
                timingVariationEnabled = true,
                timingVariationMs = 12L
            )

            val loopSwipe = GestureConfig(
                label = "Loop",
                mode = ExecutionMode.LOOP,
                loopIntervalMs = 800L,
                buttonX = 60, buttonY = 600,
                strokes = listOf(
                    StrokeConfig(
                        startX = 540f, startY = 1400f,
                        endX   = 540f, endY   = 900f,
                        durationMs = 200L, startOffsetMs = 0L
                    )
                ),
                jitterEnabled = true,
                jitterRadiusPx = 5f,
                timingVariationEnabled = true,
                timingVariationMs = 15L
            )

            val profile = GestureProfile(
                name = "Default",
                gestures = listOf(singleSwipe, burstSwipe, loopSwipe)
            )

            repository.saveProfile(profile)
            repository.setActiveProfile(profile.id)
        }
    }
}
