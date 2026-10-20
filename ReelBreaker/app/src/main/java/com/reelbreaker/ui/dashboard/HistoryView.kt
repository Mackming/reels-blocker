package com.reelbreaker.ui.dashboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.reelbreaker.R

/**
 * Cyber Glass 7-day capsule bar chart.
 * Renders smooth pill bars with budget threshold reference line and day labels.
 */
class HistoryView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var data: List<Int> = emptyList()
    var budget: Int = 45
        set(value) {
            field = value
            invalidate()
        }

    private val dayLabels = arrayOf("M", "T", "W", "T", "F", "S", "Today")

    private val defaultBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.surface3)
    }

    private val todayBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.accent2)
    }

    private val overBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.warn)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val gridPath = Path()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.text_med)
        textSize = resources.displayMetrics.density * 11f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val budgetLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.text_lo)
        textSize = resources.displayMetrics.density * 9f
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun setData(d: List<Int>) {
        data = d
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (data.isEmpty()) return

        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val bottomPadding = 26f * density
        val topPadding = 18f * density
        val chartHeight = h - bottomPadding - topPadding

        val maxV = (data.maxOrNull() ?: 1).coerceAtLeast(budget + 10)
        val slot = w / data.size.toFloat()
        val bw = (slot * 0.42f).coerceAtMost(22f * density)

        // 1. Budget guide line
        val budgetRatio = (budget.toFloat() / maxV.toFloat()).coerceIn(0.05f, 0.95f)
        val by = (h - bottomPadding) - (budgetRatio * chartHeight)
        gridPath.reset()
        gridPath.moveTo(0f, by)
        gridPath.lineTo(w, by)
        c.drawPath(gridPath, gridPaint)
        c.drawText("$budget max", w - 4f, by - (4f * density), budgetLabelPaint)

        // 2. Capsule bars
        for (i in data.indices) {
            val v = data[i]
            val barHeightRatio = (v.toFloat() / maxV.toFloat()).coerceIn(0.04f, 1f)
            val bh = barHeightRatio * chartHeight
            val left = i * slot + (slot - bw) / 2f
            val right = left + bw
            val bottom = h - bottomPadding
            val top = bottom - bh

            val isToday = i == data.size - 1
            val paint = when {
                v > budget -> overBarPaint
                isToday -> todayBarPaint
                else -> defaultBarPaint
            }

            // Rounded capsule
            val radius = bw / 2f
            c.drawRoundRect(left, top, right, bottom, radius, radius, paint)

            // Day label
            val label = if (i < dayLabels.size) dayLabels[i] else "·"
            labelPaint.color = ContextCompat.getColor(
                context,
                if (isToday) R.color.text_hi else R.color.text_med
            )
            c.drawText(label, left + bw / 2f, h - (6f * density), labelPaint)
        }
    }
}
