package com.shekath.gnssglobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var orbits: OrbitRepository
    private lateinit var engine: GnssEngine

    private lateinit var globe: GlobeView
    private lateinit var sky: SkyPlotView
    private lateinit var table: SatTableView
    private lateinit var accuracy: AccuracyView
    private lateinit var statusChip: TextView
    private lateinit var coords: TextView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var permissionPanel: View

    private var selectedKey: Int? = null
    private var running = false
    private var askedPermission = false

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orbits = OrbitRepository(this)
        engine = GnssEngine(this, orbits)
        engine.listener = ::render
        setContentView(buildUi())
        orbits.load { engine.requestCompute() }
        selectTab(savedInstanceState?.getInt("tab") ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", pages.indexOfFirst { it.visibility == View.VISIBLE }.coerceAtLeast(0))
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) startTracking() else {
            permissionPanel.visibility = View.VISIBLE
            if (!askedPermission) { askedPermission = true; askPermission() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (running) { engine.stop(); running = false }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ && hasPermission()) startTracking()
    }

    private fun askPermission() = requestPermissions(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ
    )

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startTracking() {
        permissionPanel.visibility = View.GONE
        if (!running) { engine.start(); running = true }
    }

    // ------------------------------------------------------------------ render

    private fun render(s: Snapshot) {
        globe.snapshot = s
        sky.snapshot = s
        table.snapshot = s
        accuracy.snapshot = s

        val loc = s.location
        val (chipText, chipColor) = when {
            !engine.isGpsEnabled -> "GPS OFF" to 0xFFF87171.toInt()
            loc != null && loc.provider == "gps" && s.usedCount >= 4 ->
                (if (loc.hasAltitude()) "3D FIX" else "2D FIX") to 0xFF4ADE80.toInt()
            loc != null -> "APPROX" to 0xFFFBBF24.toInt()
            else -> "SEARCHING" to 0xFFFBBF24.toInt()
        }
        statusChip.text = chipText
        (statusChip.background as GradientDrawable).setStroke((1.5f * dp).toInt(), chipColor)
        statusChip.setTextColor(chipColor)

        val parts = ArrayList<String>()
        if (loc != null) {
            parts += Format.latLon(loc.latitude, loc.longitude)
            if (loc.hasAccuracy()) parts += "±" + Format.meters(loc.accuracy.toDouble())
            if (loc.hasAltitude()) parts += String.format(Locale.US, "alt %.0f m", loc.altitude)
        } else parts += "Waiting for position…"
        parts += "${s.usedCount}/${s.sats.size} sats"
        s.ttffMs?.let { parts += String.format(Locale.US, "TTFF %.1f s", it / 1000.0) }
        when (s.rawMeasurements) {
            true -> parts += "raw ✓"
            false -> parts += "raw ✗"
            null -> {}
        }
        coords.text = parts.joinToString("  ·  ")
    }

    private fun select(key: Int?) {
        selectedKey = key
        globe.selectedKey = key
        sky.selectedKey = key
        table.selectedKey = key
    }

    // ---------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SPACE)
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(10), px(16), px(8))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "GNSS GLOBE"
            setTextColor(Color.WHITE)
            textSize = 18f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusChip = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding(px(10), px(3), px(10), px(3))
            background = GradientDrawable().apply { cornerRadius = 50f * dp; setColor(0x22000000) }
            setOnClickListener {
                if (!engine.isGpsEnabled) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        titleRow.addView(statusChip)
        header.addView(titleRow)
        coords = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            setPadding(0, px(4), 0, 0)
        }
        header.addView(coords)
        root.addView(header)

        // pages
        val content = FrameLayout(this)
        globe = GlobeView(this).apply { onSelect = { select(it) } }
        val globePage = FrameLayout(this).apply {
            addView(globe, FrameLayout.LayoutParams(-1, -1))
            addView(buildGlobeControls(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = px(10)
            })
        }
        sky = SkyPlotView(this).apply { onSelect = { select(it) } }
        table = SatTableView(this).apply {
            onSelect = {
                select(it)
                selectTab(0)
                globe.focusSelected()
            }
        }
        accuracy = AccuracyView(this).apply {
            onReset = { engine.fixStats.clear(); engine.requestCompute() }
        }
        pages = listOf(globePage, scroll(sky), scroll(table), scroll(accuracy))
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        permissionPanel = buildPermissionPanel()
        content.addView(permissionPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // bottom navigation
        val nav = LinearLayout(this).apply {
            setBackgroundColor(0xFF0B1220.toInt())
            setPadding(0, px(4), 0, px(4))
        }
        val names = listOf("◍\nGLOBE", "◎\nSKY", "≡\nSATELLITES", "⌖\nACCURACY")
        tabs = names.mapIndexed { i, n ->
            TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                textSize = 11f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setPadding(0, px(6), 0, px(6))
                setOnClickListener { selectTab(i) }
                nav.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        root.addView(nav)
        return root
    }

    private fun buildGlobeControls(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun chip(label: String, toggled: Boolean?, onClick: (TextView) -> Unit) {
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 12f * dp }
                setPadding(px(6), px(8), px(6), px(8))
                setOnClickListener { onClick(this) }
            }
            styleChip(tv, toggled)
            col.addView(tv, LinearLayout.LayoutParams(px(58), -2).apply { bottomMargin = px(8) })
        }
        chip("◎ ME", null) { globe.centerOnUser() }
        chip("＋", null) { globe.zoomBy(1.35) }
        chip("－", null) { globe.zoomBy(1 / 1.35) }
        chip("ORBITS", true) { globe.showOrbits = !globe.showOrbits; styleChip(it, globe.showOrbits) }
        chip("SIGNALS", true) { globe.showRays = !globe.showRays; styleChip(it, globe.showRays) }
        chip("ALL GNSS", true) { globe.showCatalog = !globe.showCatalog; styleChip(it, globe.showCatalog) }
        chip("SPIN", true) { globe.autoRotate = !globe.autoRotate; styleChip(it, globe.autoRotate) }
        return col
    }

    private fun styleChip(tv: TextView, on: Boolean?) {
        val bg = tv.background as GradientDrawable
        when (on) {
            null -> { bg.setColor(0xCC15233D.toInt()); bg.setStroke(px(1), 0x5538BDF8); tv.alpha = 1f }
            true -> { bg.setColor(0xCC0E3B5C.toInt()); bg.setStroke(px(1), 0xFF38BDF8.toInt()); tv.alpha = 1f }
            false -> { bg.setColor(0x9915233D.toInt()); bg.setStroke(px(1), 0x33FFFFFF); tv.alpha = 0.6f }
        }
    }

    private fun buildPermissionPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(SPACE)
            setPadding(px(32), px(32), px(32), px(32))
            visibility = View.GONE
            isClickable = true
        }
        box.addView(TextView(this).apply {
            text = "Location permission needed"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "GNSS Globe reads your phone's satellite receiver to locate the GPS, GLONASS, Galileo and " +
                "BeiDou satellites it uses and to measure their distance. Nothing leaves the device; the app " +
                "only downloads public orbit data."
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, px(12), 0, px(20))
        })
        box.addView(TextView(this).apply {
            text = "GRANT ACCESS"
            setTextColor(SPACE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(px(24), px(12), px(24), px(12))
            background = GradientDrawable().apply { cornerRadius = 50f * dp; setColor(0xFF38BDF8.toInt()) }
            setOnClickListener { askPermission() }
        })
        box.addView(TextView(this).apply {
            text = "If the prompt no longer appears, enable it in App settings."
            setTextColor(0x80FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, px(14), 0, 0)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
        })
        return box
    }

    private fun scroll(v: View) = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(SPACE)
        addView(v, FrameLayout.LayoutParams(-1, -2))
    }

    private fun selectTab(i: Int) {
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, t -> t.setTextColor(if (j == i) 0xFF38BDF8.toInt() else 0x80FFFFFF.toInt()) }
    }

    private fun px(v: Int) = (v * dp).toInt()

    companion object {
        private const val REQ = 42
        private const val SPACE = 0xFF05070D.toInt()
    }
}
