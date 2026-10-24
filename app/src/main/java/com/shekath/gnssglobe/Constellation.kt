package com.shekath.gnssglobe

/**
 * GNSS constellations, ids match android.location.GnssStatus.CONSTELLATION_*.
 * Orbit shells are used when no published orbital elements are available: the
 * satellite is assumed to sit on a sphere of [shellRadius] ± [shellMargin] km,
 * where the margin covers orbit eccentricity (r = a(1 ± e)) for that system.
 */
enum class Constellation(val id: Int, val label: String, val prefix: String, val color: Int) {
    GPS(1, "GPS", "G", 0xFF4ADE80.toInt()),
    SBAS(2, "SBAS", "S", 0xFFB0BEC5.toInt()),
    GLONASS(3, "GLONASS", "R", 0xFFF87171.toInt()),
    QZSS(4, "QZSS", "J", 0xFFC084FC.toInt()),
    BEIDOU(5, "BeiDou", "C", 0xFFFBBF24.toInt()),
    GALILEO(6, "Galileo", "E", 0xFF60A5FA.toInt()),
    IRNSS(7, "NavIC", "I", 0xFFF472B6.toInt()),
    UNKNOWN(0, "Other", "?", 0xFF9CA3AF.toInt());

    /** Nominal geocentric orbit radius (km) and ± uncertainty for a given satellite. */
    fun shell(svid: Int): Pair<Double, Double> = when (this) {
        GPS -> 26_560.0 to 530.0                 // a = 26 560 km, e ≤ 0.02
        GLONASS -> 25_508.0 to 260.0             // e ≤ 0.01
        GALILEO -> if (svid == 14 || svid == 18) {
            27_977.0 to 4_360.0                  // E14/E18 in eccentric orbit, e ≈ 0.16
        } else 29_600.0 to 60.0                  // e ≤ 0.002
        BEIDOU -> if (svid in BEIDOU_GEO_IGSO) 42_164.0 to 420.0 else 27_906.0 to 140.0
        QZSS -> if (svid == 199) 42_164.0 to 50.0 else 42_164.0 to 3_200.0  // IGSO e ≈ 0.075
        SBAS -> 42_164.0 to 50.0                 // geostationary
        IRNSS -> 42_164.0 to 250.0
        UNKNOWN -> 26_560.0 to 16_000.0
    }

    companion object {
        private val BEIDOU_GEO_IGSO =
            setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13, 16, 31, 38, 39, 40, 56, 59, 60, 61, 62, 63)

        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: UNKNOWN

        /** Classify a published object name (e.g. CelesTrak OBJECT_NAME). */
        fun fromObjectName(name: String): Constellation {
            val n = name.uppercase()
            return when {
                n.contains("NAVSTAR") || n.startsWith("GPS") -> GPS
                n.contains("GLONASS") || n.startsWith("COSMOS") -> GLONASS
                n.contains("GALILEO") || n.startsWith("GSAT") -> GALILEO
                n.contains("BEIDOU") -> BEIDOU
                n.startsWith("QZS") || n.contains("MICHIBIKI") -> QZSS
                n.contains("IRNSS") || n.startsWith("NVS") -> IRNSS
                else -> UNKNOWN
            }
        }
    }
}
