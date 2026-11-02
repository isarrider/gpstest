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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalHistoryTest {

    private fun series(
        times: List<Long>,
        values: List<Float>,
        gnssType: GnssType = GnssType.NAVSTAR,
        svid: Int = 3,
        sbasType: SbasType = SbasType.UNKNOWN,
    ) = Cn0History.Series(
        gnssType, sbasType, svid, true, 1575.42e6, times.toLongArray(), values.toFloatArray()
    )

    @Test
    fun samplesAreAveragedPerColumnAndNewestIsRightmost() {
        // 10 s window in 10 columns: one column per second, newest at 10_000
        val columns = columnAverages(
            series(listOf(9_100, 9_600, 5_500), listOf(30f, 40f, 20f)),
            newestMillis = 10_000,
            windowMillis = 10_000,
            columns = 10
        )
        assertEquals(35f, columns[9], 0.001f)
        assertEquals(20f, columns[5], 0.001f)
        assertTrue(columns[0].isNaN())
    }

    @Test
    fun samplesOutsideTheWindowAreIgnored() {
        val columns = columnAverages(
            series(listOf(-5_000, 10_500), listOf(30f, 40f)),
            newestMillis = 10_000,
            windowMillis = 10_000,
            columns = 10
        )
        assertTrue(columns.all { it.isNaN() })
    }

    @Test
    fun groupAverageUsesStrongestSignalPerSatellite() {
        val nan = Float.NaN
        val average = groupAverage(
            listOf(
                3 to floatArrayOf(40f, 40f, nan),  // G03 L1
                3 to floatArrayOf(30f, 44f, nan),  // G03 L5, stronger in column 1
                7 to floatArrayOf(20f, nan, nan),  // G07 L1
            ),
            columns = 3
        )
        assertEquals(30f, average[0], 0.001f)   // (40 + 20) / 2
        assertEquals(44f, average[1], 0.001f)   // only G03, its L5
        assertTrue(average[2].isNaN())
    }

    @Test
    fun runsBreakAtGaps() {
        val nan = Float.NaN
        val runs = connectedRuns(floatArrayOf(10f, 11f, nan, 12f, nan, nan, nan, 13f), maxGap = 2)
        assertEquals(listOf(listOf(0, 1, 3), listOf(7)), runs.map { run -> run.map { it.first } })
    }

    @Test
    fun sbasSystemsAreSeparateGroups() {
        val egnos = series(listOf(0), listOf(40f), GnssType.SBAS, 123, SbasType.EGNOS)
        val waas = series(listOf(0), listOf(40f), GnssType.SBAS, 131, SbasType.WAAS)
        val gps = series(listOf(0), listOf(40f))
        assertEquals(HistoryGroup(GnssType.SBAS, SbasType.EGNOS), egnos.historyGroup())
        assertTrue(egnos.historyGroup() != waas.historyGroup())
        assertEquals(HistoryGroup(GnssType.NAVSTAR), gps.historyGroup())
    }
}
