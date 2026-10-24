package com.shekath.gnssglobe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Interactive 3-D globe: dot-matrix Earth with day/night shading, the phone's position,
 * every tracked GNSS satellite at its computed position in space, their orbit tracks,
 * signal rays to the satellites used in the fix, and the rest of the GNSS catalog.
 */
class GlobeView(context: Context) : View(context) {

    var snapshot: Snapshot? = null
        set(value) {
            field = value
            if (value != null) sun = Sun.directionEcef(value.timeMs)
            invalidate()
        }
    var selectedKey: Int? = null
        set(value) { field = value; invalidate() }
    var onSelect: ((Int?) -> Unit)? = null

    var showOrbits = true
    var showRays = true
    var showCatalog = true
    var autoRotate = true

    private val dp = resources.displayMetrics.density
    private val camDist = 14.0                // camera distance, Earth radii
    private var camLat = 20.0
    private var camLon = 0.0
    private var zoom = 1.0
    private var targetLat: Double? = null
    private var targetLon: Double? = null
    private var velLat = 0.0
    private var velLon = 0.0
    private var lastTouchMs = 0L
    private var focal = 1.0
    private var cx = 0f
    private var cy = 0f
    private var sun = Vec3(1.0, 0.0, 0.0)
    private var centeredOnce = false

    // projection outputs (avoid allocations in the draw loop)
    private var px = 0f
    private var py = 0f
    private var depth = 0.0
    private var pscale = 0.0

    private val land: FloatArray = buildLandPoints(context)
    private val bucketPts = Array(4) { FloatArray(land.size / 3 * 2) }
    private val clipPath = android.graphics.Path()
    private val rayPath = android.graphics.Path()
    private val stars = FloatArray(260 * 3).also {
        val r = Random(7)
        for (i in 0 until 260) { it[i * 3] = r.nextFloat(); it[i * 3 + 1] = r.nextFloat(); it[i * 3 + 2] = r.nextFloat() * 6.28f }
    }

    private val trails = HashMap<Int, FloatArray>()  // NORAD id → xyz (Earth radii)
    private var trailsAtMs = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint().apply { strokeCap = Paint.Cap.ROUND }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val bold = Paint(text).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private var cardRect = RectF()

