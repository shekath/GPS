package com.shekath.gnssglobe

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Downloads current GNSS orbital elements from CelesTrak (OMM JSON) and caches them, so
 * satellite positions and distances can be computed with SGP4 instead of the coarse
 * orbit-shell model. Works offline from the cache once downloaded.
 */
class OrbitRepository(private val context: Context) {

    enum class State { IDLE, LOADING, READY, OFFLINE }

    @Volatile var orbits: List<OrbitElements> = emptyList(); private set
    @Volatile var state = State.IDLE; private set
    @Volatile var fetchedAtMs = 0L; private set
    @Volatile var lastError: String? = null; private set

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cacheFile get() = File(context.filesDir, "gnss_omm.json")

    /** Loads the cache immediately and refreshes from the network when stale. */
    fun load(onChange: () -> Unit) {
        state = State.LOADING
        executor.execute {
            val cached = cacheFile
            if (cached.exists()) {
                runCatching { parse(cached.readText()) }.getOrNull()?.let {
                    orbits = it
                    fetchedAtMs = cached.lastModified()
                    state = State.READY
                    main.post(onChange)
                }
            }
            val stale = !cached.exists() || System.currentTimeMillis() - cached.lastModified() > REFRESH_MS
            if (stale) {
                try {
                    val text = download()
                    val parsed = parse(text)
                    if (parsed.isNotEmpty()) {
                        cached.writeText(text)
                        orbits = parsed
                        fetchedAtMs = System.currentTimeMillis()
                        state = State.READY
                        lastError = null
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    if (orbits.isEmpty()) state = State.OFFLINE
                }
            }
            if (orbits.isNotEmpty()) state = State.READY
            main.post(onChange)
        }
    }

    private fun download(): String {
        // One request for all GNSS; fall back to the per-constellation groups.
        val groups = listOf(listOf("gnss"), listOf("gps-ops", "glo-ops", "galileo", "beidou", "sbas"))
        var lastEx: Exception? = null
        for (set in groups) {
            try {
                val merged = JSONArray()
                for (g in set) {
                    val arr = JSONArray(httpGet("$BASE?GROUP=$g&FORMAT=json"))
                    for (i in 0 until arr.length()) merged.put(arr.get(i))
                }
                if (merged.length() > 0) return merged.toString()
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx ?: IllegalStateException("no data")
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "GnssGlobe/1.0 (Android)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://celestrak.org/NORAD/elements/gp.php"
        private const val REFRESH_MS = 8 * 3_600_000L

        fun parse(text: String): List<OrbitElements> {
            val arr = JSONArray(text)
            val out = ArrayList<OrbitElements>(arr.length())
            val seen = HashSet<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("NORAD_CAT_ID")
                if (!seen.add(id)) continue
                val epoch = parseEpoch(o.optString("EPOCH")) ?: continue
                val el = runCatching {
                    OrbitElements(
                        name = o.optString("OBJECT_NAME"),
                        noradId = id,
                        epochMs = epoch,
                        meanMotionRevPerDay = o.getDouble("MEAN_MOTION"),
                        ecc = o.getDouble("ECCENTRICITY"),
                        inclDeg = o.getDouble("INCLINATION"),
                        raanDeg = o.getDouble("RA_OF_ASC_NODE"),
                        argpDeg = o.getDouble("ARG_OF_PERICENTER"),
                        meanAnomDeg = o.getDouble("MEAN_ANOMALY"),
                        bstar = o.optDouble("BSTAR", 0.0)
                    )
                }.getOrNull() ?: continue
                // Only medium/high orbits are navigation satellites.
                if (el.semiMajorAxis > 15_000) out.add(el)
            }
            return out
        }

        /** "2026-10-23T11:42:07.123456" (UTC) → epoch millis. */
        fun parseEpoch(s: String): Long? {
            if (s.length < 19) return null
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val base = runCatching { fmt.parse(s.substring(0, 19))?.time }.getOrNull() ?: return null
            val frac = if (s.length > 20 && s[19] == '.') ("0" + s.substring(19)).toDoubleOrNull() ?: 0.0 else 0.0
            return base + (frac * 1000).toLong()
        }
    }
}
