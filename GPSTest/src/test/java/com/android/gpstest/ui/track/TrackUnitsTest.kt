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
package com.android.gpstest.ui.track

import com.android.gpstest.library.util.PreferenceUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TrackUnitsTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    private fun units(
        metric: Boolean,
        distance: String = PreferenceUtil.TRACK_DISTANCE_AUTO,
        speed: String = PreferenceUtil.METERS_PER_SECOND
    ) = TrackUnits(metric, distance, speed)

    @Test
    fun automaticDistanceFollowsElevationUnits() {
        val metric = units(true, PreferenceUtil.TRACK_DISTANCE_AUTO)
        assertEquals("km", metric.distanceAxisUnit)
        assertEquals("12.35 km", metric.distance(12_345.0))
        assertEquals("850 m", metric.distance(850.0))

        val imperial = units(false, PreferenceUtil.TRACK_DISTANCE_AUTO)
        assertEquals("mi", imperial.distanceAxisUnit)
        assertEquals("1.00 mi", imperial.distance(1609.344))
        assertEquals("328 ft", imperial.distance(100.0))
    }

    @Test
    fun distanceUnitsIndependentOfElevationUnits() {
        val miWithMeters = units(true, PreferenceUtil.TRACK_DISTANCE_MILES)
        assertEquals("m", miWithMeters.heightUnit)
        assertEquals("3.11 mi", miWithMeters.distance(5000.0))
        assertEquals("100 m", miWithMeters.distance(100.0))

        val kmWithFeet = units(false, PreferenceUtil.TRACK_DISTANCE_KILOMETERS)
        assertEquals("ft", kmWithFeet.heightUnit)
        assertEquals("5.00 km", kmWithFeet.distance(5000.0))
        assertEquals("1640 ft", kmWithFeet.distance(500.0))
    }

    @Test
    fun nauticalMiles() {
        val u = units(true, PreferenceUtil.TRACK_DISTANCE_NAUTICAL_MILES, PreferenceUtil.KNOTS)
        assertEquals("nmi", u.distanceAxisUnit)
        assertEquals("2.00 nmi", u.distance(3704.0))
        assertEquals(1f, u.distanceAxis(1852f), 1e-6f)
        assertEquals("150 m", u.distance(150.0))
    }

    @Test
    fun speedUnits() {
        val mps = 10.0
        assertEquals("10.0 m/s", units(true).speed(mps))
        assertEquals("36.0 km/h", units(true, speed = PreferenceUtil.KILOMETERS_PER_HOUR).speed(mps))
        assertEquals("22.4 mph", units(true, speed = PreferenceUtil.MILES_PER_HOUR).speed(mps))
        assertEquals("19.4 kt", units(true, speed = PreferenceUtil.KNOTS).speed(mps))
    }

    @Test
    fun pace() {
        assertEquals("5:00 /km", units(true).pace(1000.0 / 300))
        assertEquals("10:00 /mi", units(false).pace(1609.344 / 600))
        assertEquals("2:00 /nmi", units(true, PreferenceUtil.TRACK_DISTANCE_NAUTICAL_MILES).pace(1852.0 / 120))
        // Standing still, or too slow for a pace
        assertEquals(null, units(true).pace(0.2))
        assertEquals(null, units(false).pace(0.49))
        assertEquals("53:39 /mi", units(false).pace(0.5))
    }

    @Test
    fun accuracy() {
        assertEquals("4.7 m", units(true).accuracy(4.73))
        assertEquals("15.5 ft", units(false).accuracy(4.73))
    }

    @Test
    fun direction() {
        assertEquals("0° N", TrackUnits.direction(0f))
        assertEquals("0° N", TrackUnits.direction(359.6f))
        assertEquals("23° NE", TrackUnits.direction(22.6f))
        assertEquals("123° SE", TrackUnits.direction(123f))
        assertEquals("270° W", TrackUnits.direction(-90f))
    }

    @Test
    fun elevation() {
        assertEquals("520 m", units(true).height(520.0))
        assertEquals("1706 ft", units(false).height(520.0))
    }
}
