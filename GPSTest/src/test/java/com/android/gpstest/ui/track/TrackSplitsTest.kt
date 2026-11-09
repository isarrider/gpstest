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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TrackSplitsTest {
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

    @Test
    fun constantSpeed_fullSplitsAndLastPart() {
        // 4 m/s for 600 s: 2.4 km, a sample every 10 s
        val seconds = (0..60).map { it * 10.0 }
        val meters = seconds.map { it * 4 }
        val splits = TrackSplits.compute(seconds, meters, 1000.0)
        assertEquals(3, splits.size)
        assertEquals(250.0, splits[0].seconds, 1e-6)
        assertEquals(250.0, splits[1].seconds, 1e-6)
        assertEquals(0.4, splits[2].fraction, 1e-6)
        assertEquals(100.0, splits[2].seconds, 1e-6)
        // The last part at the same pace
        assertEquals(250.0, splits[2].secondsPerUnit, 1e-6)
    }

    @Test
    fun stopIsCountedInItsSplit() {
        // 1 km in 300 s, stop for 120 s, another 1 km in 300 s
        val seconds = listOf(0.0, 300.0, 420.0, 720.0)
        val meters = listOf(0.0, 1000.0, 1000.0, 2000.0)
        val splits = TrackSplits.compute(seconds, meters, 1000.0)
        assertEquals(2, splits.size)
        assertEquals(300.0, splits[0].seconds, 1e-6)
        assertEquals(420.0, splits[1].seconds, 1e-6)
    }

    @Test
    fun gapPassingSeveralUnits() {
        // No fix between 0.5 km and 3.5 km
        val seconds = listOf(0.0, 100.0, 700.0, 800.0)
        val meters = listOf(0.0, 500.0, 3500.0, 4000.0)
        val splits = TrackSplits.compute(seconds, meters, 1000.0)
        assertEquals(4, splits.size)
        assertTrue(splits.take(4).all { it.fraction == 1.0 })
        assertEquals(800.0, splits.sumOf { it.seconds }, 1e-6)
    }

    @Test
    fun shortLastPartAndShortTrack() {
        val splits = TrackSplits.compute(listOf(0.0, 260.0), listOf(0.0, 1050.0), 1000.0)
        assertEquals(1, splits.size)
        assertTrue(TrackSplits.compute(listOf(0.0, 10.0), listOf(0.0, 50.0), 1000.0).isEmpty())
        assertTrue(TrackSplits.compute(listOf(0.0), listOf(0.0), 1000.0).isEmpty())
    }

    @Test
    fun milesSplits() {
        val seconds = listOf(0.0, 600.0)
        val meters = listOf(0.0, 1609.344)
        val splits = TrackSplits.compute(seconds, meters, 1609.344)
        assertEquals(1, splits.size)
        assertEquals("10:00", TrackSplits.format(splits[0].seconds))
    }

    @Test
    fun format() {
        assertEquals("5:32", TrackSplits.format(332.0))
        assertEquals("0:59", TrackSplits.format(59.4))
        assertEquals("1:02:05", TrackSplits.format(3725.0))
    }
}
