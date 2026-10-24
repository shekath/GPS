package com.shekath.gnssglobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * User-equivalent range error (UERE) budget for a single-frequency phone receiver.
 * Values are 1-sigma residuals after the receiver applies the broadcast corrections
 * (satellite clock/ephemeris, Klobuchar ionosphere, standard troposphere).
 */
object ErrorModel {
    data class RangeBudget(
        val clockEphemeris: Double,
        val ionosphere: Double,
        val troposphere: Double,
        val multipath: Double,
        val receiverNoise: Double
    ) {
        val total: Double
            get() = sqrt(
                clockEphemeris.pow(2) + ionosphere.pow(2) + troposphere.pow(2) +
                    multipath.pow(2) + receiverNoise.pow(2)
            )
    }

    fun rangeBudget(elDeg: Double, cn0: Double, measuredNoiseM: Double?): RangeBudget {
        val el = Math.toRadians(elDeg.coerceAtLeast(2.0))
        // Thin-shell ionosphere obliquity at 350 km.
        val k = Geo.MEAN_RADIUS * cos(el) / (Geo.MEAN_RADIUS + 350.0)
        val obliquity = 1.0 / sqrt(1.0 - k * k)
        val tropoMap = 1.001 / sqrt(0.002001 + sin(el) * sin(el))
        val noiseModel = 0.5 * sqrt(10.0.pow((45.0 - cn0.coerceIn(10.0, 55.0)) / 10.0))
        return RangeBudget(
            clockEphemeris = 1.0,
            ionosphere = 2.0 * obliquity,
            troposphere = 0.2 * tropoMap,
            multipath = 1.0 + 3.0 * exp(-elDeg.coerceAtLeast(0.0) / 15.0),
            receiverNoise = measuredNoiseM?.let { max(it, 0.1) } ?: noiseModel
        )
    }

    data class Dop(
        val gdop: Double, val pdop: Double, val hdop: Double, val vdop: Double, val tdop: Double,
        /** Per satellite (same order as input): horizontal / vertical position shift per metre of range error. */
        val horizontalGain: DoubleArray,
        val verticalGain: DoubleArray
    )

    /**
     * Least-squares geometry for satellites given as (azDeg, elDeg). Returns null if the
     * geometry cannot be solved (fewer than 4 satellites or degenerate).
     */
    fun dop(azEl: List<Pair<Double, Double>>): Dop? {
        val n = azEl.size
        if (n < 4) return null
        val g = Array(n) { i ->
            val az = Math.toRadians(azEl[i].first)
            val el = Math.toRadians(azEl[i].second)
            doubleArrayOf(-cos(el) * sin(az), -cos(el) * cos(az), -sin(el), 1.0)
        }
        val gtg = Array(4) { r -> DoubleArray(4) { c -> (0 until n).sumOf { g[it][r] * g[it][c] } } }
        val q = invert4(gtg) ?: return null
        val hGain = DoubleArray(n)
        val vGain = DoubleArray(n)
        for (i in 0 until n) {
            // Column i of S = Q·Gᵀ maps a range error on satellite i into (E, N, U, clock).
            val s = DoubleArray(4) { r -> (0 until 4).sumOf { k -> q[r][k] * g[i][k] } }
            hGain[i] = sqrt(s[0] * s[0] + s[1] * s[1])
            vGain[i] = abs(s[2])
        }
        val qe = q[0][0]; val qn = q[1][1]; val qu = q[2][2]; val qt = q[3][3]
        if (qe < 0 || qn < 0 || qu < 0 || qt < 0) return null
        return Dop(
            gdop = sqrt(qe + qn + qu + qt), pdop = sqrt(qe + qn + qu),
            hdop = sqrt(qe + qn), vdop = sqrt(qu), tdop = sqrt(qt),
            horizontalGain = hGain, verticalGain = vGain
        )
    }

    private fun invert4(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = 4
        val a = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) m[r][c] else if (c - n == r) 1.0 else 0.0 } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val d = a[col][col]
            for (c in 0 until 2 * n) a[col][c] /= d
            for (r in 0 until n) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in 0 until 2 * n) a[r][c] -= f * a[col][c]
            }
        }
        return Array(n) { r -> DoubleArray(n) { c -> a[r][c + n] } }
    }
}

/**
 * Scatter of recent fixes around their mean: an empirical measurement of how far the
 * reported position wanders on the ground while the phone is stationary.
 */
class FixStats(private val capacity: Int = 600) {
    data class Sample(val lat: Double, val lon: Double, val accuracy: Float, val timeMs: Long)
    data class Summary(
        val count: Int,
        val meanLat: Double, val meanLon: Double,
        val offsetsEN: List<Pair<Double, Double>>,
        val cep50: Double, val r95: Double, val rms: Double, val max: Double,
        val spanSeconds: Double
    )

    private val samples = ArrayDeque<Sample>()

    fun add(s: Sample) {
        samples.addLast(s)
        while (samples.size > capacity) samples.removeFirst()
    }

    fun clear() = samples.clear()

    fun summary(): Summary? {
        if (samples.size < 3) return null
        val mLat = samples.sumOf { it.lat } / samples.size
        val mLon = samples.sumOf { it.lon } / samples.size
        val r = Geo.MEAN_RADIUS * 1000.0
        val cosLat = cos(Math.toRadians(mLat))
        val off = samples.map {
            val e = Math.toRadians(it.lon - mLon) * cosLat * r
            val n = Math.toRadians(it.lat - mLat) * r
            e to n
        }
        val radii = off.map { sqrt(it.first * it.first + it.second * it.second) }.sorted()
        fun pct(p: Double) = radii[((radii.size - 1) * p).toInt()]
        return Summary(
            count = samples.size, meanLat = mLat, meanLon = mLon, offsetsEN = off,
            cep50 = pct(0.5), r95 = pct(0.95),
            rms = sqrt(radii.sumOf { it * it } / radii.size), max = radii.last(),
            spanSeconds = (samples.last().timeMs - samples.first().timeMs) / 1000.0
        )
    }
}

/** Low-precision solar direction (ECEF unit vector) for the day/night terminator. */
object Sun {
    fun directionEcef(tMs: Long): Vec3 {
        val d = tMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d).mod(360.0))
        val q = (280.459 + 0.98564736 * d).mod(360.0)
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val eci = Vec3(cos(l), cos(e) * sin(l), sin(e) * sin(l))
        return OrbitElements.inertialToEcef(eci, tMs)
    }
}
