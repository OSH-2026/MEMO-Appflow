package com.memoos.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.memoos.R
import kotlin.math.min

class DecisionDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val arcBounds = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.memo_chart_track)
    }
    private val keepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.memo_keep_fill)
    }
    private val prewarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.memo_prewarm_fill)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.memo_hint_fill)
    }
    private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.memo_text_primary)
        textAlign = Paint.Align.CENTER
        textSize = sp(22f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val centerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.memo_text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
    }

    private var keepCount: Int = 0
    private var prewarmCount: Int = 0
    private var hintCount: Int = 0

    fun setDecisionCounts(keep: Int, prewarm: Int, hint: Int) {
        keepCount = keep.coerceAtLeast(0)
        prewarmCount = prewarm.coerceAtLeast(0)
        hintCount = hint.coerceAtLeast(0)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(156f)
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val strokeWidth = size * 0.14f
        trackPaint.strokeWidth = strokeWidth
        keepPaint.strokeWidth = strokeWidth
        prewarmPaint.strokeWidth = strokeWidth
        hintPaint.strokeWidth = strokeWidth

        val padding = strokeWidth / 2f + dp(6f)
        arcBounds.set(padding, padding, width - padding, height - padding)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        val total = keepCount + prewarmCount + hintCount
        if (total > 0) {
            var start = -90f
            start = drawSegment(canvas, start, keepCount, total, keepPaint)
            start = drawSegment(canvas, start, prewarmCount, total, prewarmPaint)
            drawSegment(canvas, start, hintCount, total, hintPaint)
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val totalText = if (total == 0) "0" else total.toString()
        canvas.drawText(totalText, centerX, centerY - dp(2f), centerValuePaint)
        canvas.drawText("actions", centerX, centerY + dp(18f), centerLabelPaint)
    }

    private fun drawSegment(
        canvas: Canvas,
        startAngle: Float,
        count: Int,
        total: Int,
        paint: Paint,
    ): Float {
        if (count <= 0 || total <= 0) return startAngle
        val sweep = 360f * (count.toFloat() / total.toFloat())
        canvas.drawArc(arcBounds, startAngle, sweep, false, paint)
        return startAngle + sweep + 2f
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        ).toInt()
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics,
        )
    }
}
