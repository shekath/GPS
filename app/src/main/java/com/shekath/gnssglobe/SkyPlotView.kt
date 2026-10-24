package com.shekath.gnssglobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Polar sky plot (azimuth/elevation as the phone sees it) plus signal-strength bars. */
class SkyPlotView(context: Context) : View(context) {
    var snapshot: Snapshot? = null
        set(value) { field = value; requestLayout(); invalidate() }
    var selectedKey: Int? = null
        set(value) { field = value; invalidate() }
    var onSelect: ((Int?) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val hits = ArrayList<Triple<Float, Float, Int>>()
    private val barH = 22f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = snapshot?.sats?.size ?: 0
        val h = w + (60 + n * barH) * dp
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF05070D.toInt())
        val snap = snapshot
        val size = width.toFloat()
        val cx = size / 2
        val cy = size / 2
        val r = min(cx, cy) - 28 * dp
        hits.clear()

        fill.color = 0xFF0B1220.toInt()
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = 0x4460A5FA; stroke.strokeWidth = 1f * dp
        for (el in listOf(0, 30, 60)) canvas.drawCircle(cx, cy, r * (90 - el) / 90f, stroke)
        for (az in 0 until 360 step 30) {
            val a = Math.toRadians(az.toDouble())
            canvas.drawLine(cx, cy, cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat(), stroke)
        }
        text.textSize = 13f * dp; text.color = 0xCCFFFFFF.toInt(); text.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - r - 8 * dp, text)
        canvas.drawText("S", cx, cy + r + 18 * dp, text)
        canvas.drawText("E", cx + r + 14 * dp, cy + 5 * dp, text)
        canvas.drawText("W", cx - r - 14 * dp, cy + 5 * dp, text)
        text.textSize = 10f * dp; text.color = 0x8860A5FA.toInt()
        canvas.drawText("30°", cx + 4 * dp, cy - r * 60 / 90f + 12 * dp, text)
        canvas.drawText("60°", cx + 4 * dp, cy - r * 30 / 90f + 12 * dp, text)

        if (snap == null) return
        text.textSize = 10f * dp
        for (s in snap.sats) {
            if (s.elDeg < 0) continue
            val rr = r * (90 - s.elDeg.toFloat()) / 90f
            val a = Math.toRadians(s.azDeg)
            val x = cx + (rr * sin(a)).toFloat()
            val y = cy - (rr * cos(a)).toFloat()
            val rad = (4 + (s.cn0 - 15).coerceIn(0.0, 35.0) / 35.0 * 5).toFloat() * dp
            fill.color = (s.constellation.color and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(x, y, rad * 2.2f, fill)
            if (s.used) {
                fill.color = s.constellation.color
                canvas.drawCircle(x, y, rad, fill)
            } else {
                stroke.color = s.constellation.color; stroke.strokeWidth = 2f * dp
                canvas.drawCircle(x, y, rad, stroke)
            }
            if (s.key == selectedKey) {
                stroke.color = Color.WHITE; stroke.strokeWidth = 1.5f * dp
                canvas.drawCircle(x, y, rad + 5 * dp, stroke)
            }
            text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
            canvas.drawText(s.label, x + rad + 2 * dp, y + 4 * dp, text)
            hits.add(Triple(x, y, s.key))
        }

        // signal strength bars
        var y = size + 24 * dp
        text.textAlign = Paint.Align.LEFT; text.textSize = 13f * dp; text.color = Color.WHITE
        canvas.drawText("Signal strength (C/N₀, dB-Hz) — filled = used in fix", 16 * dp, y, text)
        y += 16 * dp
        text.textSize = 11f * dp
        val left = 56 * dp
        val right = width - 48 * dp
        val rect = RectF()
        for (s in snap.sats) {
            text.color = 0xCCFFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
            canvas.drawText(s.label, 16 * dp, y + 12 * dp, text)
            val frac = (s.cn0 / 50.0).coerceIn(0.02, 1.0).toFloat()
            rect.set(left, y + 3 * dp, left + (right - left) * frac, y + 15 * dp)
            fill.color = if (s.used) s.constellation.color else (s.constellation.color and 0x00FFFFFF) or 0x55000000
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", s.cn0), width - 16 * dp, y + 12 * dp, text)
            y += barH * dp
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val hit = hits.minByOrNull { hypot(it.first - event.x, it.second - event.y) }
            if (hit != null && hypot(hit.first - event.x, hit.second - event.y) < 28 * dp) {
                selectedKey = hit.third
                onSelect?.invoke(hit.third)
            }
        }
        return event.y < width
    }
}
