package com.gestureoverlay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.gestureoverlay.data.GestureConfig
import com.gestureoverlay.data.StrokeConfig

/**
 * GesturePathOverlayView is a full-screen transparent view that draws swipe path
 * previews and animated "touch dot" indicators when a gesture executes.
 *
 * Drawing strategy:
 *  - Each stroke is drawn as a dashed line from start → end.
 *  - A filled circle marks the start point; an arrow-tip marks the end.
 *  - On [flashGesture], paths fade in briefly then fade out over ~600ms.
 *  - All rendering uses hardware-accelerated Canvas ops; no Bitmap allocation in loops.
 *
 * This view is added above all other overlay views with FLAG_NOT_TOUCHABLE so it
 * never intercepts input.
 */
class GesturePathOverlayView(context: Context) : View(context) {

    private data class PathEntry(
        val path: Path,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        var alpha: Int = 255
    )

    private val activePaths = mutableListOf<PathEntry>()
    private var fadeAnimator: ValueAnimator? = null

    // ── Paints ───────────────────────────────────────────────────────────────

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0x8042A5F5.toInt()
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF42A5F5.toInt()
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFEF5350.toInt()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Flash all stroke paths from [config] on screen, then fade them out.
     * Safe to call from any thread.
     */
    fun flashGesture(config: GestureConfig) {
        post {
            buildPathEntries(config)
            startFadeAnimation()
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val entries = synchronized(activePaths) { activePaths.toList() }
        entries.forEach { entry ->
            val a = entry.alpha
            linePaint.alpha  = (a * 0.6f).toInt()
            dotPaint.alpha   = a
            arrowPaint.alpha = a

            canvas.drawPath(entry.path, linePaint)
            canvas.drawCircle(entry.startX, entry.startY, 10f, dotPaint)
            drawArrowHead(canvas, entry.endX, entry.endY,
                entry.endX - entry.startX, entry.endY - entry.startY)
        }
    }

    private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, dx: Float, dy: Float) {
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        val arrowLen = 22f
        val arrowWidth = 10f

        val leftX  = tipX - ux * arrowLen - uy * arrowWidth
        val leftY  = tipY - uy * arrowLen + ux * arrowWidth
        val rightX = tipX - ux * arrowLen + uy * arrowWidth
        val rightY = tipY - uy * arrowLen - ux * arrowWidth

        val triangle = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(triangle, arrowPaint)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildPathEntries(config: GestureConfig) {
        synchronized(activePaths) { activePaths.clear() }
        config.strokes.forEach { stroke ->
            val path = Path().apply {
                moveTo(stroke.startX, stroke.startY)
                lineTo(stroke.endX, stroke.endY)
            }
            synchronized(activePaths) {
                activePaths.add(
                    PathEntry(path, stroke.startX, stroke.startY, stroke.endX, stroke.endY)
                )
            }
        }
        invalidate()
    }

    private fun startFadeAnimation() {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofInt(255, 0).apply {
            duration = 700L
            startDelay = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val alpha = anim.animatedValue as Int
                synchronized(activePaths) {
                    activePaths.forEach { it.alpha = alpha }
                }
                invalidate()
                if (alpha == 0) {
                    synchronized(activePaths) { activePaths.clear() }
                }
            }
            start()
        }
    }
}
