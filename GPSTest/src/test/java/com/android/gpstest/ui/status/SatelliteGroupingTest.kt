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
package com.android.gpstest.ui.status

import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.model.SbasType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteGroupingTest {

    private val l1 = 1575.42e6
    private val l5 = 1176.45e6
    private val l2 = 1227.6e6

    private fun signal(
        gnssType: GnssType,
        svid: Int,
        cn0: Float = 30f,
        frequencyHz: Double? = l1,
        used: Boolean = false,
        almanac: Boolean = true,
        ephemeris: Boolean = true,
        elevation: Float = 45f,
        azimuth: Float = 90f,
        sbasType: SbasType = SbasType.UNKNOWN,
    ): SatelliteStatus {
        val status = SatelliteStatus(
            svid, gnssType, cn0, almanac, ephemeris, used, elevation, azimuth
        )
        if (frequencyHz != null) {
            status.hasCarrierFrequency = true
            status.carrierFrequencyHz = frequencyHz
        }
        status.sbasType = sbasType
        return status
    }

    private fun List<SatelliteGroup>.ids(index: Int) = this[index].satellites.map { it.svid }

    @Test
    fun emptyListGivesNoGroups() {
        assertTrue(groupSatellites(emptyList(), true).isEmpty())
    }

    @Test
    fun signalsOfOneSatelliteAreMergedAndOrderedByFrequency() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.NAVSTAR, 14, frequencyHz = l5),
                signal(GnssType.NAVSTAR, 14, frequencyHz = l1),
                signal(GnssType.NAVSTAR, 14, frequencyHz = l2),
            ),
            true
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].satellites.size)
        val frequencies = groups[0].satellites[0].signals.map { it.carrierFrequencyHz }
        assertEquals(listOf(l1, l2, l5), frequencies)
    }

    @Test
    fun sameSvidInDifferentConstellationsStaysSeparate() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.GALILEO, 8),
                signal(GnssType.BEIDOU, 8),
                signal(GnssType.NAVSTAR, 8),
            ),
            true
        )
        assertEquals(
            listOf(GnssType.NAVSTAR, GnssType.GALILEO, GnssType.BEIDOU),
            groups.map { it.gnssType }
        )
        groups.forEach { assertEquals(1, it.satellites.size) }
    }

    @Test
    fun groupsComeInFixedConstellationOrder() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.IRNSS, 1),
                signal(GnssType.QZSS, 194),
                signal(GnssType.BEIDOU, 1),
                signal(GnssType.GLONASS, 1),
                signal(GnssType.GALILEO, 1),
                signal(GnssType.NAVSTAR, 1),
                signal(GnssType.UNKNOWN, 1),
            ),
            true
        )
        assertEquals(
            listOf(
                GnssType.NAVSTAR,
                GnssType.GALILEO,
                GnssType.GLONASS,
                GnssType.BEIDOU,
                GnssType.QZSS,
                GnssType.IRNSS,
                GnssType.UNKNOWN,
            ),
            groups.map { it.gnssType }
        )
    }

    @Test
    fun sbasIsGroupedBySystem() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.SBAS, 136, sbasType = SbasType.EGNOS),
                signal(GnssType.SBAS, 131, sbasType = SbasType.WAAS),
                signal(GnssType.SBAS, 123, sbasType = SbasType.EGNOS),
            ),
            false
        )
        assertEquals(listOf(SbasType.WAAS, SbasType.EGNOS), groups.map { it.sbasType })
        assertEquals(listOf(123, 136), groups.ids(1))
    }

    @Test
    fun flagsAndUsedCountAreCombinedOverSignals() {
        val groups = groupSatellites(
            listOf(
                // E5a used in the fix, E1 not: the satellite counts as used
                signal(GnssType.GALILEO, 6, frequencyHz = l1, used = false, almanac = false),
                signal(GnssType.GALILEO, 6, frequencyHz = l5, used = true, ephemeris = false),
                signal(GnssType.GALILEO, 21, almanac = true, ephemeris = false, used = false),
            ),
            true
        )
        val satellites = groups[0].satellites
        assertEquals("AEU", satellites[0].aeu)
        assertTrue(satellites[0].usedInFix)
        assertEquals("A", satellites[1].aeu)
        assertEquals(1, groups[0].numUsed)
        assertEquals(2, groups[0].satellites.size)
    }

    @Test
    fun signalWithoutCarrierFrequencyIsLast() {
        val groups = groupSatellites(
            listOf(
                signal(GnssType.NAVSTAR, 3, frequencyHz = null),
                signal(GnssType.NAVSTAR, 3, frequencyHz = l5),
            ),
            true
        )
        val signals = groups[0].satellites[0].signals
        assertTrue(signals[0].hasCarrierFrequency)
        assertTrue(!signals[1].hasCarrierFrequency)
    }

    private val mixed = listOf(
        signal(GnssType.GALILEO, 21, cn0 = 25.8f, elevation = 12f, used = false),
        signal(GnssType.GALILEO, 6, cn0 = 41.9f, elevation = 62f, used = true),
        signal(GnssType.GALILEO, 6, cn0 = 28.6f, frequencyHz = l5, elevation = 62f, used = true),
        signal(GnssType.GALILEO, 34, cn0 = 34.7f, elevation = SatelliteStatus.NO_DATA, used = false),
        signal(GnssType.GALILEO, 23, cn0 = 42.0f, elevation = 48f, used = true),
        signal(GnssType.GALILEO, 2, cn0 = SatelliteStatus.NO_DATA, elevation = 5f, used = false),
    )

    @Test
    fun sortById() {
        assertEquals(listOf(2, 6, 21, 23, 34), groupSatellites(mixed, true, GroupedSortOrder.ID).ids(0))
    }

    @Test
    fun sortBySignalStrengthUsesStrongestSignalAndPutsNoDataLast() {
        assertEquals(
            listOf(23, 6, 34, 21, 2),
            groupSatellites(mixed, true, GroupedSortOrder.SIGNAL_STRENGTH).ids(0)
        )
    }

    @Test
    fun sortByElevationPutsUnknownElevationLast() {
        assertEquals(
            listOf(6, 23, 21, 2, 34),
            groupSatellites(mixed, true, GroupedSortOrder.ELEVATION).ids(0)
        )
    }

    @Test
    fun sortByUsedInFixThenId() {
        assertEquals(
            listOf(6, 23, 2, 21, 34),
            groupSatellites(mixed, true, GroupedSortOrder.USED_IN_FIX).ids(0)
        )
    }

    @Test
    fun averageCn0IgnoresSignalsWithoutCn0() {
        val group = groupSatellites(mixed, true)[0]
        // 25.8, 41.9, 28.6, 34.7 and 42.0; the satellite without C/N0 doesn't count
        assertEquals(34.6f, group.averageCn0!!, 0.001f)
    }

    @Test
    fun averageCn0IsNullWithoutAnyCn0() {
        val group = groupSatellites(
            listOf(signal(GnssType.NAVSTAR, 1, cn0 = SatelliteStatus.NO_DATA)),
            true
        )[0]
        assertNull(group.averageCn0)
    }

    @Test
    fun satelliteKeyIncludesConstellation() {
        val groups = groupSatellites(
            listOf(signal(GnssType.GALILEO, 8), signal(GnssType.BEIDOU, 8)),
            true
        )
        assertEquals("GALILEO_8", groups[0].satellites[0].key)
        assertEquals("BEIDOU_8", groups[1].satellites[0].key)
    }

    @Test
    fun sortOrderFromIndexFallsBackToId() {
        assertEquals(GroupedSortOrder.SIGNAL_STRENGTH, GroupedSortOrder.fromIndex(1))
        assertEquals(GroupedSortOrder.USED_IN_FIX, GroupedSortOrder.fromIndex(3))
        assertEquals(GroupedSortOrder.ID, GroupedSortOrder.fromIndex(-1))
        assertEquals(GroupedSortOrder.ID, GroupedSortOrder.fromIndex(99))
    }
}
