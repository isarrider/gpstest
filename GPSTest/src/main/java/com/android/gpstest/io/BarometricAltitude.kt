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

import kotlin.math.max
import kotlin.math.pow

/**
 * Elevation from the barometer, calibrated against GNSS altitude.
 *
 * The barometer measures changes in height precisely (to about a meter), but its absolute value
 * depends on the weather. GNSS altitude is right on average, but noisy (several meters from fix
 * to fix). So the elevation is the pressure altitude plus an offset that follows the difference
 * to GNSS slowly: the average of the first [settleFixes] differences, then a moving average with
 * weight 1 / [settleFixes]. That filters the GNSS noise and still follows weather changes, which
 * take hours.
 *
 * Changes in height (ascent, descent, gradient) should use [pressureAltitude] differences, which
 * don't move with the calibration.
 */
class BarometricAltitude(private val settleFixes: Int = DEFAULT_SETTLE_FIXES) {

    private var offset: Double? = null
    private var calibrations = 0

    /**
     * @param pressureHpa air pressure for this fix, or null if there was no reading
     * @param gnssElevation GNSS elevation above sea level, or null if not known
     * @param gnssVerticalAccuracy its 68 % accuracy in meters, or null if not known
     * @return the calibrated elevation, or null without pressure or before the first GNSS
     * elevation
     */
    fun update(pressureHpa: Float?, gnssElevation: Double?, gnssVerticalAccuracy: Float?): Double? {
        val altitude = pressureHpa?.let { pressureAltitude(it) } ?: return null
        if (gnssElevation != null &&
            (gnssVerticalAccuracy == null || gnssVerticalAccuracy <= MAX_VERTICAL_ACCURACY)
        ) {
            val difference = gnssElevation - altitude
            calibrations++
            val weight = 1.0 / minOf(calibrations, max(1, settleFixes))
            offset = offset?.let { it + weight * (difference - it) } ?: difference
        }
        return offset?.let { altitude + it }
    }

    companion object {
        /** About a minute at one fix per second */
        const val DEFAULT_SETTLE_FIXES = 60

        /** GNSS elevations less accurate than this don't calibrate the barometer */
        const val MAX_VERTICAL_ACCURACY = 20f

        private const val STANDARD_PRESSURE_HPA = 1013.25

        /**
         * Altitude in meters in the standard atmosphere for [pressureHpa], as
         * android.hardware.SensorManager.getAltitude() computes it
         */
        fun pressureAltitude(pressureHpa: Float): Double =
            44330.0 * (1.0 - (pressureHpa / STANDARD_PRESSURE_HPA).pow(1.0 / 5.255))
    }
}
