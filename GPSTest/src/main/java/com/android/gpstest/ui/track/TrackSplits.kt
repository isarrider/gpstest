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

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Splits of a track: the time for each kilometer (or mile, or nautical mile), from the chart
 * samples (time and distance since the start). The time at which the track passes each full
 * unit is interpolated between the two samples around it.
 */
object TrackSplits {

    /**
     * @param number 1 for the first unit
     * @param fraction 1 for a full unit, less for the last, unfinished one
     * @param seconds time for this split, stops included
     */
    data class Split(val number: Int, val fraction: Double, val seconds: Double) {
        /** Time for a full unit at the pace of this split */
        val secondsPerUnit: Double get() = seconds / fraction
    }

    /** A last split shorter than this share of a unit is left out */
    const val MIN_LAST_FRACTION = 0.1

    /**
     * @param seconds time since the start of each sample, ascending
     * @param meters distance since the start of each sample, not decreasing
     * @param metersPerUnit length of a split (1000 for km)
     */
    fun compute(seconds: List<Double>, meters: List<Double>, metersPerUnit: Double): List<Split> {
        require(seconds.size == meters.size)
        if (seconds.size < 2 || metersPerUnit <= 0) return emptyList()
        val splits = ArrayList<Split>()
        var splitStart = seconds[0]
        var nextBoundary = metersPerUnit
        for (i in 1 until seconds.size) {
            val d0 = meters[i - 1]
            val d1 = meters[i]
            // One step can pass several units, e.g. after a gap without a fix
            while (d1 >= nextBoundary && d1 > d0) {
                val t = seconds[i - 1] + (seconds[i] - seconds[i - 1]) * (nextBoundary - d0) / (d1 - d0)
                splits.add(Split(splits.size + 1, 1.0, t - splitStart))
                splitStart = t
                nextBoundary += metersPerUnit
            }
        }
        val fraction = (meters.last() - (nextBoundary - metersPerUnit)) / metersPerUnit
        val rest = seconds.last() - splitStart
        if (fraction >= MIN_LAST_FRACTION && rest > 0) {
            splits.add(Split(splits.size + 1, fraction, rest))
        }
        return splits
    }

    /** A duration as m:ss, or h:mm:ss from an hour on */
    fun format(seconds: Double): String {
        val s = seconds.roundToLong()
        return if (s >= 3600) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        }
    }
}
