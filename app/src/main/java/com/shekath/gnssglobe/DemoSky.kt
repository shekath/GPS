package com.shekath.gnssglobe

import android.location.Location
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulated receiver for indoor testing: places a phone at a fixed spot and reports the
 * satellites that would be above its horizon, with realistic signal strength, fix
 * geometry and position noise. Uses real orbits when they are cached, otherwise a
 * synthetic constellation laid out like the real systems.
 */
class DemoSky {
    class Sat(
        val c: Constellation, val svid: Int, val az: Double, val el: Double,
        val cn0: Double, val used: Boolean
    )

    private val rnd = Random(System.currentTimeMillis())
    private var noiseE = 0.0
    private var noiseN = 0.0
    private var synthetic: List<OrbitElements>? = null
    private val glonassSlots = HashMap<Int, Int>()

    /** Real orbits when available, otherwise the synthetic set (created once). */
    fun catalog(real: List<OrbitElements>, now: Long): List<OrbitElements> =
        real.ifEmpty { synthetic ?: syntheticConstellation(now).also { synthetic = it } }

    fun location(baseLat: Double, baseLon: Double, baseAltM: Double, now: Long): Location {
        // First-order random walk: fixes wander a few metres like a real receiver.
        noiseE = 0.9 * noiseE + gaussian() * 1.2
        noiseN = 0.9 * noiseN + gaussian() * 1.2
        val r = Geo.MEAN_RADIUS * 1000.0
        return Location(PROVIDER).apply {
            latitude = baseLat + Math.toDegrees(noiseN / r)
            longitude = baseLon + Math.toDegrees(noiseE / (r * cos(Math.toRadians(baseLat))))
            altitude = baseAltM + gaussian() * 2.0
            accuracy = (3.4 + rnd.nextDouble() * 0.8).toFloat()
            if (android.os.Build.VERSION.SDK_INT >= 26) verticalAccuracyMeters = (5.5 + rnd.nextDouble()).toFloat()
            time = now
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    fun satellites(catalog: List<OrbitElements>, loc: Location, now: Long): List<Sat> {
        val user = Geo.geodeticToEcef(loc.latitude, loc.longitude, loc.altitude / 1000.0)
        val visible = ArrayList<Sat>()
        catalog.forEachIndexed { i, o ->
            if (o.constellation == Constellation.UNKNOWN) return@forEachIndexed
            val (az, el) = Geo.azEl(loc.latitude, loc.longitude, user, o.ecef(now))
            if (el < 5) return@forEachIndexed
            val svid = when (o.constellation) {
                Constellation.GLONASS -> glonassSlots.getOrPut(o.noradId) { glonassSlots.size % 24 + 1 }
                else -> o.prn ?: return@forEachIndexed
            }
            // Stronger signals overhead; a fixed per-satellite offset plus a little flicker.
            val bias = ((o.noradId * 7919) % 7) - 3.0
            val cn0 = (18 + 29 * sin(Math.toRadians(el)) + bias + gaussian() * 0.7).coerceIn(12.0, 52.0)
            visible += Sat(o.constellation, svid, az + gaussian() * 0.05, el + gaussian() * 0.05, cn0, false)
        }
        // Like a real receiver, use the strongest satellites well above the horizon.
        val usedKeys = visible.filter { it.el > 10 && it.cn0 > 25 }.sortedByDescending { it.cn0 }
            .take(MAX_USED).toSet()
        return visible.map { if (it in usedKeys) Sat(it.c, it.svid, it.az, it.el, it.cn0, true) else it }
    }

    private fun gaussian(): Double {
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * Math.PI * rnd.nextDouble())
    }

    companion object {
        const val PROVIDER = "demo"
        private const val MAX_USED = 18

        /** Fallback location when the phone has never had a fix: Royal Observatory, Greenwich. */
        const val DEFAULT_LAT = 51.4779
        const val DEFAULT_LON = -0.0015
        const val DEFAULT_ALT_M = 46.0

        /** Walker-style layouts approximating GPS, Galileo, GLONASS, BeiDou and QZSS. */
        fun syntheticConstellation(now: Long): List<OrbitElements> {
            val out = ArrayList<OrbitElements>()
            var id = 90_000
            fun add(name: String, revPerDay: Double, e: Double, inc: Double, raan: Double, argp: Double, m: Double) {
                out += OrbitElements(name, id++, now, revPerDay, e, inc, raan, argp, m)
            }
            for (p in 0 until 6) for (s in 0 until 5) {
                val prn = p * 5 + s + 1
                add("GPS SIM (PRN $prn)", 2.00561, 0.008, 55.0, p * 60.0 + 12, 30.0, s * 72.0 + p * 15)
            }
            for (p in 0 until 3) for (s in 0 until 8) {
                val prn = p * 8 + s + 1
                add("GALILEO SIM (PRN E${"%02d".format(prn)})", 1.70475, 0.0003, 56.0, p * 120.0 + 40, 0.0, s * 45.0 + p * 15)
            }
            for (p in 0 until 3) for (s in 0 until 8) {
                add("GLONASS SIM ${p * 8 + s + 1}", 2.13102, 0.001, 64.8, p * 120.0 + 80, 0.0, s * 45.0 + p * 15)
            }
            for (p in 0 until 3) for (s in 0 until 8) {
                val prn = 19 + p * 8 + s
                add("BEIDOU SIM M (C$prn)", 1.86232, 0.001, 55.0, p * 120.0 + 100, 0.0, s * 45.0 + p * 15)
            }
            for (k in 0 until 3) add("BEIDOU SIM G (C${59 + k})", 1.00272, 0.0002, 0.5, 80.0 + k * 60, 0.0, 30.0)
            for (k in 0 until 3) add("QZS SIM (PRN ${194 + k})", 1.00272, 0.075, 41.0, 150.0 + k * 120, 270.0, 10.0 + k * 120)
            return out
        }
    }
}
