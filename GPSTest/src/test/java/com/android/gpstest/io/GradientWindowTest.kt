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
import org.junit.Test

class GradientWindowTest {

    @Test
    fun noGradient_beforeFullWindow() {
        val g = GradientWindow(50.0)
        g.add(0.0, 500.0)
        g.add(30.0, 503.0)
        assertNull(g.current)
        assertNull(g.steepestClimb)
    }

    @Test
    fun constantClimb() {
        val g = GradientWindow(50.0)
        // 10 % up: 1 m every 10 m
        for (i in 0..20) g.add(i * 10.0, 500.0 + i)
        assertEquals(10.0, g.current!!, 1e-9)
        assertEquals(10.0, g.steepestClimb!!, 1e-9)
        assertNull(g.steepestDescent)
    }

    @Test
    fun climbThenDescent_keepsExtremes() {
        val g = GradientWindow(50.0)
        var ele = 500.0
        for (i in 0..10) g.add(i * 10.0, ele + i * 1.0)        // 10 % up to 510 m
        ele = 510.0
        for (i in 1..20) g.add(100.0 + i * 10.0, ele - i * 0.5) // 5 % down
        assertEquals(-5.0, g.current!!, 1e-9)
        assertEquals(10.0, g.steepestClimb!!, 1e-9)
        assertEquals(-5.0, g.steepestDescent!!, 1e-9)
    }

    @Test
    fun windowSlides_overLastStretch() {
        val g = GradientWindow(50.0)
        // Flat for 100 m, then 20 % up for 50 m: only the last 50 m count
        for (i in 0..10) g.add(i * 10.0, 500.0)
        for (i in 1..5) g.add(100.0 + i * 10.0, 500.0 + i * 2.0)
        assertEquals(20.0, g.current!!, 1e-9)
    }

    @Test
    fun restartAndStop() {
        val g = GradientWindow(50.0)
        for (i in 0..10) g.add(i * 10.0, 500.0 + i)
        g.stopped()
        assertNull(g.current)
        g.restart()
        g.add(200.0, 600.0)
        assertNull(g.current)
        // The steepest climb survives a new segment
        assertEquals(10.0, g.steepestClimb!!, 1e-9)
    }
}
