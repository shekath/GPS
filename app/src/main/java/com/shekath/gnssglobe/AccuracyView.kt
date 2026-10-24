package com.shekath.gnssglobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * How large the positioning error is on the ground: the receiver's own estimate, the
 * error predicted from satellite geometry (DOP × range error), the measured scatter of
 * recent fixes, and how much each satellite contributes.
 */
class AccuracyView(context: Context) : View(context) {
    var snapshot: Snapshot? = null
        set(value) { field = value; requestLayout(); invalidate() }
    var onReset: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val bold = Paint(text).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val rect = RectF()
    private val resetRect = RectF()
    private val barRow = 24f

    private var contentHeight = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val used = snapshot?.sats?.count { it.groundErrorM != null } ?: 0
        val estimate = 330 * dp + (w - 32 * dp) + 120 * dp + used * barRow * dp + 360 * dp
        setMeasuredDimension(w, (if (contentHeight > 0) contentHeight else estimate).toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return resetRect.contains(event.x, event.y)
        if (event.action == MotionEvent.ACTION_UP && resetRect.contains(event.x, event.y)) onReset?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF05070D.toInt())
        val snap = snapshot ?: return
        val pad = 16 * dp
        var y = 32 * dp
        title(canvas, "Position error on the ground", pad, y)
        y += 14 * dp

        // --- receiver estimate tiles
        val loc = snap.location
        val acc = loc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
        val vacc = if (android.os.Build.VERSION.SDK_INT >= 26 && loc != null && loc.hasVerticalAccuracy())
            loc.verticalAccuracyMeters.toDouble() else null
        val tileW = (width - pad * 2 - 16 * dp) / 3
        tile(canvas, pad, y, tileW, "Horizontal 68%", acc?.let { "±" + Format.meters(it) } ?: "—", 0xFF38BDF8.toInt())
        tile(canvas, pad + tileW + 8 * dp, y, tileW, "Horizontal 95%", acc?.let { "±" + Format.meters(it * R95_OVER_R68) } ?: "—", 0xFFFBBF24.toInt())
        tile(canvas, pad + 2 * (tileW + 8 * dp), y, tileW, "Vertical 68%", vacc?.let { "±" + Format.meters(it) } ?: "—", 0xFFC084FC.toInt())
        y += 86 * dp
        y = small(canvas, "Reported by the phone's GNSS receiver (${loc?.provider ?: "no fix"}).", pad, y)
        y += 30 * dp

        // --- geometry prediction
        title(canvas, "Predicted from satellite geometry", pad, y)
        y += 24 * dp
        val dop = snap.dop
        if (dop != null && snap.predictedH1Sigma != null) {
            y = body(canvas, String.format(Locale.US, "GDOP %.2f   PDOP %.2f   HDOP %.2f   VDOP %.2f   TDOP %.2f",
                dop.gdop, dop.pdop, dop.hdop, dop.vdop, dop.tdop), pad, y)
            y += 22 * dp
            y = body(canvas, "Horizontal ±${Format.meters(snap.predictedH1Sigma)} (1σ) · ±${Format.meters(snap.predictedH1Sigma * 2)} (95%)", pad, y)
            y += 22 * dp
            y = body(canvas, "Vertical ±${Format.meters(snap.predictedV1Sigma ?: 0.0)} (1σ) · quality: ${dopQuality(dop.pdop)}", pad, y)
        } else {
            y = body(canvas, "Needs at least 4 satellites used in the fix.", pad, y)
            y += 44 * dp
        }
        y += 18 * dp
        y = small(canvas, "Position error ≈ DOP × range error. Low DOP = satellites spread across the sky.", pad, y)
        y += 36 * dp

        // --- scatter
        title(canvas, "Measured scatter of your fixes", pad, y)
        bold.textSize = 13f * dp; bold.color = 0xFF38BDF8.toInt(); bold.textAlign = Paint.Align.RIGHT
        canvas.drawText("RESET", width - pad, y, bold)
        resetRect.set(width - pad - 70 * dp, y - 24 * dp, width - pad + 8 * dp, y + 10 * dp)
        bold.textAlign = Paint.Align.LEFT
        y += 14 * dp
        val stats = snap.fixStats
        val side = width - 2 * pad
        rect.set(pad, y, pad + side, y + side)
        drawScatter(canvas, rect, stats, acc)
        y += side + 24 * dp
        if (stats != null) {
            y = body(canvas, String.format(Locale.US, "%d fixes over %.0f s · CEP50 %s · R95 %s",
                stats.count, stats.spanSeconds, Format.meters(stats.cep50), Format.meters(stats.r95)), pad, y)
            y += 22 * dp
            y = body(canvas, "RMS ${Format.meters(stats.rms)} · max ${Format.meters(stats.max)} from the mean position", pad, y)
        } else {
            y = body(canvas, "Collecting fixes… keep the phone still outdoors.", pad, y)
            y += 22 * dp
        }
        y += 18 * dp
        y = small(canvas, "Valid while the phone is stationary: CEP50 = half of fixes fall inside, R95 = 95%.", pad, y)
        y += 36 * dp

