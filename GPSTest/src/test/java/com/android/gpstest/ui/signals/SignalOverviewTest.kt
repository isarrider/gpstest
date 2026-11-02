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

import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.ui.status.groupSatellites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalOverviewTest {

    private val l1 = 1575.42e6
    private val l2 = 1227.6e6
    private val l5 = 1176.45e6
    private val e5b = 1207.14e6
    private val glonassL1Ch1 = 1602.5625e6
    private val glonassL1Ch5 = 1604.8125e6

    /**
     * CarrierFreqUtils needs the Android SDK level, which isn't available in unit tests, so the
     * tests label signals by frequency themselves
     */
    private val labeler: SignalLabeler = { status ->
        if (!status.hasCarrierFrequency) {
            NO_FREQUENCY_LABEL
        } else {
            val mhz = status.carrierFrequencyHz / 1e6
            when {
                mhz > 1590 -> "G1"
                mhz > 1570 -> if (status.gnssType == GnssType.GALILEO) "E1" else "L1"
                mhz > 1220 && mhz < 1230 -> "L2"
                mhz > 1200 && mhz < 1210 -> "E5b"
                mhz > 1170 && mhz < 1180 -> if (status.gnssType == GnssType.GALILEO) "E5a" else "L5"
                else -> String.format("%.0f", mhz)
            }
        }
    }

    private fun signal(
        gnssType: GnssType,
        svid: Int,
        cn0: Float = 30f,
        frequencyHz: Double? = l1,
        used: Boolean = false,
    ): SatelliteStatus {
        val status = SatelliteStatus(svid, gnssType, cn0, true, true, used, 45f, 90f)
        if (frequencyHz != null) {
            status.hasCarrierFrequency = true
            status.carrierFrequencyHz = frequencyHz
        }
        return status
    }

    private fun group(vararg statuses: SatelliteStatus) =
        groupSatellites(statuses.toList(), true).single()

    @Test
    fun tableColumnsAreBandsFromHighToLowFrequency() {
        val table = signalTable(
            group(
                signal(GnssType.NAVSTAR, 3, frequencyHz = l5),
                signal(GnssType.NAVSTAR, 3, frequencyHz = l1),
                signal(GnssType.NAVSTAR, 7, frequencyHz = l2),
                signal(GnssType.NAVSTAR, 7, frequencyHz = l1),
            ),
            labeler
        )
        assertEquals(listOf("L1", "L2", "L5"), table.columns.map { it.label })
    }

    @Test
    fun missingBandIsAnEmptyCell() {
        val table = signalTable(
            group(
                signal(GnssType.NAVSTAR, 3, cn0 = 40f, frequencyHz = l1),
                signal(GnssType.NAVSTAR, 3, cn0 = 32f, frequencyHz = l5),
                signal(GnssType.NAVSTAR, 7, cn0 = 25f, frequencyHz = l1),
            ),
            labeler
        )
        val row7 = table.rows.single { it.satellite.svid == 7 }
        assertEquals(25f, row7.cells[0]!!.cn0DbHz)
        assertNull(row7.cells[1])
        val row3 = table.rows.single { it.satellite.svid == 3 }
        assertEquals(listOf(40f, 32f), row3.cells.map { it!!.cn0DbHz })
    }

    @Test
    fun glonassChannelsShareOneColumn() {
        val table = signalTable(
            groupSatellites(
                listOf(
                    signal(GnssType.GLONASS, 1, frequencyHz = glonassL1Ch1),
                    signal(GnssType.GLONASS, 2, frequencyHz = glonassL1Ch5),
                ),
                true
            ).single(),
            labeler
        )
        assertEquals(listOf("G1"), table.columns.map { it.label })
        assertEquals(glonassL1Ch5, table.columns.single().frequencyHz, 0.0)
        assertTrue(table.rows.all { it.cells.single() != null })
    }

    @Test
    fun duplicateBandUsesTheStrongerSignal() {
        val table = signalTable(
            group(
                signal(GnssType.GALILEO, 4, cn0 = 22f, frequencyHz = l1),
                signal(GnssType.GALILEO, 4, cn0 = 37f, frequencyHz = l1),
            ),
            labeler
        )
        assertEquals(37f, table.rows.single().cells.single()!!.cn0DbHz)
    }

    @Test
    fun signalsWithoutFrequencyGetTheirOwnLastColumn() {
        val table = signalTable(
            group(
                signal(GnssType.NAVSTAR, 3, frequencyHz = null),
                signal(GnssType.NAVSTAR, 4, frequencyHz = l1),
            ),
            labeler
        )
        assertEquals(listOf("L1", NO_FREQUENCY_LABEL), table.columns.map { it.label })
    }

    @Test
    fun summaryCountsSatellitesSignalsAndMultiBand() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.NAVSTAR, 3, frequencyHz = l1, used = true),
                signal(GnssType.NAVSTAR, 3, frequencyHz = l5, used = true),
                signal(GnssType.GALILEO, 11, frequencyHz = l1, used = true),
                signal(GnssType.GALILEO, 11, frequencyHz = e5b),
                // Second band without C/N0 doesn't make the satellite multi-band
                signal(GnssType.GALILEO, 12, frequencyHz = l1),
                signal(GnssType.GALILEO, 12, cn0 = SatelliteStatus.NO_DATA, frequencyHz = l5),
            ),
            true
        )
        val summary = signalSummary(groups, labeler)
        assertEquals(SignalSummary(satellites = 3, signals = 6, signalsUsed = 3, multiBandSatellites = 2), summary)
    }

    @Test
    fun averagePerBandOrderedByFrequency() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.NAVSTAR, 3, cn0 = 40f, frequencyHz = l1),
                signal(GnssType.NAVSTAR, 5, cn0 = 30f, frequencyHz = l1),
                signal(GnssType.NAVSTAR, 3, cn0 = 28f, frequencyHz = l5),
                signal(GnssType.GALILEO, 11, cn0 = 33f, frequencyHz = l5),
                signal(GnssType.GALILEO, 11, cn0 = SatelliteStatus.NO_DATA, frequencyHz = e5b),
            ),
            true
        )
        val averages = averageCn0ByBand(groups, labeler)
        assertEquals(listOf("L1", "L5", "E5a"), averages.map { it.first })
        assertEquals(35f, averages[0].second, 0.001f)
        assertEquals(28f, averages[1].second, 0.001f)
        assertEquals(33f, averages[2].second, 0.001f)
    }

    @Test
    fun emptyGroupsGiveEmptySummary() {
        assertEquals(SignalSummary(0, 0, 0, 0), signalSummary(emptyList(), labeler))
        assertTrue(averageCn0ByBand(emptyList(), labeler).isEmpty())
    }

    @Test
    fun seenBandsKeepASlotForABandThatDisappears() {
        val seen = SeenBands()
        // GPS 3 on L1 and L5, GPS 7 on L1 only
        val both = group(
            signal(GnssType.NAVSTAR, 3, frequencyHz = l1),
            signal(GnssType.NAVSTAR, 3, frequencyHz = l5),
            signal(GnssType.NAVSTAR, 7, frequencyHz = l1),
        )
        assertEquals(listOf("L1", "L5"), seen.update(both, labeler).map { it.label })

        // L5 is lost: the L5 slot stays, so the bars don't move
        val l1Only = group(
            signal(GnssType.NAVSTAR, 3, frequencyHz = l1),
            signal(GnssType.NAVSTAR, 7, frequencyHz = l1),
        )
        val columns = seen.update(l1Only, labeler)
        assertEquals(listOf("L1", "L5"), columns.map { it.label })

        val table = signalTable(l1Only, labeler, columns)
        assertEquals(listOf("L1", "L5"), table.columns.map { it.label })
        table.rows.forEach { row ->
            assertEquals(2, row.cells.size)
            assertNull(row.cells[1])
        }
    }

    @Test
    fun seenBandsAddNewBandsInFrequencyOrder() {
        val seen = SeenBands()
        seen.update(group(signal(GnssType.NAVSTAR, 3, frequencyHz = l5)), labeler)
        val columns = seen.update(group(signal(GnssType.NAVSTAR, 3, frequencyHz = l1)), labeler)
        assertEquals(listOf("L1", "L5"), columns.map { it.label })
    }
}