    // ------------------------------------------------------------------ input

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            velLat = 0.0; velLon = 0.0
            targetLat = null; targetLon = null
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val k = 0.25 / (zoom * dp)
            camLon += dx * k
            camLat = (camLat - dy * k).coerceIn(-85.0, 85.0)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val k = 0.25 / (zoom * dp) / 60.0
            velLon = -vx * k
            velLat = vy * k
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (selectedKey != null && cardRect.contains(e.x, e.y)) {
                focusSelected(); return true
            }
            val snap = snapshot ?: return false
            var best: SatView? = null
            var bestD = 30 * dp
            for (s in snap.sats) {
                val p = s.ecef ?: continue
                project(p.x / R_KM, p.y / R_KM, p.z / R_KM)
                val d = hypot(px - e.x, py - e.y)
                if (d < bestD) { bestD = d; best = s }
            }
            selectedKey = best?.key
            onSelect?.invoke(selectedKey)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            centerOnUser(); return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.6, 5.0)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchMs = System.currentTimeMillis()
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    fun centerOnUser() {
        val loc = snapshot?.location ?: return
        targetLat = loc.latitude; targetLon = loc.longitude
    }

    /** Rotate so the selected satellite faces the camera. */
    fun focusSelected() {
        val s = snapshot?.sats?.firstOrNull { it.key == selectedKey } ?: return
        if (s.subLat != null && s.subLon != null) { targetLat = s.subLat; targetLon = s.subLon }
    }

    fun zoomBy(f: Double) { zoom = (zoom * f).coerceIn(0.6, 5.0) }

    // ------------------------------------------------------------- projection

    /** Earth radii, ECEF → screen. Sets px, py, depth (towards camera) and pscale. */
    private fun project(x: Double, y: Double, z: Double) {
        val lon = Math.toRadians(camLon); val lat = Math.toRadians(camLat)
        val x1 = x * cos(lon) + y * sin(lon)
        val y1 = -x * sin(lon) + y * cos(lon)
        val x2 = x1 * cos(lat) + z * sin(lat)
        val z2 = -x1 * sin(lat) + z * cos(lat)
        depth = x2
        pscale = focal / (camDist - x2)
        px = (cx + y1 * pscale).toFloat()
        py = (cy - z2 * pscale).toFloat()
    }

    /** Is the straight line from the camera to this point blocked by the Earth? */
    private fun hiddenByEarth(x: Double, y: Double, z: Double): Boolean {
        val lon = Math.toRadians(camLon); val lat = Math.toRadians(camLat)
        val x1 = x * cos(lon) + y * sin(lon)
        val y1 = -x * sin(lon) + y * cos(lon)
        val x2 = x1 * cos(lat) + z * sin(lat)
        val z2 = -x1 * sin(lat) + z * cos(lat)
        // camera at (D,0,0); segment C→P
        val dx = x2 - camDist; val dy = y1; val dz = z2
        val t = (-(camDist * dx) / (dx * dx + dy * dy + dz * dz)).coerceIn(0.0, 1.0)
        val qx = camDist + t * dx; val qy = t * dy; val qz = t * dz
        return t < 1.0 && qx * qx + qy * qy + qz * qz < 1.0
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        stepCamera(now)
        cx = width / 2f
        cy = height * 0.47f
        focal = 0.47 * min(width, height) * camDist / 4.9 * zoom
        canvas.drawColor(SPACE)
        drawStars(canvas, now)

        val snap = snapshot
        if (snap != null && showCatalog) drawCatalog(canvas, snap, front = false)
        if (snap != null && showOrbits) drawTrails(canvas, snap, front = false)
        if (snap != null) drawSatellites(canvas, snap, front = false)

        drawEarth(canvas)
        if (snap != null) {
            if (showRays) drawRays(canvas, snap, now)
            drawUser(canvas, snap, now)
            if (showCatalog) drawCatalog(canvas, snap, front = true)
            if (showOrbits) drawTrails(canvas, snap, front = true)
            drawSatellites(canvas, snap, front = true)
            drawHud(canvas, snap)
            drawCard(canvas, snap)
        } else {
            drawCentered(canvas, "Starting GNSS…")
        }
        postInvalidateOnAnimation()
    }

    private fun stepCamera(now: Long) {
        val loc = snapshot?.location
        if (!centeredOnce && loc != null) {
            centeredOnce = true
            targetLat = loc.latitude; targetLon = loc.longitude
        }
        val tl = targetLat; val tn = targetLon
        if (tl != null && tn != null) {
            var dLon = (tn - camLon) % 360.0
            if (dLon > 180) dLon -= 360.0
            if (dLon < -180) dLon += 360.0
            camLon += dLon * 0.08
            camLat += (tl.coerceIn(-85.0, 85.0) - camLat) * 0.08
            if (abs(dLon) < 0.05 && abs(tl - camLat) < 0.05) { targetLat = null; targetLon = null }
        } else if (abs(velLat) + abs(velLon) > 0.001) {
            camLon += velLon; camLat = (camLat + velLat).coerceIn(-85.0, 85.0)
            velLon *= 0.94; velLat *= 0.94
        } else if (autoRotate && now - lastTouchMs > 6000) {
            camLon += 0.04
        }
    }

    private fun drawStars(canvas: Canvas, now: Long) {
        val t = now / 1000f
        for (i in 0 until stars.size / 3) {
            val a = 0.35f + 0.3f * sin(t * 0.8f + stars[i * 3 + 2])
            fill.color = Color.argb((a * 255).toInt(), 200, 215, 255)
            canvas.drawCircle(stars[i * 3] * width, stars[i * 3 + 1] * height, if (i % 9 == 0) 1.4f * dp else 0.8f * dp, fill)
        }
    }

    private fun drawEarth(canvas: Canvas) {
        val r = (focal / sqrt(camDist * camDist - 1)).toFloat()
        // atmosphere glow
        fill.shader = RadialGradient(cx, cy, r * 1.12f, intArrayOf(0x0038BDF8, 0x5538BDF8, 0x0038BDF8),
            floatArrayOf(0.86f, 0.9f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.12f, fill)
        // ocean, lit towards the sun
        project(sun.x, sun.y, sun.z)
        val k = if (depth > 0) 0.6f else 1.6f
        val sx = cx + (px - cx) * k
        val sy = cy + (py - cy) * k
        fill.shader = RadialGradient(sx, sy, r * 1.6f, intArrayOf(0xFF123C78.toInt(), 0xFF0A2350.toInt(), 0xFF040C1E.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null

        // graticule
        stroke.strokeWidth = 0.7f * dp
        stroke.color = 0x2260A5FA
        for (latDeg in -60..60 step 30) drawSurfaceLine(canvas, true, latDeg.toDouble())
        for (lonDeg in -180 until 180 step 30) drawSurfaceLine(canvas, false, lonDeg.toDouble())

        // land dots: bucket by brightness so each bucket is one drawPoints call
        val horizon = 1.0 / camDist
        val buckets = 4
        val counts = IntArray(buckets)
        val n = land.size / 3
        for (i in 0 until n) {
            val x = land[i * 3].toDouble(); val y = land[i * 3 + 1].toDouble(); val z = land[i * 3 + 2].toDouble()
            project(x, y, z)
            if (depth < horizon) continue
            val day = (x * sun.x + y * sun.y + z * sun.z)
            val b = when {
                day > 0.15 -> 3
                day > -0.05 -> 2
                depth > 0.5 -> 1
                else -> 0
            }
            val k = counts[b]
            bucketPts[b][k] = px; bucketPts[b][k + 1] = py
            counts[b] = k + 2
        }
        val colors = intArrayOf(0x7034D399, 0x9934D399.toInt(), 0xCC6EE7B7.toInt(), 0xFFA7F3D0.toInt())
        dotPaint.strokeWidth = (1.9f * dp * zoom.toFloat().coerceIn(0.7f, 2.2f))
        for (b in 0 until buckets) {
            dotPaint.color = colors[b]
            canvas.drawPoints(bucketPts[b], 0, counts[b], dotPaint)
        }
        // night side veil
        project(-sun.x, -sun.y, -sun.z)
        if (depth > -0.95) {
            fill.shader = RadialGradient(px, py, r * 1.3f, intArrayOf(0x88000000.toInt(), 0x44000000, 0x00000000),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawCircle(px, py, r * 1.3f, fill)
            canvas.restore()
            fill.shader = null
        }
    }

    private fun drawSurfaceLine(canvas: Canvas, parallel: Boolean, value: Double) {
        val horizon = 1.0 / camDist
        var prevX = 0f; var prevY = 0f; var prevOk = false
        for (i in 0..72) {
            val a = Math.toRadians(if (parallel) -180.0 + i * 5 else -90.0 + i * 2.5)
            val lat = if (parallel) Math.toRadians(value) else a
            val lon = if (parallel) a else Math.toRadians(value)
            project(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
            val ok = depth > horizon
            if (ok && prevOk) canvas.drawLine(prevX, prevY, px, py, stroke)
            prevX = px; prevY = py; prevOk = ok
        }
    }

    private fun drawCatalog(canvas: Canvas, snap: Snapshot, front: Boolean) {
        val horizon = 1.0 / camDist
        for ((c, p) in snap.catalog) {
            val x = p.x / R_KM; val y = p.y / R_KM; val z = p.z / R_KM
            project(x, y, z)
            if ((depth >= horizon) != front) continue
            val hidden = !front && hiddenByEarth(x, y, z)
            if (hidden) continue
            fill.color = (c.color and 0x00FFFFFF) or 0x55000000
            canvas.drawCircle(px, py, 1.8f * dp, fill)
        }
    }

    private fun updateTrails(snap: Snapshot) {
        if (snap.timeMs - trailsAtMs < 20_000 && snap.sats.all { it.orbit == null || trails.containsKey(it.orbit.noradId) }) return
        trailsAtMs = snap.timeMs
        trails.clear()
        for (s in snap.sats) {
            val o = s.orbit ?: continue
            val steps = 16
            val span = o.periodMinutes * 60_000 * 0.12   // ±12 % of an orbit: shows direction of travel
            val arr = FloatArray((steps + 1) * 3)
            for (i in 0..steps) {
                val t = snap.timeMs + ((i - steps / 2) * span / (steps / 2)).toLong()
                val p = o.ecef(t)
                arr[i * 3] = (p.x / R_KM).toFloat(); arr[i * 3 + 1] = (p.y / R_KM).toFloat(); arr[i * 3 + 2] = (p.z / R_KM).toFloat()
            }
            trails[o.noradId] = arr
        }
    }

    private fun drawTrails(canvas: Canvas, snap: Snapshot, front: Boolean) {
        updateTrails(snap)
        val horizon = 1.0 / camDist
        stroke.strokeWidth = 1.4f * dp
        for (s in snap.sats) {
            val o = s.orbit ?: continue
            val arr = trails[o.noradId] ?: continue
            val sel = s.key == selectedKey
            val n = arr.size / 3
            var prevX = 0f; var prevY = 0f
            for (i in 0 until n) {
                val x = arr[i * 3].toDouble(); val y = arr[i * 3 + 1].toDouble(); val z = arr[i * 3 + 2].toDouble()
                project(x, y, z)
                val cxp = px; val cyp = py
                val isFront = depth >= horizon
                if (i > 0 && isFront == front && !(!front && hiddenByEarth(x, y, z))) {
                    // fade towards both ends; future half brighter than the past
                    val f = 1f - abs(i - n / 2).toFloat() / (n / 2)
                    val alpha = ((if (i > n / 2) 0.55f else 0.3f) * (0.25f + 0.75f * f) * (if (sel) 1.6f else 1f)).coerceAtMost(1f)
                    stroke.color = (s.constellation.color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
                    canvas.drawLine(prevX, prevY, cxp, cyp, stroke)
                }
                prevX = cxp; prevY = cyp
            }
        }
    }

    private fun drawRays(canvas: Canvas, snap: Snapshot, now: Long) {
        val u = snap.userEcef ?: return
        val ux = u.x / R_KM; val uy = u.y / R_KM; val uz = u.z / R_KM
        val horizon = 1.0 / camDist
        rayPaint.strokeWidth = 1.2f * dp
        // dashes flow from the satellite down to the phone
        val phase = -((now % 1000) / 1000f) * 14f * dp
        rayPaint.pathEffect = DashPathEffect(floatArrayOf(8f * dp, 6f * dp), phase)
        val path = rayPath
        for (s in snap.sats) {
            if (!s.used && s.key != selectedKey) continue
            val p = s.ecef ?: continue
            val sx = p.x / R_KM; val sy = p.y / R_KM; val sz = p.z / R_KM
            path.reset()
            var started = false
            val segs = 24
            for (i in 0..segs) {
                val t = i / segs.toDouble()
                val x = sx + (ux - sx) * t; val y = sy + (uy - sy) * t; val z = sz + (uz - sz) * t
                project(x, y, z)
                val visible = depth >= horizon || !hiddenByEarth(x, y, z)
                if (visible) {
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                } else started = false
            }
            val alpha = if (s.key == selectedKey) 0xEE else 0x77
            rayPaint.color = (s.constellation.color and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawPath(path, rayPaint)
        }
        rayPaint.pathEffect = null
    }

    private fun drawUser(canvas: Canvas, snap: Snapshot, now: Long) {
        val u = snap.userEcef ?: return
        project(u.x / R_KM, u.y / R_KM, u.z / R_KM)
        if (depth < 1.0 / camDist) return
        val t = (now % 2000) / 2000f
        fill.color = Color.argb(((1 - t) * 160).toInt(), 56, 189, 248)
        canvas.drawCircle(px, py, (4 + 22 * t) * dp, fill)
        fill.color = 0xFF38BDF8.toInt()
        canvas.drawCircle(px, py, 5f * dp, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 2f * dp
        canvas.drawCircle(px, py, 5f * dp, stroke)
        bold.textSize = 11f * dp; bold.color = Color.WHITE
        canvas.drawText("YOU", px + 9 * dp, py - 7 * dp, bold)
    }

    private fun drawSatellites(canvas: Canvas, snap: Snapshot, front: Boolean) {
        val horizon = 1.0 / camDist
        text.textSize = 10f * dp
        for (s in snap.sats) {
            val p = s.ecef ?: continue
            val x = p.x / R_KM; val y = p.y / R_KM; val z = p.z / R_KM
            project(x, y, z)
            if ((depth >= horizon) != front) continue
            val hidden = !front && hiddenByEarth(x, y, z)
            val sel = s.key == selectedKey
            val r = ((3.0 + (s.cn0 - 15).coerceIn(0.0, 35.0) / 35.0 * 4.0) * dp * (0.75 + 0.25 * zoom.coerceAtMost(2.0))).toFloat()
            val baseAlpha = if (hidden) 0x40 else 0xFF
            val col = (s.constellation.color and 0x00FFFFFF) or (baseAlpha shl 24)
            if (!hidden) {
                fill.color = (s.constellation.color and 0x00FFFFFF) or 0x33000000
                canvas.drawCircle(px, py, r * 2.4f, fill)
            }
            if (s.used) {
                fill.color = col
                canvas.drawCircle(px, py, r, fill)
                stroke.color = if (hidden) 0x40FFFFFF else Color.WHITE
                stroke.strokeWidth = 1.2f * dp
                canvas.drawCircle(px, py, r, stroke)
            } else {
                stroke.color = col; stroke.strokeWidth = 1.8f * dp
                canvas.drawCircle(px, py, r, stroke)
            }
            if (sel) {
                stroke.color = Color.WHITE; stroke.strokeWidth = 1.5f * dp
                canvas.drawCircle(px, py, r + 6 * dp, stroke)
                drawNadir(canvas, s, x, y, z)
                project(x, y, z)
            }
            if (!hidden) {
                text.color = if (sel) Color.WHITE else 0xCCFFFFFF.toInt()
                canvas.drawText(s.label, px + r + 3 * dp, py + 3.5f * dp, text)
            }
        }
    }

    /** Line from the selected satellite down to the point on Earth directly beneath it. */
    private fun drawNadir(canvas: Canvas, s: SatView, x: Double, y: Double, z: Double) {
        val n = sqrt(x * x + y * y + z * z)
        project(x, y, z); val ax = px; val ay = py
        project(x / n, y / n, z / n)
        if (depth < 1.0 / camDist) return
        stroke.color = 0x99FFFFFF.toInt(); stroke.strokeWidth = 1f * dp
        stroke.pathEffect = DashPathEffect(floatArrayOf(3 * dp, 3 * dp), 0f)
        canvas.drawLine(ax, ay, px, py, stroke)
        stroke.pathEffect = null
        fill.color = s.constellation.color
        canvas.drawCircle(px, py, 3f * dp, fill)
    }

    private fun drawHud(canvas: Canvas, snap: Snapshot) {
        val pad = 12 * dp
        var y = pad + 12 * dp
        bold.textSize = 12f * dp
        text.textSize = 12f * dp
        val groups = snap.sats.groupBy { it.constellation }.toSortedMap(compareBy { it.ordinal })
        for ((c, list) in groups) {
            fill.color = c.color
            canvas.drawCircle(pad + 5 * dp, y - 4 * dp, 4.5f * dp, fill)
            bold.color = Color.WHITE
            canvas.drawText(c.label, pad + 15 * dp, y, bold)
            text.color = 0xB3FFFFFF.toInt()
            canvas.drawText("${list.count { it.used }}/${list.size}", pad + 72 * dp, y, text)
            y += 18 * dp
        }
        if (groups.isEmpty()) {
            text.color = 0xB3FFFFFF.toInt()
            canvas.drawText("No satellites yet — go outdoors with a clear sky view", pad, y, text)
        }
        text.textAlign = Paint.Align.RIGHT
        text.color = 0xB3FFFFFF.toInt()
        canvas.drawText(timeFmt.format(Date(snap.timeMs)), width - pad, pad + 12 * dp, text)
        val orbitsLine = when (snap.orbitState) {
            OrbitRepository.State.READY -> "${snap.orbitCount} orbits · SGP4"
            OrbitRepository.State.LOADING -> "loading orbits…"
            OrbitRepository.State.OFFLINE -> "orbits offline · shell model"
            OrbitRepository.State.IDLE -> ""
        }
        canvas.drawText(orbitsLine, width - pad, pad + 30 * dp, text)
        text.textAlign = Paint.Align.LEFT
        if (snap.location == null) drawCentered(canvas, "Waiting for a location fix…")
    }

    private fun drawCentered(canvas: Canvas, msg: String) {
        bold.textSize = 15f * dp; bold.color = Color.WHITE; bold.textAlign = Paint.Align.CENTER
        canvas.drawText(msg, width / 2f, height * 0.9f, bold)
        bold.textAlign = Paint.Align.LEFT
    }

    private fun drawCard(canvas: Canvas, snap: Snapshot) {
        val s = snap.sats.firstOrNull { it.key == selectedKey }
        if (s == null) { cardRect.setEmpty(); return }
        val lines = Format.satelliteLines(s).take(8)
        val pad = 12 * dp
        val lineH = 17 * dp
        val h = pad * 2 + 22 * dp + lines.size * lineH
        cardRect.set(pad, height - h - pad, width - pad, height - pad)
        fill.color = 0xE60B1220.toInt()
        canvas.drawRoundRect(cardRect, 14 * dp, 14 * dp, fill)
        stroke.color = (s.constellation.color and 0x00FFFFFF) or 0x88000000.toInt()
        stroke.strokeWidth = 1.2f * dp
        canvas.drawRoundRect(cardRect, 14 * dp, 14 * dp, stroke)
        var y = cardRect.top + pad + 15 * dp
        fill.color = s.constellation.color
        canvas.drawCircle(cardRect.left + pad + 6 * dp, y - 5 * dp, 6 * dp, fill)
        bold.textSize = 17f * dp; bold.color = Color.WHITE
        canvas.drawText("${s.label}  ${s.constellation.label}${if (s.used) "  · used in fix" else ""}",
            cardRect.left + pad + 18 * dp, y, bold)
        text.textSize = 11f * dp; text.color = 0x99FFFFFF.toInt(); text.textAlign = Paint.Align.RIGHT
        canvas.drawText("tap card to fly here", cardRect.right - pad, y, text)
        text.textAlign = Paint.Align.LEFT
        y += 8 * dp
        text.textSize = 13f * dp
        for ((label, value) in lines) {
            y += lineH
            text.color = 0x99FFFFFF.toInt()
            canvas.drawText(label, cardRect.left + pad, y, text)
            text.color = Color.WHITE
            canvas.drawText(value, cardRect.left + pad + 112 * dp, y, text)
        }
    }

    companion object {
        private const val R_KM = Geo.MEAN_RADIUS
        private const val SPACE = 0xFF05070D.toInt()

        /** Fibonacci-sphere points that fall on land, as unit xyz triples. */
        private fun buildLandPoints(context: Context): FloatArray {
            val mask = context.assets.open("landmask.bin").use { it.readBytes() }
            val n = 14_000
            val out = ArrayList<Float>(n)
            val golden = PI * (3 - sqrt(5.0))
            for (i in 0 until n) {
                val z = 1 - 2 * (i + 0.5) / n
                val r = sqrt(1 - z * z)
                val th = golden * i
                val x = r * cos(th); val y = r * sin(th)
                val lat = Math.toDegrees(asin(z))
                val lon = Math.toDegrees(kotlin.math.atan2(y, x))
                val row = floor((90 - lat) / 0.5).toInt().coerceIn(0, 359)
                val col = floor((lon + 180) / 0.5).toInt().coerceIn(0, 719)
                val bit = row * 720 + col
                if ((mask[bit shr 3].toInt() shr (7 - (bit and 7))) and 1 == 1) {
                    out.add(x.toFloat()); out.add(y.toFloat()); out.add(z.toFloat())
                }
            }
            return out.toFloatArray()
        }
    }
}
