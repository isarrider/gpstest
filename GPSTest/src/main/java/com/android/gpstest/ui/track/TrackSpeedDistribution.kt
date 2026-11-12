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

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * How the moving time of a track is spread over speed ranges, to see its dominant speed. Each
 * interval between two chart samples counts with the average speed of its ends; stops (below
 * [minSpeed]) and gaps without a fix (longer than [MAX_GAP_SECONDS]) aren't counted.
 */
object TrackSpeedDistribution {

    /** @param from lower end of the range, in the speed unit; [seconds] spent in it */
    data class Bin(val index: Int, val from: Double, val to: Double, val seconds: Double)

    /** @param totalSeconds moving time counted */
    data class Distribution(val binWidth: Double, val bins: List<Bin>, val totalSeconds: Double) {
        /** The range with the most time */
        val dominant: Bin? get() = bins.maxByOrNull { it.seconds }?.takeIf { it.seconds > 0 }

        fun share(bin: Bin): Double = if (totalSeconds > 0) bin.seconds / totalSeconds else 0.0
    }

    /** Intervals longer than this are gaps without a fix, not travel at one speed */
    const val MAX_GAP_SECONDS = 300.0

    /** About this many ranges, so the bars read as a curve; the width is rounded to [STEPS] */
    const val TARGET_BINS = 100

    /** Widths × 10ⁿ the range width is rounded up to, fine enough to stay close to [TARGET_BINS] */
    private val STEPS = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)

    /**
     * @param seconds time since the start of each sample, ascending
     * @param speeds speed of each sample in the speed unit, or null if unknown
     * @param minSpeed slower is standing still, in the speed unit
     * @return the distribution, or null if there's no moving time
     */
    fun compute(seconds: List<Double>, speeds: List<Double?>, minSpeed: Double): Distribution? {
        require(seconds.size == speeds.size)
        val intervals = ArrayList<Pair<Double, Double>>()
        for (i in 1 until seconds.size) {
            val dt = seconds[i] - seconds[i - 1]
            if (dt <= 0 || dt > MAX_GAP_SECONDS) continue
            val a = speeds[i - 1]
            val b = speeds[i]
            val v = when {
                a != null && b != null -> (a + b) / 2
                else -> a ?: b ?: continue
            }
            if (v < minSpeed) continue
            intervals.add(v to dt)
        }
        if (intervals.isEmpty()) return null
        val width = niceWidth(intervals.maxOf { it.first } / TARGET_BINS)
        val count = (floor(intervals.maxOf { it.first } / width).toInt() + 1)
        val time = DoubleArray(count)
        intervals.forEach { (v, dt) -> time[floor(v / width).toInt().coerceIn(0, count - 1)] += dt }
        // Leave out empty ranges at the start (e.g. below walking speed when driving)
        val first = time.indexOfFirst { it > 0 }
        val bins = (first until count).map { Bin(it - first, it * width, (it + 1) * width, time[it]) }
        return Distribution(width, bins, time.sum())
    }

    /** The smallest of [STEPS] × 10ⁿ at least [raw] */
    fun niceWidth(raw: Double): Double {
        if (raw <= 0) return 1.0
        val magnitude = 10.0.pow(floor(log10(raw)))
        return STEPS.map { it * magnitude }.first { it >= raw - 1e-12 }
    }
}