        // --- per-satellite contribution
        title(canvas, "Ground error caused by each satellite", pad, y)
        y += 10 * dp
        val used = snap.sats.filter { it.groundErrorM != null }.sortedByDescending { it.groundErrorM }
        val maxErr = used.maxOfOrNull { it.groundErrorM!! }?.coerceAtLeast(0.5) ?: 1.0
        text.textSize = 12f * dp
        for (s in used) {
            y += barRow * dp
            text.color = 0xCCFFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
            canvas.drawText(s.label, pad, y, text)
            val left = pad + 44 * dp
            val right = width - pad - 60 * dp
            rect.set(left, y - 11 * dp, left + ((right - left) * (s.groundErrorM!! / maxErr)).toFloat().coerceAtLeast(2 * dp), y + 1 * dp)
            fill.color = s.constellation.color
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(Format.meters(s.groundErrorM), width - pad, y, text)
        }
        text.textAlign = Paint.Align.LEFT
        y += 26 * dp
        y = small(canvas, "Each satellite's range error (1σ) projected onto the ground through the fix geometry.", pad, y)
        y += 34 * dp

        // --- error budget
        title(canvas, "Range error budget (median, used satellites)", pad, y)
        y += 24 * dp
        val budgets = snap.sats.filter { it.used }.map { it.budget }
        if (budgets.isNotEmpty()) {
            fun med(f: (ErrorModel.RangeBudget) -> Double) = budgets.map(f).sorted()[budgets.size / 2]
            val rows = listOf(
                "Satellite clock & orbit" to med { it.clockEphemeris },
                "Ionosphere (residual)" to med { it.ionosphere },
                "Troposphere (residual)" to med { it.troposphere },
                "Multipath" to med { it.multipath },
                "Receiver noise" to med { it.receiverNoise },
                "Total UERE (1σ)" to med { it.total }
            )
            for ((label, v) in rows) {
                y = body(canvas, label, pad, y)
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(Format.meters(v), width - pad, y, text)
                text.textAlign = Paint.Align.LEFT
                y += 21 * dp
            }
        }
        y += 12 * dp
        y = small(canvas, "Satellite distance margins: SGP4 orbit ±3–10 km; orbit-shell model ±60–530 km.", pad, y)
        y += 18 * dp
        y = small(canvas, "The ground error is metres because the receiver solves time and position together.", pad, y)
        val needed = y + 32 * dp
        if (kotlin.math.abs(needed - height) > 2 * dp) {
            contentHeight = needed
            post { requestLayout() }
        }
    }

    private fun drawScatter(canvas: Canvas, r: RectF, stats: FixStats.Summary?, acc: Double?) {
        fill.color = 0xFF0B1220.toInt()
        canvas.drawRoundRect(r, 14 * dp, 14 * dp, fill)
        val cx = r.centerX(); val cy = r.centerY()
        val extent = max(max(stats?.r95 ?: 0.0, stats?.max ?: 0.0) * 1.15, max(acc ?: 0.0, 5.0) * 1.2)
        val k = (r.width() / 2 - 12 * dp) / extent.toFloat()
        stroke.strokeWidth = 1f * dp; stroke.color = 0x3360A5FA
        canvas.drawLine(r.left + 8 * dp, cy, r.right - 8 * dp, cy, stroke)
        canvas.drawLine(cx, r.top + 8 * dp, cx, r.bottom - 8 * dp, stroke)
        text.textSize = 11f * dp; text.color = 0x8860A5FA.toInt(); text.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, r.top + 20 * dp, text)
        canvas.drawText("E", r.right - 14 * dp, cy - 6 * dp, text)

        // scale bar
        val nice = niceStep(extent / 2)
        val barPx = (nice * k).toFloat()
        stroke.color = 0xAAFFFFFF.toInt(); stroke.strokeWidth = 2f * dp
        canvas.drawLine(r.left + 14 * dp, r.bottom - 16 * dp, r.left + 14 * dp + barPx, r.bottom - 16 * dp, stroke)
        text.color = 0xCCFFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
        canvas.drawText(Format.meters(nice), r.left + 14 * dp, r.bottom - 22 * dp, text)

        if (acc != null) {
            stroke.color = 0xAA38BDF8.toInt(); stroke.strokeWidth = 1.5f * dp
            stroke.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
            canvas.drawCircle(cx, cy, (acc * k).toFloat(), stroke)
            stroke.pathEffect = null
        }
        if (stats == null) return
        val n = stats.offsetsEN.size
        stats.offsetsEN.forEachIndexed { i, (e, nn) ->
            val a = (60 + 195 * i / max(1, n - 1))
            fill.color = Color.argb(a, 230, 240, 255)
            canvas.drawCircle(cx + (e * k).toFloat(), cy - (nn * k).toFloat(), 2.2f * dp, fill)
        }
        stroke.strokeWidth = 2f * dp
        stroke.color = 0xFF4ADE80.toInt(); canvas.drawCircle(cx, cy, (stats.cep50 * k).toFloat(), stroke)
        stroke.color = 0xFFFBBF24.toInt(); canvas.drawCircle(cx, cy, (stats.r95 * k).toFloat(), stroke)
        // legend
        text.textAlign = Paint.Align.RIGHT; text.textSize = 11f * dp
        text.color = 0xFF4ADE80.toInt(); canvas.drawText("CEP50", r.right - 12 * dp, r.bottom - 44 * dp, text)
        text.color = 0xFFFBBF24.toInt(); canvas.drawText("R95", r.right - 12 * dp, r.bottom - 30 * dp, text)
        text.color = 0xFF38BDF8.toInt(); canvas.drawText("reported 68%", r.right - 12 * dp, r.bottom - 16 * dp, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun niceStep(v: Double): Double {
        val p = Math.pow(10.0, Math.floor(Math.log10(max(v, 0.01))))
        val m = v / p
        return p * when { m >= 5 -> 5.0; m >= 2 -> 2.0; else -> 1.0 }
    }

    private fun dopQuality(pdop: Double) = when {
        pdop < 2 -> "excellent"
        pdop < 4 -> "good"
        pdop < 6 -> "moderate"
        else -> "poor"
    }

    private fun tile(canvas: Canvas, x: Float, y: Float, w: Float, label: String, value: String, color: Int) {
        rect.set(x, y, x + w, y + 74 * dp)
        fill.color = 0xFF0B1220.toInt()
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
        fill.color = color
        rect.set(x, y, x + 4 * dp, y + 74 * dp)
        canvas.drawRoundRect(rect, 2 * dp, 2 * dp, fill)
        text.textSize = 11f * dp; text.color = 0x99FFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x + 12 * dp, y + 22 * dp, text)
        bold.textSize = 22f * dp; bold.color = Color.WHITE; bold.textAlign = Paint.Align.LEFT
        canvas.drawText(value, x + 12 * dp, y + 56 * dp, bold)
    }

    private fun title(canvas: Canvas, s: String, x: Float, y: Float) {
        bold.textSize = 17f * dp; bold.color = Color.WHITE; bold.textAlign = Paint.Align.LEFT
        canvas.drawText(s, x, y, bold)
    }

    private fun body(canvas: Canvas, s: String, x: Float, y: Float): Float {
        text.textSize = 14f * dp; text.color = 0xE6FFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
        return wrapped(canvas, s, x, y, 19 * dp)
    }

    private fun small(canvas: Canvas, s: String, x: Float, y: Float): Float {
        text.textSize = 11.5f * dp; text.color = 0x80FFFFFF.toInt(); text.textAlign = Paint.Align.LEFT
        return wrapped(canvas, s, x, y, 15 * dp)
    }

    /** Draws word-wrapped text; returns the baseline of the last line. */
    private fun wrapped(canvas: Canvas, s: String, x: Float, y: Float, lineH: Float): Float {
        val maxW = width - x - 16 * dp
        var line = ""
        var yy = y
        for (word in s.split(" ")) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(t) > maxW && line.isNotEmpty()) {
                canvas.drawText(line, x, yy, text)
                yy += lineH
                line = word
            } else line = t
        }
        if (line.isNotEmpty()) canvas.drawText(line, x, yy, text)
        return yy
    }

    companion object {
        /** For a circular normal distribution, R95 / R68 = 2.448 / 1.515. */
        const val R95_OVER_R68 = 2.4477 / 1.5096
    }
}
