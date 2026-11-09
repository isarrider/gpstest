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

/**
 * The gradient (slope in percent) over the last [windowMeters] of distance travelled, and the
 * steepest climb and descent so far. Measuring over a stretch of distance rather than from point
 * to point keeps the elevation noise from turning into wild slopes: with the barometer 50 m is
 * enough, GNSS elevation needs a longer stretch.
 */
class GradientWindow(private val windowMeters: Double) {

    private val points = ArrayDeque<Pair<Double, Double>>()

    /** Gradient in percent over the last window, or null until a full window is travelled */
    var current: Double? = null
        private set

    /** Steepest climb so far in percent, or null */
    var steepestClimb: Double? = null
        private set

    /** Steepest descent so far in percent (a negative number), or null */
    var steepestDescent: Double? = null
        private set

    /**
     * @param distance distance travelled so far in meters; only call this when it increased
     * @param altitude altitude at that point in meters; only its changes matter
     */
    fun add(distance: Double, altitude: Double) {
        points.addLast(distance to altitude)
        // Keep exactly one point at or before the start of the window
        while (points.size >= 2 && points[1].first <= distance - windowMeters) points.removeFirst()
        val (startDistance, startAltitude) = points.first()
        if (distance - startDistance < windowMeters) return
        val gradient = (altitude - startAltitude) / (distance - startDistance) * 100
        current = gradient
        steepestClimb = maxOf(steepestClimb ?: gradient, gradient).takeIf { it > 0 }
            ?: steepestClimb
        steepestDescent = minOf(steepestDescent ?: gradient, gradient).takeIf { it < 0 }
            ?: steepestDescent
    }

    /** Starts a new window (new segment or other elevation source); the extremes are kept */
    fun restart() {
        points.clear()
        current = null
    }

    /** Standing still: the current gradient isn't meaningful */
    fun stopped() {
        current = null
    }
}
