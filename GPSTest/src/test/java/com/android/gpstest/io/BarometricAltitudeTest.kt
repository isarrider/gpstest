/*
 * Copyright (C) 2026 The GPSTest contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.gpstest.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

class BarometricAltitudeTest {

    /** Pressure for an altitude in the standard atmosphere, shifted by the weather */
    private fun pressureAt(altitude: Double, weatherHpa: Double = 0.0): Float =
        (1013.25 * (1 - altitude / 44330.0).pow(5.255) + weatherHpa).toFloat()

    @Test
    fun pressureAltitude_standardAtmosphere() {
        assertEquals(0.0, BarometricAltitude.pressureAltitude(1013.25f), 0.01)
        assertEquals(500.0, BarometricAltitude.pressureAltitude(pressureAt(500.0)), 0.1)
        // About 8 m per hPa near sea level
        val perHpa = BarometricAltitude.pressureAltitude(1012.25f) -
            BarometricAltitude.pressureAltitude(1013.25f)
        assertEquals(8.3, perHpa, 0.2)
    }

    @Test
    fun noPressure_noElevation() {
        val b = BarometricAltitude()
        assertNull(b.update(null, 500.0, 3f))
        // Pressure, but no GNSS elevation yet to calibrate with
        assertNull(b.update(pressureAt(500.0), null, null))
    }

    @Test
    fun weatherOffset_calibratedAway() {
        // The weather lowers the pressure by 5 hPa, so the raw pressure altitude is 40+ m too high
        val b = BarometricAltitude()
        val raw = BarometricAltitude.pressureAltitude(pressureAt(500.0, -5.0))
        assertTrue(raw - 500.0 > 40.0)
        var ele: Double? = null
        repeat(10) { ele = b.update(pressureAt(500.0, -5.0), 500.0, 3f) }
        assertEquals(500.0, ele!!, 0.01)
    }

    @Test
    fun gnssNoise_filtered() {
        // GNSS elevation with 5 m noise, true elevation constant: the calibrated elevation stays
        // much closer to it than single GNSS values
        val random = Random(1)
        val b = BarometricAltitude()
        var worst = 0.0
        repeat(600) { i ->
            val gnss = 500.0 + random.nextDouble(-5.0, 5.0)
            val ele = b.update(pressureAt(500.0), gnss, 5f)!!
            if (i >= 60) worst = maxOf(worst, abs(ele - 500.0))
        }
        assertTrue("worst $worst", worst < 2.0)
    }

    @Test
    fun inaccurateGnss_ignored() {
        val b = BarometricAltitude()
        b.update(pressureAt(500.0), 500.0, 3f)
        // A wildly wrong GNSS elevation with a poor vertical accuracy doesn't move the offset
        val ele = b.update(pressureAt(500.0), 800.0, 50f)!!
        assertEquals(500.0, ele, 0.01)
    }

    @Test
    fun climb_followsPressure() {
        val b = BarometricAltitude()
        repeat(60) { b.update(pressureAt(500.0), 500.0, 3f) }
        // Climbing 30 m without GNSS elevation: the barometer alone follows the climb
        val ele = b.update(pressureAt(530.0), null, null)!!
        assertEquals(530.0, ele, 0.1)
    }
}
