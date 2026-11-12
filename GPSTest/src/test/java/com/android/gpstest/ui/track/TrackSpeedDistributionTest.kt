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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSpeedDistributionTest {

    @Test
    fun niceWidth() {
        assertEquals(1.0, TrackSpeedDistribution.niceWidth(0.85), 1e-9)
        assertEquals(1.5, TrackSpeedDistribution.niceWidth(1.2), 1e-9)
        assertEquals(2.5, TrackSpeedDistribution.niceWidth(2.1), 1e-9)
        assertEquals(5.0, TrackSpeedDistribution.niceWidth(4.2), 1e-9)
        assertEquals(8.0, TrackSpeedDistribution.niceWidth(7.0), 1e-9)
        assertEquals(0.5, TrackSpeedDistribution.niceWidth(0.45), 1e-9)
    }

    @Test
    fun dominantSpeed_byTime() {
        // 10 min at 50 km/h, 30 min at 100 km/h, 5 min at 120 km/h: one sample a minute
        val speeds = List(11) { 50.0 } + List(30) { 100.0 } + List(5) { 120.0 }
        val seconds = speeds.indices.map { it * 60.0 }
        val d = TrackSpeedDistribution.compute(seconds, speeds, 1.8)!!
        // 120 km/h / 100 ranges = 1.2, rounded up to 1.5 km/h steps
        assertEquals(1.5, d.binWidth, 1e-9)
        val dominant = d.dominant!!
        assertEquals(99.0, dominant.from, 1e-9)
        assertEquals(100.5, dominant.to, 1e-9)
        // The interval from 50 to 100 km/h counts at 75 km/h
        assertEquals(29 * 60.0, dominant.seconds, 1e-9)
        assertEquals(45 * 60.0, d.totalSeconds, 1e-9)
        // Empty ranges below the slowest speed are left out
        assertEquals(49.5, d.bins.first().from, 1e-9)
    }

    @Test
    fun stopsAndGaps_notCounted() {
        val seconds = listOf(0.0, 10.0, 20.0, 30.0, 1000.0, 1010.0)
        val speeds = listOf(5.0, 5.0, 0.0, 0.0, 5.0, 5.0)
        val d = TrackSpeedDistribution.compute(seconds, speeds, 1.8)!!
        // 0-10 at 5, 10-20 at 2.5, 20-30 standing, 30-1000 a gap, 1000-1010 at 5
        assertEquals(30.0, d.totalSeconds, 1e-9)
    }

    @Test
    fun noMovement_noDistribution() {
        assertNull(TrackSpeedDistribution.compute(listOf(0.0, 10.0), listOf(0.0, 0.1), 1.8))
        assertNull(TrackSpeedDistribution.compute(listOf(0.0, 10.0), listOf(null, null), 1.8))
    }
}
