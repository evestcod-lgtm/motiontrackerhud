package com.motiontracker.hud.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.motiontracker.hud.vision.TrackStatus
import com.motiontracker.hud.vision.TrackedTarget
import kotlin.math.max
import kotlin.math.min

/**
 * Draws all HUD graphics on top of the camera preview:
 *  - center crosshair (reticle)
 *  - locked target bounding box with corner brackets
 *  - status label ("TRACKING ACTIVE" / "SEARCHING" / "TARGET LOST")
 *  - motion trail (fading dot trail of recent target positions)
 *  - subtle corner tick decorations for the tactical aesthetic
 *
 * Coordinates for the target/trail are supplied in analyzer-grid space
 * (gridWidth x gridHeight) and are mapped to view pixels here, accounting for
 * the front camera's horizontal mirroring in the preview.
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var gridWidth = 1
    private var gridHeight = 1
    private var target: TrackedTarget? = null
    private var mirrorHorizontal = true

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#39FF6A")
        strokeCap = Paint.Cap.ROUND
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#39FF6A")
        alpha = 180
    }

    private val coastingBoxPaint = Paint(boxPaint).apply {
        color = Color.parseColor("#FFC24B")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val lostBoxPaint = Paint(boxPaint).apply {
        color = Color.parseColor("#FF4B4B")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#39FF6A")
    }

    private val trailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#39FF6A")
        alpha = 110
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#39FF6A")
        alpha = 140
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 30f
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.08f
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC0D140D")
    }

    private val cornerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1F8A3E")
        alpha = 200
    }

    fun setGridSize(width: Int, height: Int) {
        gridWidth = max(width, 1)
        gridHeight = max(height, 1)
    }

    fun setMirror(mirror: Boolean) {
        mirrorHorizontal = mirror
    }

    /** Call from the UI thread with the latest tracker state; triggers a redraw. */
    fun updateTarget(newTarget: TrackedTarget?) {
        target = newTarget?.copy(trail = ArrayDeque(newTarget.trail))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCornerTicks(canvas)
        drawCrosshair(canvas)

        val t = target ?: return
        drawTrail(canvas, t)
        drawTargetBox(canvas, t)
    }

    private fun gridToViewX(gx: Float): Float {
        val fraction = gx / gridWidth
        val mapped = if (mirrorHorizontal) 1f - fraction else fraction
        return mapped * width
    }

    private fun gridToViewY(gy: Float): Float {
        return (gy / gridHeight) * height
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val size = 40f
        val gap = 10f
        canvas.drawLine(cx - size, cy, cx - gap, cy, crosshairPaint)
        canvas.drawLine(cx + gap, cy, cx + size, cy, crosshairPaint)
        canvas.drawLine(cx, cy - size, cx, cy - gap, crosshairPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + size, crosshairPaint)
        canvas.drawCircle(cx, cy, 3f, trailPaint)
    }

    private fun drawCornerTicks(canvas: Canvas) {
        val len = 36f
        val margin = 24f
        canvas.drawLine(margin, margin, margin + len, margin, cornerTickPaint)
        canvas.drawLine(margin, margin, margin, margin + len, cornerTickPaint)
        canvas.drawLine(width - margin, margin, width - margin - len, margin, cornerTickPaint)
        canvas.drawLine(width - margin, margin, width - margin, margin + len, cornerTickPaint)
        canvas.drawLine(margin, height - margin, margin + len, height - margin, cornerTickPaint)
        canvas.drawLine(margin, height - margin, margin, height - margin - len, cornerTickPaint)
        canvas.drawLine(width - margin, height - margin, width - margin - len, height - margin, cornerTickPaint)
        canvas.drawLine(width - margin, height - margin, width - margin, height - margin - len, cornerTickPaint)
    }

    private fun drawTrail(canvas: Canvas, t: TrackedTarget) {
        val points = t.trail
        if (points.isEmpty()) return

        var prevX: Float? = null
        var prevY: Float? = null
        val n = points.size

        points.forEachIndexed { index, point ->
            val vx = gridToViewX(point.x)
            val vy = gridToViewY(point.y)
            val ageFraction = (index + 1f) / n
            val alpha = (60 + ageFraction * 150).toInt().coerceIn(0, 255)

            if (prevX != null && prevY != null) {
                trailLinePaint.alpha = (alpha * 0.6f).toInt()
                canvas.drawLine(prevX!!, prevY!!, vx, vy, trailLinePaint)
            }

            trailPaint.alpha = alpha
            val radius = 3f + ageFraction * 3f
            canvas.drawCircle(vx, vy, radius, trailPaint)

            prevX = vx
            prevY = vy
        }
    }

    private fun drawTargetBox(canvas: Canvas, t: TrackedTarget) {
        val left = gridToViewX(t.centerX - t.halfWidth)
        val right = gridToViewX(t.centerX + t.halfWidth)
        val top = gridToViewY(t.centerY - t.halfHeight)
        val bottom = gridToViewY(t.centerY + t.halfHeight)

        val rect = RectF(min(left, right), top, max(left, right), bottom)

        val (boxStyle, statusLabel, statusColor) = when (t.status) {
            TrackStatus.TRACKING -> Triple(boxPaint, "TRACKING ACTIVE", "#39FF6A")
            TrackStatus.COASTING -> Triple(coastingBoxPaint, "TRACKING\u2026", "#FFC24B")
            TrackStatus.LOST -> Triple(lostBoxPaint, "TARGET LOST", "#FF4B4B")
            TrackStatus.SEARCHING -> Triple(boxPaint, "SEARCHING", "#39FF6A")
        }

        canvas.drawRect(rect, boxStyle)
        drawCornerBrackets(canvas, rect, boxStyle.color)

        labelPaint.color = Color.parseColor(statusColor)
        val textWidth = labelPaint.measureText(statusLabel)
        val labelX = rect.left
        val labelY = if (rect.top > 60f) rect.top - 16f else rect.bottom + 40f

        canvas.drawRect(
            labelX - 8f, labelY - 34f,
            labelX + textWidth + 8f, labelY + 8f,
            labelBgPaint
        )
        canvas.drawText(statusLabel, labelX, labelY, labelPaint)

        val confidenceText = "CONF ${(t.confidence * 100).toInt()}%"
        labelPaint.textSize = 22f
        val confY = labelY + 26f
        labelPaint.color = Color.parseColor("#7FBF8E")
        canvas.drawText(confidenceText, labelX, confY, labelPaint)
        labelPaint.textSize = 30f
    }

    private fun drawCornerBrackets(canvas: Canvas, rect: RectF, color: Int) {
        val bracketLen = min(rect.width(), rect.height()) * 0.22f
        bracketPaint.color = color

        canvas.drawLine(rect.left, rect.top, rect.left + bracketLen, rect.top, bracketPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + bracketLen, bracketPaint)
        canvas.drawLine(rect.right, rect.top, rect.right - bracketLen, rect.top, bracketPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + bracketLen, bracketPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + bracketLen, rect.bottom, bracketPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - bracketLen, bracketPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right - bracketLen, rect.bottom, bracketPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - bracketLen, bracketPaint)
    }
}
