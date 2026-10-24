package com.shekath.gnssglobe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Published mean orbital elements (CelesTrak OMM, i.e. TLE content) for one satellite,
 * propagated with SGP4/SDP4.
 */
class OrbitElements(
    val name: String,
    val noradId: Int,
    val epochMs: Long,
    meanMotionRevPerDay: Double,
    ecc: Double,
    inclDeg: Double,
    raanDeg: Double,
    argpDeg: Double,
    meanAnomDeg: Double,
    bstar: Double = 0.0
) {
    val constellation = Constellation.fromObjectName(name)
    val prn: Int? = parsePrn(name, constellation)

    private val sgp4 = Sgp4(
        epochDays = epochMs / 86_400_000.0 + 2_440_587.5 - 2_433_281.5,
        bstar = bstar,
        ecco0 = ecc,
        argpo0 = Math.toRadians(argpDeg),
        inclo0 = Math.toRadians(inclDeg),
        mo0 = Math.toRadians(meanAnomDeg),
        noKozai = meanMotionRevPerDay * 2 * PI / 1440.0,
        nodeo0 = Math.toRadians(raanDeg)
    )

    val semiMajorAxis: Double get() = sgp4.semiMajorAxisKm
    val periodMinutes: Double get() = 2 * PI / sgp4.noUnkozai

    /** Position in the TEME inertial frame, km. Not thread-safe (SDP4 keeps integrator state). */
    @Synchronized
    fun inertial(tMs: Long): Vec3 = sgp4.position((tMs - epochMs) / 60_000.0)

    /** Earth-fixed position, km. */
    fun ecef(tMs: Long): Vec3 = inertialToEcef(inertial(tMs), tMs)

    /** Age of the element set in days at [tMs]. */
    fun ageDays(tMs: Long) = abs(tMs - epochMs) / 86_400_000.0

    /**
     * Estimated 1-sigma position error (km) of SGP4 with published GNSS element sets:
     * a few km near epoch, growing roughly linearly with element age.
     */
    fun positionErrorKm(tMs: Long) = 3.0 + 2.0 * ageDays(tMs)

    companion object {
        /** TEME → Earth-fixed rotation by Greenwich mean sidereal time (UT1 ≈ UTC). */
        fun inertialToEcef(p: Vec3, tMs: Long): Vec3 {
            val th = Sgp4.gstime(tMs / 86_400_000.0 + 2_440_587.5)
            val c = cos(th); val s = sin(th)
            return Vec3(c * p.x + s * p.y, -s * p.x + c * p.y, p.z)
        }

        private val PRN_GENERIC = Regex("PRN\\s*[A-Z]?(\\d+)")
        private val PRN_BEIDOU = Regex("\\(C(\\d+)\\)")
        private val PRN_GALILEO = Regex("\\(E(\\d+)\\)")

        fun parsePrn(name: String, c: Constellation): Int? {
            val n = name.uppercase()
            val m = when (c) {
                Constellation.BEIDOU -> PRN_BEIDOU.find(n) ?: PRN_GENERIC.find(n)
                Constellation.GALILEO -> PRN_GENERIC.find(n) ?: PRN_GALILEO.find(n)
                Constellation.GPS, Constellation.QZSS -> PRN_GENERIC.find(n)
                else -> null
            }
            return m?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
