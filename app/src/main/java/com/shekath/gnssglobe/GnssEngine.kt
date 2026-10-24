package com.shekath.gnssglobe

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

enum class RangeSource { ORBIT, SHELL }

/** Everything the UI shows about one tracked satellite. */
data class SatView(
    val key: Int,
    val constellation: Constellation,
    val svid: Int,
    val azDeg: Double,
    val elDeg: Double,
    val cn0: Double,
    val used: Boolean,
    val hasEphemeris: Boolean,
    val carrierMHz: Double?,
    /** Earth-fixed position, km (null until the phone has a location). */
    val ecef: Vec3?,
    val rangeKm: Double?,
    /** ± uncertainty of [rangeKm], km. */
    val rangeErrKm: Double?,
    /** 3-D position uncertainty of [ecef], km. */
    val posErrKm: Double?,
    val altitudeKm: Double?,
    val subLat: Double?,
    val subLon: Double?,
    val source: RangeSource?,
    val orbit: OrbitElements?,
    /** Angle between phone-reported direction and the SGP4 prediction, degrees. */
    val matchSepDeg: Double?,
    val rangeRateKmS: Double?,
    /** Raw pseudorange from signal travel time, km (needs raw GNSS measurement support). */
    val pseudorangeKm: Double?,
    val pseudorangeSigmaM: Double?,
    val dopplerRateMs: Double?,
    val budget: ErrorModel.RangeBudget,
    /** Horizontal position error this satellite's range error causes on the ground, m. */
    val groundErrorM: Double?,
    val verticalErrorM: Double?
) {
    val label get() = constellation.prefix + svid
}

data class Snapshot(
    val timeMs: Long,
    val location: Location?,
    val userEcef: Vec3?,
    val sats: List<SatView>,
    val dop: ErrorModel.Dop?,
    val predictedH1Sigma: Double?,
    val predictedV1Sigma: Double?,
    val catalog: List<Pair<Constellation, Vec3>>,
    val rawMeasurements: Boolean?,
    val ttffMs: Int?,
    val orbitState: OrbitRepository.State,
    val orbitCount: Int,
    val fixStats: FixStats.Summary?
) {
    val usedCount get() = sats.count { it.used }
}

/** Collects GNSS status, raw measurements and fixes, and turns them into a [Snapshot]. */
class GnssEngine(context: Context, private val orbits: OrbitRepository) {

