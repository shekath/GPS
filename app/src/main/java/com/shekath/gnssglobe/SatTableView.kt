package com.shekath.gnssglobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/** Table of every tracked satellite with its distance and error margins. */
class SatTableView(context: Context) : View(context) {
    var snapshot: Snapshot? = null
        set(value) { field = value; requestLayout(); invalidate() }
    var selectedKey: Int? = null
        set(value) { field = value; invalidate() }
    var onSelect: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val rowH = 78 * dp
    private val headerH = 64 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val bold = Paint(text).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val rect = RectF()

    private val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val sats = snapshot?.sats ?: return false
            val i = ((e.y - headerH) / rowH).toInt()
            if (e.y >= headerH && i in sats.indices) onSelect?.invoke(sats[i].key)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent) = tap.onTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = snapshot?.sats?.size ?: 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (headerH + n * rowH + 24 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF05070D.toInt())
        val snap = snapshot ?: return
        val pad = 16 * dp
        bold.textSize = 18f * dp; bold.color = Color.WHITE; bold.textAlign = Paint.Align.LEFT
        canvas.drawText("${snap.sats.size} satellites tracked · ${snap.usedCount} used in fix", pad, 28 * dp, bold)
        text.textSize = 12f * dp; text.color = 0x99FFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
        canvas.drawText("Distance from your phone to each satellite, with its error margin. Tap to view on the globe.",
            pad, 48 * dp, text)

        var top = headerH
        for (s in snap.sats) {
            rect.set(pad / 2, top + 3 * dp, width - pad / 2, top + rowH - 3 * dp)
            fill.color = if (s.key == selectedKey) 0xFF15233D.toInt() else 0xFF0B1220.toInt()
            canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)

            // badge
            rect.set(pad, top + 12 * dp, pad + 48 * dp, top + 36 * dp)
            fill.color = if (s.used) s.constellation.color else (s.constellation.color and 0x00FFFFFF) or 0x44000000
            canvas.drawRoundRect(rect, 7 * dp, 7 * dp, fill)
            bold.textSize = 14f * dp; bold.textAlign = Paint.Align.CENTER
            bold.color = if (s.used) 0xFF05070D.toInt() else Color.WHITE
            canvas.drawText(s.label, rect.centerX(), rect.bottom - 7 * dp, bold)

            val x = pad + 58 * dp
            bold.textAlign = Paint.Align.LEFT; bold.color = Color.WHITE; bold.textSize = 15f * dp
            canvas.drawText(s.constellation.label + if (s.used) "  ✓" else "", x, top + 29 * dp, bold)

            text.textSize = 12f * dp; text.color = 0xB3FFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
            canvas.drawText(String.format(Locale.US, "el %.0f°  az %.0f°  %.0f dB-Hz", s.elDeg, s.azDeg, s.cn0),
                x, top + 48 * dp, text)
            val third = buildList {
                s.altitudeKm?.let { add("alt " + Format.km(it)) }
                when {
                    s.pseudorangeKm != null -> add(String.format(Locale.US, "signal %.2f ms", s.pseudorangeKm / Geo.C_KM_S * 1000))
                    s.rangeKm != null -> add(String.format(Locale.US, "signal ≈%.1f ms", s.rangeKm / Geo.C_KM_S * 1000))
                }
                s.groundErrorM?.let { add("ground ±" + Format.meters(it)) }
            }.joinToString("  ·  ")
            canvas.drawText(third, x, top + 66 * dp, text)

            // distance, right aligned
            bold.textAlign = Paint.Align.RIGHT; bold.textSize = 16f * dp; bold.color = Color.WHITE
            text.textAlign = Paint.Align.RIGHT
            if (s.rangeKm != null && s.rangeErrKm != null) {
                canvas.drawText(Format.km(s.rangeKm), width - pad, top + 29 * dp, bold)
                text.color = if (s.source == RangeSource.ORBIT) 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt()
                val tag = if (s.source == RangeSource.ORBIT) "SGP4" else "SHELL"
                canvas.drawText("± ${Format.meters(s.rangeErrKm * 1000)}  $tag", width - pad, top + 48 * dp, text)
            } else {
                text.color = 0x99FFFFFF.toInt()
                canvas.drawText("no fix yet", width - pad, top + 29 * dp, text)
            }
            top += rowH
        }
    }
}
