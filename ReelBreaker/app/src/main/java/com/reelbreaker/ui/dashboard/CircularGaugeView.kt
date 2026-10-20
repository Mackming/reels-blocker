package com.reelbreaker.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.reelbreaker.R
import kotlin.math.min

/**
 * Cyber Glass 2026 Circular Progress Gauge.
 * Renders an illuminated gradient arc with center metrics for daily reel budget.
 */
class CircularGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var total: Int = 0
        private set
    var budget: Int = 45
        private set

    private var animatedFraction = 0f
    private val arcRect = RectF()

    private val strokeWidthPx = resources.displayMetrics.density * 13f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.surface2)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }

    private val countTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.text_hi)
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.08f
        color = ContextCompat.getColor(context, R.color.text_med)
    }

    private val remTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.accent2)
    }

    fun setProgress(currentTotal: Int, dailyBudget: Int, animate: Boolean = true) {
        val targetTotal = currentTotal.coerceAtLeast(0)
        val targetBudget = dailyBudget.coerceAtLeast(1)
        
        val prevTotal = total
        total = targetTotal
        budget = targetBudget

        val targetFraction = (total.toFloat() / budget.toFloat()).coerceIn(0f, 1f)

        if (animate && prevTotal != targetTotal) {
            val animator = ValueAnimator.ofFloat(animatedFraction, targetFraction)
            animator.duration = 600
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener {
                animatedFraction = it.animatedValue as Float
                invalidate()
            }
            animator.start()
        } else {
            animatedFraction = targetFraction
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h).toFloat()
        val pad = strokeWidthPx / 2f + (resources.displayMetrics.density * 8f)
        arcRect.set(
            (w - size) / 2f + pad,
            (h - size) / 2f + pad,
            (w + size) / 2f - pad,
            (h + size) / 2f - pad
        )

        val accentViolet = ContextCompat.getColor(context, R.color.accent)
        val accentMint = ContextCompat.getColor(context, R.color.accent2)

        progressPaint.shader = SweepGradient(
            w / 2f,
            h / 2f,
            intArrayOf(accentViolet, accentMint, accentViolet),
            floatArrayOf(0f, 0.75f, 1f)
        ).apply {
            val matrix = Matrix()
            matrix.postRotate(-90f, w / 2f, h / 2f)
            setLocalMatrix(matrix)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // 1. Background Track
        canvas.drawOval(arcRect, trackPaint)

        // 2. Animated Progress Arc
        if (animatedFraction > 0.005f) {
            val sweepAngle = 360f * animatedFraction
            canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)
        }

        // 3. Central Typography
        val density = resources.displayMetrics.density
        countTextPaint.textSize = density * 48f
        subTextPaint.textSize = density * 11f
        remTextPaint.textSize = density * 12f

        // Count Number
        val isOver = total > budget
        countTextPaint.color = ContextCompat.getColor(
            context,
            if (isOver) R.color.warn else R.color.text_hi
        )
        canvas.drawText(total.toString(), cx, cy - (density * 2f), countTextPaint)

        // Subtitle "OF X REELS"
        canvas.drawText("OF $budget REELS", cx, cy + (density * 16f), subTextPaint)

        // Remaining Pill text
        val remaining = budget - total
        if (remaining > 0) {
            remTextPaint.color = ContextCompat.getColor(context, R.color.accent2)
            canvas.drawText("$remaining remaining", cx, cy + (density * 34f), remTextPaint)
        } else {
            remTextPaint.color = ContextCompat.getColor(context, R.color.warn)
            canvas.drawText("LIMIT REACHED", cx, cy + (density * 34f), remTextPaint)
        }
    }
}