    var listener: ((Snapshot) -> Unit)? = null
    var latest: Snapshot? = null; private set
    val fixStats = FixStats()

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())

    private class RawSat(
        val c: Constellation, val svid: Int, var az: Double, var el: Double, var cn0: Double,
        var used: Boolean, var eph: Boolean, var carrierMHz: Double?
    )

    private class Measured(val rangeKm: Double?, val sigmaM: Double?, val dopplerMs: Double, val atMs: Long)

    private var rawSats: Map<Int, RawSat> = emptyMap()
    private val measured = HashMap<Int, Measured>()
    private var location: Location? = null
    private var rawSupported: Boolean? = null
    private var ttffMs: Int? = null
    private val matches = HashMap<Int, Int>() // satellite key → NORAD id
    private var lastComputeMs = 0L
    private var pending = false

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onFirstFix(ttffMillis: Int) { ttffMs = ttffMillis }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val map = HashMap<Int, RawSat>()
            for (i in 0 until status.satelliteCount) {
                val c = Constellation.fromId(status.getConstellationType(i))
                val svid = status.getSvid(i)
                val key = c.id * 1000 + svid
                val carrier = if (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i))
                    status.getCarrierFrequencyHz(i) / 1e6 else null
                val existing = map[key]
                if (existing == null) {
                    map[key] = RawSat(
                        c, svid, status.getAzimuthDegrees(i).toDouble(), status.getElevationDegrees(i).toDouble(),
                        status.getCn0DbHz(i).toDouble(), status.usedInFix(i), status.hasEphemerisData(i), carrier
                    )
                } else {
                    // Same satellite on a second frequency band (e.g. L1 + L5): merge.
                    existing.cn0 = maxOf(existing.cn0, status.getCn0DbHz(i).toDouble())
                    existing.used = existing.used || status.usedInFix(i)
                    existing.eph = existing.eph || status.hasEphemerisData(i)
                    if (carrier != null && (existing.carrierMHz == null || carrier > 1500)) existing.carrierMHz = carrier
                }
            }
            rawSats = map
            requestCompute()
        }
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            rawSupported = true
            val now = System.currentTimeMillis()
            for (m in event.measurements) {
                val key = m.constellationType * 1000 + m.svid
                val pr = pseudorange(event.clock, m)
                val prev = measured[key]
                // Prefer the L1/E1/B1 band when a satellite reports several signals.
                val isL1 = !m.hasCarrierFrequencyHz() || m.carrierFrequencyHz > 1.5e9
                if (prev != null && prev.atMs == now && !isL1) continue
                measured[key] = Measured(pr?.first, pr?.second, m.pseudorangeRateMetersPerSecond, now)
            }
        }

        override fun onStatusChanged(status: Int) {
            rawSupported = status == STATUS_READY
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val cur = location
            // A GPS fix always wins; a network fix is only used until GPS has one.
            if (loc.provider == LocationManager.GPS_PROVIDER || cur == null ||
                cur.provider != LocationManager.GPS_PROVIDER ||
                SystemClock.elapsedRealtimeNanos() - cur.elapsedRealtimeNanos > 10_000_000_000L
            ) {
                location = loc
            }
            if (loc.provider == LocationManager.GPS_PROVIDER) {
                fixStats.add(FixStats.Sample(loc.latitude, loc.longitude, loc.accuracy, loc.time))
            }
            requestCompute()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    val isGpsEnabled get() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start() {
        val looper = Looper.getMainLooper()
        lm.registerGnssStatusCallback(statusCallback, main)
        lm.registerGnssMeasurementsCallback(measurementCallback, main)
        if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, looper)
        }
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locationListener, looper)
            }
        }
        if (location == null) {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) location = last
        }
        ticker.run()
    }

    fun stop() {
        main.removeCallbacks(ticker)
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measurementCallback)
        lm.removeUpdates(locationListener)
    }

    /** Recompute once a second even without callbacks so satellites keep moving. */
    private val ticker = object : Runnable {
        override fun run() {
            compute()
            main.postDelayed(this, 1000L)
        }
    }

    fun requestCompute() {
        val now = SystemClock.uptimeMillis()
        if (now - lastComputeMs >= 250) {
            compute()
        } else if (!pending) {
            pending = true
            main.postAtTime({ pending = false; compute() }, lastComputeMs + 250)
        }
    }

    private fun compute() {
        lastComputeMs = SystemClock.uptimeMillis()
        val now = System.currentTimeMillis()
        val loc = location
        val user = loc?.let {
            Geo.geodeticToEcef(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude / 1000.0 else 0.0)
        }

        // Propagate every published GNSS orbit to "now".
        val catalogOrbits = orbits.orbits
        val catalogPos = catalogOrbits.map { it.ecef(now) }
        val catalogAzEl = if (user != null && loc != null) catalogPos.map { Geo.azEl(loc.latitude, loc.longitude, user, it) } else null
        val byNorad = HashMap<Int, Int>().apply { catalogOrbits.forEachIndexed { i, o -> put(o.noradId, i) } }
        val matchedIdx = HashSet<Int>()

        val views = ArrayList<SatView>(rawSats.size)
        for ((key, s) in rawSats) {
            val meas = measured[key]?.takeIf { now - it.atMs < 3000 }
            var ecef: Vec3? = null
            var range: Double? = null
            var rangeErr: Double? = null
            var posErr: Double? = null
            var source: RangeSource? = null
            var orbit: OrbitElements? = null
            var sep: Double? = null
            var rate: Double? = null

            if (user != null && loc != null) {
                val idx = if (catalogAzEl != null) match(key, s, catalogOrbits, catalogAzEl, byNorad) else null
                if (idx != null) {
                    orbit = catalogOrbits[idx]
                    matchedIdx.add(idx)
                    ecef = catalogPos[idx]
                    range = (ecef - user).norm()
                    rangeErr = orbit.positionErrorKm(now)
                    posErr = rangeErr
                    val (az, el) = catalogAzEl!![idx]
                    sep = Geo.angularSeparation(az, el, s.az, s.el)
                    rate = (orbit.ecef(now + 1000) - user).norm() - range
                    source = RangeSource.ORBIT
                } else {
                    val (r, dr) = s.c.shell(s.svid)
                    val dir = Geo.enuDirection(loc.latitude, loc.longitude, s.az, s.el)
                    val d = Geo.rangeToShell(user, dir, r)
                    val radial = (Geo.rangeToShell(user, dir, r + dr) - Geo.rangeToShell(user, dir, r - dr)) / 2
                    val dirHi = Geo.enuDirection(loc.latitude, loc.longitude, s.az, s.el + ANGLE_ERR_DEG)
                    val dirLo = Geo.enuDirection(loc.latitude, loc.longitude, s.az, s.el - ANGLE_ERR_DEG)
                    val angular = abs(Geo.rangeToShell(user, dirHi, r) - Geo.rangeToShell(user, dirLo, r)) / 2
                    ecef = user + dir * d
                    range = d
                    rangeErr = sqrt(radial * radial + angular * angular)
                    val lateral = d * Math.toRadians(ANGLE_ERR_DEG)
                    posErr = sqrt(rangeErr * rangeErr + lateral * lateral)
                    source = RangeSource.SHELL
                }
            }
            val geo = ecef?.let { Geo.ecefToGeodetic(it) }
            views.add(
                SatView(
                    key = key, constellation = s.c, svid = s.svid, azDeg = s.az, elDeg = s.el, cn0 = s.cn0,
                    used = s.used, hasEphemeris = s.eph, carrierMHz = s.carrierMHz,
                    ecef = ecef, rangeKm = range, rangeErrKm = rangeErr, posErrKm = posErr,
                    altitudeKm = geo?.third, subLat = geo?.first, subLon = geo?.second,
                    source = source, orbit = orbit, matchSepDeg = sep, rangeRateKmS = rate,
                    pseudorangeKm = meas?.rangeKm, pseudorangeSigmaM = meas?.sigmaM,
                    dopplerRateMs = meas?.dopplerMs,
                    budget = ErrorModel.rangeBudget(s.el, s.cn0, meas?.sigmaM),
                    groundErrorM = null, verticalErrorM = null
                )
            )
        }

        // Geometry of the satellites the receiver actually used for the fix.
        val usedIdx = views.indices.filter { views[it].used && views[it].elDeg > 0 }
        val dop = ErrorModel.dop(usedIdx.map { views[it].azDeg to views[it].elDeg })
        var predH: Double? = null
        var predV: Double? = null
        if (dop != null) {
            var h2 = 0.0; var v2 = 0.0
            usedIdx.forEachIndexed { j, i ->
                val sigma = views[i].budget.total
                val gh = dop.horizontalGain[j] * sigma
                val gv = dop.verticalGain[j] * sigma
                h2 += gh * gh; v2 += gv * gv
                views[i] = views[i].copy(groundErrorM = gh, verticalErrorM = gv)
            }
            predH = sqrt(h2); predV = sqrt(v2)
        }

        views.sortWith(compareByDescending<SatView> { it.used }.thenByDescending { it.cn0 })
        val catalog = catalogOrbits.indices.filter { it !in matchedIdx }
            .map { catalogOrbits[it].constellation to catalogPos[it] }

        val snap = Snapshot(
            timeMs = now, location = loc, userEcef = user, sats = views, dop = dop,
            predictedH1Sigma = predH, predictedV1Sigma = predV, catalog = catalog,
            rawMeasurements = rawSupported, ttffMs = ttffMs, orbitState = orbits.state,
            orbitCount = catalogOrbits.size, fixStats = fixStats.summary()
        )
        latest = snap
        listener?.invoke(snap)
    }

    /**
     * Pairs a tracked satellite with a published orbit: first the remembered match, then a
     * PRN taken from the object name, then the nearest predicted direction in the sky.
     * Every candidate must agree with the phone's own azimuth/elevation.
     */
    private fun match(
        key: Int, s: RawSat, cat: List<OrbitElements>, azEl: List<Pair<Double, Double>>,
        byNorad: Map<Int, Int>
    ): Int? {
        fun sepOf(i: Int) = Geo.angularSeparation(azEl[i].first, azEl[i].second, s.az, s.el)
        matches[key]?.let { norad ->
            val i = byNorad[norad]
            if (i != null && sepOf(i) < MATCH_KEEP_DEG) return i
            matches.remove(key)
        }
        val candidates = cat.indices.filter { cat[it].constellation == s.c }
        if (candidates.isEmpty()) return null
        candidates.filter { cat[it].prn == s.svid }.minByOrNull { sepOf(it) }?.let {
            if (sepOf(it) < MATCH_KEEP_DEG) { matches[key] = cat[it].noradId; return it }
        }
        val sorted = candidates.map { it to sepOf(it) }.sortedBy { it.second }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.second ?: 180.0
        if (best.second < MATCH_NEW_DEG && second > best.second + MATCH_NEW_DEG) {
            matches[key] = cat[best.first].noradId
            return best.first
        }
        return null
    }

    companion object {
        /** Assumed accuracy of phone-reported elevation (often whole degrees). */
        const val ANGLE_ERR_DEG = 0.5
        private const val MATCH_KEEP_DEG = 3.0
        private const val MATCH_NEW_DEG = 1.5

        private const val STATE_TOW_DECODED = 8
        private const val STATE_GLO_TOD_DECODED = 128
        private const val STATE_TOW_KNOWN = 16384
        private const val STATE_GLO_TOD_KNOWN = 32768
        private const val WEEK_NS = 604_800_000_000_000L
        private const val DAY_NS = 86_400_000_000_000L

        /**
         * Raw pseudorange = c · (receive time − transmit time) from Android raw GNSS
         * measurements. Returns (km, 1-sigma metres). It still contains the satellite
         * clock offset and atmospheric delays, which the receiver removes when it solves
         * the fix.
         */
        fun pseudorange(clock: GnssClock, m: GnssMeasurement): Pair<Double, Double>? {
            if (!clock.hasFullBiasNanos()) return null
            val c = Constellation.fromId(m.constellationType)
            val state = m.state
            val rxGps = clock.timeNanos - clock.fullBiasNanos
            val frac = m.timeOffsetNanos - (if (clock.hasBiasNanos()) clock.biasNanos else 0.0)
            val towOk = state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) != 0
            val period: Long
            val rx: Double = when (c) {
                Constellation.GPS, Constellation.GALILEO, Constellation.QZSS -> {
                    if (!towOk) return null
                    period = WEEK_NS
                    (rxGps % WEEK_NS).toDouble() + frac
                }
                Constellation.BEIDOU -> {
                    if (!towOk) return null
                    period = WEEK_NS
                    ((rxGps - 14_000_000_000L) % WEEK_NS).toDouble() + frac
                }
                Constellation.GLONASS -> {
                    if (state and (STATE_GLO_TOD_DECODED or STATE_GLO_TOD_KNOWN) == 0) return null
                    val leap = if (clock.hasLeapSecond()) clock.leapSecond.toLong() else 18L
                    period = DAY_NS
                    ((rxGps + 10_800_000_000_000L - leap * 1_000_000_000L) % DAY_NS).toDouble() + frac
                }
                else -> return null
            }
            var dt = rx - m.receivedSvTimeNanos
            if (dt < -period / 2) dt += period
            if (dt > period / 2) dt -= period
            val km = dt * 1e-9 * Geo.C_KM_S
            if (km < 15_000 || km > 50_000) return null
            val biasU = if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else 0.0
            val tU = m.receivedSvTimeUncertaintyNanos.toDouble()
            val sigmaM = sqrt(tU * tU + biasU * biasU) * 1e-9 * Geo.C_KM_S * 1000.0
            return km to sigmaM
        }
    }
}
