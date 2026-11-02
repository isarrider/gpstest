/*
 * Copyright (C) 2026 Sean J. Barbeau
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
package com.android.gpstest.ui.signals

import com.android.gpstest.library.model.Cn0History
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.model.SbasType

/** Time windows offered for the combined history, in minutes */
val HISTORY_WINDOWS_MINUTES = listOf(5, 15, 60)

/**
 * Averages the samples of a signal per pixel column of a chart. The right-most column ends at
 * [newestMillis], the left-most starts [windowMillis] earlier. Columns without a sample are NaN.
 */
fun columnAverages(
    series: Cn0History.Series,
    newestMillis: Long,
    windowMillis: Long,
    columns: Int,
): FloatArray {
    val sums = FloatArray(columns)
    val counts = IntArray(columns)
    val columnMillis = windowMillis.toFloat() / columns
    for (i in 0 until series.size) {
        val age = newestMillis - series.timesMillis[i]
        if (age < 0 || age > windowMillis) continue
        val column = (columns - 1 - (age / columnMillis).toInt()).coerceIn(0, columns - 1)
        sums[column] += series.cn0DbHz[i]
        counts[column]++
    }
    return FloatArray(columns) { if (counts[it] == 0) Float.NaN else sums[it] / counts[it] }
}

/** Identifies the line group a signal belongs to: a constellation, or an SBAS system */
data class HistoryGroup(val gnssType: GnssType, val sbasType: SbasType = SbasType.UNKNOWN)

fun Cn0History.Series.historyGroup(): HistoryGroup =
    if (gnssType == GnssType.SBAS) HistoryGroup(gnssType, sbasType) else HistoryGroup(gnssType)

/**
 * Average line of a constellation, the same way the group headers compute their average: per
 * column, the strongest signal of each satellite, averaged over the satellites with a sample in
 * that column. Weak secondary signals (e.g. L5 near the noise floor) don't pull the line down.
 *
 * [signalColumns] are the [columnAverages] of each signal, keyed by the satellite ID.
 */
fun groupAverage(signalColumns: List<Pair<Int, FloatArray>>, columns: Int): FloatArray {
    val strongestPerSatellite = HashMap<Int, FloatArray>()
    signalColumns.forEach { (svid, values) ->
        val strongest = strongestPerSatellite.getOrPut(svid) { FloatArray(columns) { Float.NaN } }
        for (c in 0 until columns) {
            val v = values[c]
            if (!v.isNaN() && (strongest[c].isNaN() || v > strongest[c])) strongest[c] = v
        }
    }
    return FloatArray(columns) { c ->
        var sum = 0f
        var count = 0
        strongestPerSatellite.values.forEach { values ->
            if (!values[c].isNaN()) {
                sum += values[c]
                count++
            }
        }
        if (count == 0) Float.NaN else sum / count
    }
}

/**
 * Splits a column series into runs of connected points. Points further apart than [maxGap]
 * columns (e.g. while a satellite was blocked) start a new run.
 */
fun connectedRuns(values: FloatArray, maxGap: Int): List<List<Pair<Int, Float>>> {
    val runs = mutableListOf<MutableList<Pair<Int, Float>>>()
    var lastColumn = -1
    values.forEachIndexed { column, v ->
        if (v.isNaN()) return@forEachIndexed
        if (lastColumn < 0 || column - lastColumn > maxGap) runs.add(mutableListOf())
        runs.last().add(column to v)
        lastColumn = column
    }
    return runs
}
