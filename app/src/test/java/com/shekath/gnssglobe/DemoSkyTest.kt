package com.shekath.gnssglobe

import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSkyTest {
    @Test
    fun syntheticConstellationGivesUsableSkyEverywhere() {
        val now = 1792454400000L
        val orbits = DemoSky.syntheticConstellation(now)
        assertTrue(orbits.all { it.constellation != Constellation.UNKNOWN && it.prn != null || it.constellation == Constellation.GLONASS })
        for (lat in listOf(-60.0, -20.0, 0.0, 12.97, 51.48, 70.0)) {
            for (lon in listOf(-120.0, 0.0, 77.6, 150.0)) {
                for (hours in 0 until 24 step 3) {
                    val t = now + hours * 3_600_000L
                    val user = Geo.geodeticToEcef(lat, lon, 0.0)
                    val above = orbits.count { Geo.azEl(lat, lon, user, it.ecef(t)).second > 10 }
                    assertTrue("only $above satellites above 10° at $lat,$lon +${hours}h", above >= 8)
                }
            }
        }
    }
}
