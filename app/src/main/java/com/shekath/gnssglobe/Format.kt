package com.shekath.gnssglobe

import java.util.Locale
import kotlin.math.abs

object Format {
    fun km(v: Double) = String.format(Locale.US, "%,.0f km", v)

    fun meters(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%,.2f km", v / 1000)
        v >= 100 -> String.format(Locale.US, "%.0f m", v)
        else -> String.format(Locale.US, "%.1f m", v)
    }

    fun latLon(lat: Double, lon: Double) = String.format(
        Locale.US, "%.5f°%s  %.5f°%s",
        abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W"
    )

    fun latLonShort(lat: Double, lon: Double) = String.format(
        Locale.US, "%.2f°%s %.2f°%s",
        abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W"
    )

    fun band(mhz: Double?): String {
        if (mhz == null) return ""
        val name = when {
            abs(mhz - 1575.42) < 1 -> "L1/E1"
            abs(mhz - 1176.45) < 1 -> "L5/E5a"
            abs(mhz - 1561.098) < 1 -> "B1I"
            abs(mhz - 1207.14) < 1 -> "E5b/B2I"
            abs(mhz - 1227.60) < 1 -> "L2"
            mhz in 1597.0..1610.0 -> "G1"
            mhz in 1240.0..1260.0 -> "G2"
            else -> ""
        }
        return String.format(Locale.US, "%s %.2f MHz", name, mhz).trim()
    }

    /** Label/value rows describing one satellite. */
    fun satelliteLines(s: SatView): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (s.rangeKm != null && s.rangeErrKm != null) {
            out += "Distance" to String.format(Locale.US, "%,.0f km  ± %s", s.rangeKm, meters(s.rangeErrKm * 1000))
            out += "Method" to when (s.source) {
                RangeSource.ORBIT -> String.format(Locale.US, "SGP4 orbit · sky match %.1f°", s.matchSepDeg ?: 0.0)
                else -> "Orbit-shell model (az/el + orbit radius)"
            }
        } else {
            out += "Distance" to "needs a location fix"
        }
        s.altitudeKm?.let { out += "Altitude" to km(it) }
        out += "Elev / Azim" to String.format(Locale.US, "%.1f° / %.1f°", s.elDeg, s.azDeg)
        out += "Signal" to String.format(Locale.US, "%.0f dB-Hz  %s", s.cn0, band(s.carrierMHz))
        if (s.pseudorangeKm != null) {
            out += "Travel time" to String.format(Locale.US, "%.3f ms (measured)", s.pseudorangeKm / Geo.C_KM_S * 1000)
            out += "Pseudorange" to String.format(Locale.US, "%,.1f km ± %s raw", s.pseudorangeKm, meters(s.pseudorangeSigmaM ?: 0.0))
        } else if (s.rangeKm != null) {
            out += "Travel time" to String.format(Locale.US, "%.2f ms (from distance)", s.rangeKm / Geo.C_KM_S * 1000)
        }
        s.rangeRateKmS?.let { out += "Range rate" to String.format(Locale.US, "%+.3f km/s", it) }
        out += "Range error" to "${meters(s.budget.total)} (1σ signal UERE)"
        if (s.groundErrorM != null) {
            out += "Ground error" to "${meters(s.groundErrorM)} horiz · ${meters(s.verticalErrorM ?: 0.0)} vert"
        }
        if (s.subLat != null && s.subLon != null) out += "Beneath it" to latLonShort(s.subLat, s.subLon)
        s.orbit?.let { out += "Object" to "${it.name} #${it.noradId}" }
        return out
    }
}
