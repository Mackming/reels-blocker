package com.reelbreaker.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.reelbreaker.R

/** Minimal 7-day bar chart: reels per day + budget line. */
class HistoryView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var data: List<Int> = emptyList()
    var budget: Int = 30
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C5CFF.toInt() }
    private val overPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6B6B.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF; strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA3BF.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER
    }

    fun setData(d: List<Int>) { data = d; invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (data.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val maxV = (data.maxOrNull() ?: 1).coerceAtLeast(budget + 5)
        val slot = w / data.size
        val bw = slot * 0.55f

        // budget line
        val by = h - (budget / maxV.toFloat()) * (h - 60)
        c.drawLine(0f, by, w, by, gridPaint)

        for (i in data.indices) {
            val v = data[i]
            val bh = (v / maxV.toFloat()) * (h - 60)
            val left = i * slot + (slot - bw) / 2
            barPaint.color = if (v > budget) 0xFFFF6B6B.toInt() else context.getColor(R.color.accent)
            c.drawRoundRect(left, h - 60 - bh, left + bw, h - 60, 8f, 8f, barPaint)
            c.drawText(if (v == 0) "·" else "$v", left + bw / 2, h - 20, labelPaint)
        }
    }
}
