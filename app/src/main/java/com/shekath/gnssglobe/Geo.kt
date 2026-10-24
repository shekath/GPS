package com.shekath.gnssglobe

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Simple 3-vector in kilometres (ECEF unless stated otherwise). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3 = this * (1.0 / norm())
}

/** WGS-84 geometry helpers. All distances in km, angles in degrees unless noted. */
object Geo {
    const val A = 6378.137                 // semi-major axis, km
    const val F = 1.0 / 298.257223563
    const val E2 = F * (2 - F)
    const val MEAN_RADIUS = 6371.0088
    const val C_KM_S = 299792.458          // speed of light, km/s

    fun toRad(d: Double) = Math.toRadians(d)
    fun toDeg(r: Double) = Math.toDegrees(r)

    fun geodeticToEcef(latDeg: Double, lonDeg: Double, altKm: Double): Vec3 {
        val lat = toRad(latDeg)
        val lon = toRad(lonDeg)
        val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
        return Vec3(
            (n + altKm) * cos(lat) * cos(lon),
            (n + altKm) * cos(lat) * sin(lon),
            (n * (1 - E2) + altKm) * sin(lat)
        )
    }

    /** Returns (latDeg, lonDeg, altKm). Bowring's iteration, converges in a few steps. */
    fun ecefToGeodetic(p: Vec3): Triple<Double, Double, Double> {
        val lon = atan2(p.y, p.x)
        val r = hypot(p.x, p.y)
        var lat = atan2(p.z, r * (1 - E2))
        var alt = 0.0
        repeat(6) {
            val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
            alt = r / cos(lat) - n
            lat = atan2(p.z, r * (1 - E2 * n / (n + alt)))
        }
        return Triple(toDeg(lat), toDeg(lon), alt)
    }

    /** Unit vector (ECEF) pointing along azimuth/elevation from a geodetic position. */
    fun enuDirection(latDeg: Double, lonDeg: Double, azDeg: Double, elDeg: Double): Vec3 {
        val lat = toRad(latDeg); val lon = toRad(lonDeg)
        val az = toRad(azDeg); val el = toRad(elDeg)
        val e = cos(el) * sin(az)
        val n = cos(el) * cos(az)
        val u = sin(el)
        return Vec3(
            -sin(lon) * e - sin(lat) * cos(lon) * n + cos(lat) * cos(lon) * u,
            cos(lon) * e - sin(lat) * sin(lon) * n + cos(lat) * sin(lon) * u,
            cos(lat) * n + sin(lat) * u
        )
    }

    /** Azimuth/elevation (deg) of target as seen from a geodetic observer. */
    fun azEl(latDeg: Double, lonDeg: Double, observer: Vec3, target: Vec3): Pair<Double, Double> {
        val d = target - observer
        val lat = toRad(latDeg); val lon = toRad(lonDeg)
        val e = -sin(lon) * d.x + cos(lon) * d.y
        val n = -sin(lat) * cos(lon) * d.x - sin(lat) * sin(lon) * d.y + cos(lat) * d.z
        val u = cos(lat) * cos(lon) * d.x + cos(lat) * sin(lon) * d.y + sin(lat) * d.z
        var az = toDeg(atan2(e, n))
        if (az < 0) az += 360.0
        val el = toDeg(asin(u / d.norm()))
        return az to el
    }

    /** Great-circle angle between two directions given as az/el pairs, degrees. */
    fun angularSeparation(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = toRad(az1); val e1 = toRad(el1)
        val a2 = toRad(az2); val e2 = toRad(el2)
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return toDeg(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
    }

    /**
     * Slant range along a line of sight until it reaches geocentric radius [orbitRadius].
     * Solves |U + d·ê|² = r², i.e. d = −(U·ê) + √((U·ê)² − |U|² + r²).
     */
    fun rangeToShell(user: Vec3, dir: Vec3, orbitRadius: Double): Double {
        val b = user dot dir
        val disc = b * b - (user dot user) + orbitRadius * orbitRadius
        return -b + sqrt(disc.coerceAtLeast(0.0))
    }

    /** Local east/north unit vectors (ECEF) at a geodetic location. */
    fun eastNorthUp(latDeg: Double, lonDeg: Double): Array<Vec3> {
        val lat = toRad(latDeg); val lon = toRad(lonDeg)
        return arrayOf(
            Vec3(-sin(lon), cos(lon), 0.0),
            Vec3(-sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat)),
            Vec3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
        )
    }
}
