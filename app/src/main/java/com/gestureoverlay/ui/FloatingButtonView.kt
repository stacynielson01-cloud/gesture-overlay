package com.gestureoverlay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.gestureoverlay.data.GestureConfig
import kotlin.math.abs

/**
 * FloatingButtonView renders a single draggable overlay button.
 *
 * Each button is a standalone [View] added directly to [WindowManager] with
 * TYPE_APPLICATION_OVERLAY so it floats over all other apps.
 *
 * Features:
 *  - Fully draggable (tracks finger delta from initial touch position)
 *  - Locks position when [GestureConfig.positionLocked] is true
 *  - Visual press feedback (color change + alpha pulse)
 *  - Distinguishes tap from drag to prevent accidental trigger during move
 *  - Haptic feedback on press via [Vibrator]
 *  - Exposes [onPress] callback for gesture execution
 */
class FloatingButtonView(
    private val context: Context,
    private var config: GestureConfig,
    private val windowManager: WindowManager,
    private val onPress: (GestureConfig) -> Unit
) {
    private val rootView: FrameLayout = FrameLayout(context)
    private val label: TextView
    private val vibrator: Vibrator = context.getSystemService(Vibrator::class.java)

    private lateinit var params: WindowManager.LayoutParams
    private var attached = false

    // Drag state
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDragging = false

    init {
        val sizePx = dpToPx(config.buttonSizeDp)

        label = TextView(context).apply {
            text = config.label
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        rootView.apply {
            setBackgroundColor(0xCC1976D2.toInt())
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
            elevation = 8f
        }

        setupTouchListener(sizePx)
    }

    /** Add this view to the WindowManager. */
    fun attach() {
        if (attached) return
        params = WindowManager.LayoutParams(
            dpToPx(config.buttonSizeDp),
            dpToPx(config.buttonSizeDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = config.buttonX
            y = config.buttonY
        }
        windowManager.addView(rootView, params)
        attached = true
    }

    /** Remove this view from the WindowManager. */
    fun detach() {
        if (!attached) return
        try { windowManager.removeView(rootView) } catch (e: Exception) { /* view already gone */ }
        attached = false
    }

    /** Update the button to reflect a new [GestureConfig] (e.g. label or size change). */
    fun updateConfig(newConfig: GestureConfig) {
        config = newConfig
        label.text = newConfig.label
        if (attached) {
            params.width  = dpToPx(newConfig.buttonSizeDp)
            params.height = dpToPx(newConfig.buttonSizeDp)
            windowManager.updateViewLayout(rootView, params)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Touch handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTouchListener(sizePx: Int) {
        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX  = event.rawX
                    initialTouchY  = event.rawY
                    initialParamX  = params.x
                    initialParamY  = params.y
                    isDragging     = false
                    showPressed(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                        isDragging = true
                    }

                    if (isDragging && !config.positionLocked) {
                        params.x = (initialParamX + dx).toInt()
                        params.y = (initialParamY + dy).toInt()
                        windowManager.updateViewLayout(rootView, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    showPressed(false)
                    if (!isDragging) {
                        vibrate()
                        onPress(config)
                    }
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    showPressed(false)
                    isDragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun showPressed(pressed: Boolean) {
        rootView.animate()
            .alpha(if (pressed) 0.65f else 1.0f)
            .scaleX(if (pressed) 0.90f else 1.0f)
            .scaleY(if (pressed) 0.90f else 1.0f)
            .setDuration(80)
            .start()

        val color = if (pressed) 0xCC0D47A1.toInt() else 0xCC1976D2.toInt()
        rootView.setBackgroundColor(color)
    }

    private fun vibrate() {
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { /* no vibrator */ }
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            context.resources.displayMetrics).toInt()

    companion object {
        private const val DRAG_THRESHOLD = 8f
    }
}
