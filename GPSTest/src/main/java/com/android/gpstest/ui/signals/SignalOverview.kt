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

import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.util.CarrierFreqUtils
import com.android.gpstest.library.util.MathUtils
import com.android.gpstest.ui.status.GroupedSatellite
import com.android.gpstest.ui.status.SatelliteGroup
import java.util.Locale

/** Turns a signal into its band label, e.g. "L1", "E5a" or "B2a" */
typealias SignalLabeler = (SatelliteStatus) -> String

/** Label for signals without a carrier frequency (devices before Android 8) */
const val NO_FREQUENCY_LABEL = "–"

/**
 * The band label of a signal, e.g. "L5" or "E5a". Signals without a carrier frequency get
 * [NO_FREQUENCY_LABEL], signals in a band GPSTest doesn't know get the frequency in MHz.
 */
fun defaultSignalLabel(status: SatelliteStatus): String {
    if (!status.hasCarrierFrequency) return NO_FREQUENCY_LABEL
    val label = CarrierFreqUtils.getCarrierFrequencyLabel(status)
    if (label != CarrierFreqUtils.CF_UNKNOWN && label != CarrierFreqUtils.CF_UNSUPPORTED) {
        return label
    }
    return String.format(Locale.US, "%.0f", MathUtils.toMhz(status.carrierFrequencyHz))
}

/** True if the signal has a C/N0 */
val SatelliteStatus.hasCn0: Boolean get() = cn0DbHz != SatelliteStatus.NO_DATA

/** A column of the band table, e.g. "E5a", with the highest carrier frequency seen for it */
data class SignalColumn(val label: String, val frequencyHz: Double)

/** A satellite and its signal in each column of the band table, or null if not received */
class SignalTableRow(val satellite: GroupedSatellite, val cells: List<SatelliteStatus?>)

class SignalTable(val columns: List<SignalColumn>, val rows: List<SignalTableRow>)

/**
 * Builds the band table of a constellation: one column per band received from any satellite of
 * the group, ordered from the highest to the lowest frequency (e.g. L1, L2, L5), and one row per
 * satellite in the order of the group. GLONASS FDMA channels share one column per band. If a
 * satellite reports the same band twice, the stronger signal is used.
 */
fun signalTable(
    group: SatelliteGroup,
    labeler: SignalLabeler = ::defaultSignalLabel
): SignalTable {
    val frequencyByLabel = LinkedHashMap<String, Double>()
    group.satellites.forEach { satellite ->
        satellite.signals.forEach { status ->
            val label = labeler(status)
            val frequency = if (status.hasCarrierFrequency) status.carrierFrequencyHz else 0.0
            frequencyByLabel[label] = maxOf(frequencyByLabel[label] ?: frequency, frequency)
        }
    }
    val columns = frequencyByLabel
        .map { (label, frequency) -> SignalColumn(label, frequency) }
        .sortedByDescending { it.frequencyHz }
    val rows = group.satellites.map { satellite ->
        val byLabel = satellite.signals.groupBy(labeler)
        SignalTableRow(
            satellite,
            columns.map { column -> byLabel[column.label]?.maxByOrNull { it.cn0DbHz } }
        )
    }
    return SignalTable(columns, rows)
}

/** Counts shown at the top of the Signals screen */
data class SignalSummary(
    /** Satellites in view */
    val satellites: Int,
    /** Signals in view, all bands */
    val signals: Int,
    /** Signals used in the fix */
    val signalsUsed: Int,
    /** Satellites received on two or more bands with a C/N0 */
    val multiBandSatellites: Int,
)

fun signalSummary(
    groups: List<SatelliteGroup>,
    labeler: SignalLabeler = ::defaultSignalLabel
): SignalSummary {
    val satellites = groups.flatMap { it.satellites }
    val signals = satellites.flatMap { it.signals }
    return SignalSummary(
        satellites = satellites.size,
        signals = signals.size,
        signalsUsed = signals.count { it.usedInFix },
        multiBandSatellites = satellites.count { satellite ->
            satellite.signals.filter { it.hasCn0 }.map(labeler).distinct().size >= 2
        },
    )
}

/**
 * Average C/N0 per band over all satellites of the groups, strongest band first by frequency, e.g.
 * to compare L1 with L5 at a glance. Bands are matched by label, so GPS L5 and QZSS L5 share one
 * entry while Galileo's E5a is its own.
 */
fun averageCn0ByBand(
    groups: List<SatelliteGroup>,
    labeler: SignalLabeler = ::defaultSignalLabel
): List<Pair<String, Float>> {
    val byLabel = LinkedHashMap<String, MutableList<SatelliteStatus>>()
    groups.flatMap { it.satellites }.flatMap { it.signals }.filter { it.hasCn0 }.forEach {
        byLabel.getOrPut(labeler(it)) { mutableListOf() }.add(it)
    }
    return byLabel.entries
        .sortedByDescending { (_, statuses) ->
            statuses.maxOf { if (it.hasCarrierFrequency) it.carrierFrequencyHz else 0.0 }
        }
        .map { (label, statuses) -> label to statuses.map { it.cn0DbHz }.average().toFloat() }
}
