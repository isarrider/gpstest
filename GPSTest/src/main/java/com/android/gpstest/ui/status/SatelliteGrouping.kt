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

/**
 * Order of the satellites within a group of the grouped Status list. The index of each value
 * matches R.array.sort_sats_grouped and is what's saved in the preferences.
 */
enum class GroupedSortOrder {
    /** Satellite ID, ascending */
    ID,

    /** Strongest signal of the satellite first */
    SIGNAL_STRENGTH,

    /** Highest elevation first, satellites without an elevation last */
    ELEVATION,

    /** Satellites used in the fix first */
    USED_IN_FIX;

    companion object {
        fun fromIndex(index: Int): GroupedSortOrder = values().getOrElse(index) { ID }
    }
}

/** A satellite with all of its signals, ordered from the highest to the lowest carrier frequency */
class GroupedSatellite(
    val svid: Int,
    val gnssType: GnssType,
    val signals: List<SatelliteStatus>,
) {
    /** The first signal, used for elevation, azimuth and the flag */
    val first: SatelliteStatus get() = signals.first()
    val usedInFix: Boolean get() = signals.any { it.usedInFix }
    val hasAlmanac: Boolean get() = signals.any { it.hasAlmanac }
    val hasEphemeris: Boolean get() = signals.any { it.hasEphemeris }

    /** Strongest C/N0 of all signals, or NO_DATA if none is known */
    val maxCn0: Float
        get() = signals.map { it.cn0DbHz }
            .filter { it != SatelliteStatus.NO_DATA }
            .maxOrNull() ?: SatelliteStatus.NO_DATA

    /** "A", "E" and "U" for almanac, ephemeris and used in fix, combined over all signals */
    val aeu: String
        get() = (if (hasAlmanac) "A" else "") +
                (if (hasEphemeris) "E" else "") +
                (if (usedInFix) "U" else "")
}

/** A constellation (GNSS) or SBAS system with its satellites */
class SatelliteGroup(
    val key: String,
    val gnssType: GnssType,
    val sbasType: SbasType,
    val satellites: List<GroupedSatellite>,
) {
    val numUsed: Int get() = satellites.count { it.usedInFix }
    val representative: SatelliteStatus get() = satellites.first().first
}

/** Fixed order of the constellation groups */
private val gnssOrder = listOf(
    GnssType.NAVSTAR,
    GnssType.GALILEO,
    GnssType.GLONASS,
    GnssType.BEIDOU,
    GnssType.QZSS,
    GnssType.IRNSS,
    GnssType.SBAS,
    GnssType.UNKNOWN,
)

/**
 * Groups the signals by constellation (or, for SBAS, by system) and by satellite.
 *
 * Groups come in a fixed order (GPS, Galileo, GLONASS, BeiDou, QZSS, IRNSS; SBAS systems in the
 * order of [SbasType]). Satellites within a group are ordered by [sortOrder], with the ID as tie
 * breaker. Signals of a satellite are ordered from the highest to the lowest carrier frequency
 * (e.g. L1 before L5).
 */
fun groupSatellites(
    satStatuses: List<SatelliteStatus>,
    isGnss: Boolean,
    sortOrder: GroupedSortOrder = GroupedSortOrder.ID,
): List<SatelliteGroup> {
    val byGroup = LinkedHashMap<String, MutableList<SatelliteStatus>>()
    satStatuses.forEach { status ->
        val key = if (isGnss) status.gnssType.name else "SBAS_" + status.sbasType.name
        byGroup.getOrPut(key) { mutableListOf() }.add(status)
    }
    val groups = byGroup.map { (key, statuses) ->
        val bySatellite = LinkedHashMap<Pair<GnssType, Int>, MutableList<SatelliteStatus>>()
        statuses.forEach {
            bySatellite.getOrPut(it.gnssType to it.svid) { mutableListOf() }.add(it)
        }
        val satellites = bySatellite.map { (id, signals) ->
            GroupedSatellite(
                svid = id.second,
                gnssType = id.first,
                signals = signals.sortedByDescending {
                    if (it.hasCarrierFrequency) it.carrierFrequencyHz else 0.0
                }
            )
        }
        SatelliteGroup(
            key = key,
            gnssType = statuses.first().gnssType,
            sbasType = statuses.first().sbasType,
            satellites = satellites.sortedWith(comparatorFor(sortOrder))
        )
    }
    return if (isGnss) {
        groups.sortedBy { gnssOrder.indexOf(it.gnssType) }
    } else {
        groups.sortedBy { it.sbasType.ordinal }
    }
}

private fun comparatorFor(sortOrder: GroupedSortOrder): Comparator<GroupedSatellite> {
    val byId = compareBy<GroupedSatellite> { it.svid }
    return when (sortOrder) {
        GroupedSortOrder.ID -> byId
        // NO_DATA is 0, so satellites without a C/N0 end up last
        GroupedSortOrder.SIGNAL_STRENGTH ->
            compareByDescending<GroupedSatellite> { it.maxCn0 }.then(byId)
        GroupedSortOrder.ELEVATION ->
            compareByDescending<GroupedSatellite> {
                if (it.first.elevationDegrees == SatelliteStatus.NO_DATA) {
                    Float.NEGATIVE_INFINITY
                } else {
                    it.first.elevationDegrees
                }
            }.then(byId)
        GroupedSortOrder.USED_IN_FIX ->
            compareByDescending<GroupedSatellite> { it.usedInFix }.then(byId)
    }
}
