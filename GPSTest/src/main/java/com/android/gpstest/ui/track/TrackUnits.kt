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

import com.android.gpstest.Application.Companion.app
import com.android.gpstest.Application.Companion.prefs
import com.android.gpstest.library.util.LibUIUtils
import com.android.gpstest.library.util.PreferenceUtil
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Formats the values on the Track screen using the app's unit settings: elevation and accuracy
 * (meters or feet), track distance (kilometers, miles or nautical miles) and speed (m/s, km/h,
 * mph or knots).
 *
 * @param metricHeight true for meters, false for feet
 * @param distanceUnit one of the PreferenceUtil.TRACK_DISTANCE_ values; TRACK_DISTANCE_AUTO
 * uses kilometers with meters and miles with feet
 * @param speedPref one of the PreferenceUtil speed unit values
 */
class TrackUnits(
    private val metricHeight: Boolean,
    distanceUnit: String,
    private val speedPref: String
) {
    private val distanceUnit = if (distanceUnit == PreferenceUtil.TRACK_DISTANCE_AUTO) {
        if (metricHeight) {
            PreferenceUtil.TRACK_DISTANCE_KILOMETERS
        } else {
            PreferenceUtil.TRACK_DISTANCE_MILES
        }
    } else {
        distanceUnit
    }

    val heightUnit = if (metricHeight) "m" else "ft"

    /** Unit of the distance axis in the charts, and of longer distances */
    val distanceAxisUnit = when (this.distanceUnit) {
        PreferenceUtil.TRACK_DISTANCE_MILES -> "mi"
        PreferenceUtil.TRACK_DISTANCE_NAUTICAL_MILES -> "nmi"
        else -> "km"
    }

    // Same unit labels as the speed on the Status screen
    val speedUnit = when (speedPref) {
        PreferenceUtil.KILOMETERS_PER_HOUR -> "km/h"
        PreferenceUtil.MILES_PER_HOUR -> "mph"
        PreferenceUtil.KNOTS -> "kt"
        else -> "m/s"
    }

    private val metersPerDistanceUnit = when (this.distanceUnit) {
        PreferenceUtil.TRACK_DISTANCE_MILES -> METERS_PER_MILE
        PreferenceUtil.TRACK_DISTANCE_NAUTICAL_MILES -> METERS_PER_NAUTICAL_MILE
        else -> 1000f
    }

    fun heightValue(m: Float): Float = if (metricHeight) m else m * FEET_PER_METER

    fun height(m: Double): String =
        String.format(Locale.getDefault(), "%.0f %s", heightValue(m.toFloat()), heightUnit)

    fun distanceAxis(m: Float): Float = m / metersPerDistanceUnit

    /** A value on the distance axis (see [distanceAxis]) with its unit */
    fun distanceAxisValue(value: Float): String =
        String.format(Locale.getDefault(), "%.2f %s", value, distanceAxisUnit)

    /** A height already converted with [heightValue], with its unit */
    fun heightValueText(value: Float): String =
        String.format(Locale.getDefault(), "%.0f %s", value, heightUnit)

    /** A speed already converted with [speedValue], with its unit */
    fun speedValueText(value: Float): String =
        String.format(Locale.getDefault(), "%.1f %s", value, speedUnit)

    /**
     * Time per distance unit at [mps], e.g. "5:32 /km", or null below walking speed or above
     * 100 minutes per unit, where a pace means nothing
     */
    fun pace(mps: Double): String? {
        if (mps < MIN_PACE_SPEED) return null
        val seconds = (metersPerDistanceUnit / mps).roundToLong()
        if (seconds >= 100 * 60) return null
        return String.format(
            Locale.getDefault(), "%d:%02d /%s", seconds / 60, seconds % 60, distanceAxisUnit
        )
    }


    /** An accuracy in the elevation unit, with one decimal */
    fun accuracy(m: Double): String =
        String.format(Locale.getDefault(), "%.1f %s", heightValue(m.toFloat()), heightUnit)

    /**
     * Short distances (below 1 km, or 0.1 mi or nmi) are shown in the elevation unit, so they
     * don't read as "0.00"
     */
    fun distance(m: Double): String {
        val value = m / metersPerDistanceUnit
        val short = if (distanceUnit == PreferenceUtil.TRACK_DISTANCE_KILOMETERS) {
            value < 1
        } else {
            value < 0.1
        }
        return if (short) {
            height(m)
        } else {
            String.format(Locale.getDefault(), "%.2f %s", value, distanceAxisUnit)
        }
    }

    fun speedValue(mps: Float): Float = when (speedPref) {
        PreferenceUtil.KILOMETERS_PER_HOUR -> LibUIUtils.toKilometersPerHour(mps)
        PreferenceUtil.MILES_PER_HOUR -> LibUIUtils.toMilesPerHour(mps)
        PreferenceUtil.KNOTS -> LibUIUtils.toKnots(mps)
        else -> mps
    }

    fun speed(mps: Double): String =
        String.format(Locale.getDefault(), "%.1f %s", speedValue(mps.toFloat()), speedUnit)

    companion object {
        private const val FEET_PER_METER = 3.28084f
        /** Slower than this (1.8 km/h) is standing rather than walking */
        private const val MIN_PACE_SPEED = 0.5
        private val COMPASS_POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** A direction in degrees with its compass point, e.g. "123° SE" */
        fun direction(degrees: Float): String {
            val d = ((degrees.roundToInt() % 360) + 360) % 360
            val point = COMPASS_POINTS[((d + 22.5f) / 45f).toInt() % 8]
            return String.format(Locale.getDefault(), "%d° %s", d, point)
        }

        private const val METERS_PER_MILE = 1609.344f
        private const val METERS_PER_NAUTICAL_MILE = 1852f

        fun fromPrefs() = TrackUnits(
            metricHeight = PreferenceUtil.distanceUnits(app, prefs) == PreferenceUtil.METERS,
            distanceUnit = PreferenceUtil.trackDistanceUnits(app, prefs),
            speedPref = PreferenceUtil.speedUnits(app, prefs)
        )
    }
}
