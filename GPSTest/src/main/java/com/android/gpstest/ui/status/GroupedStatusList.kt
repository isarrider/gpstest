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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.gpstest.R
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.model.SbasType
import com.android.gpstest.library.osnma.OsnmaMark
import com.android.gpstest.library.util.CarrierFreqUtils
import com.android.gpstest.library.util.LibUIUtils
import com.android.gpstest.library.util.MathUtils

/**
 * Status screen satellite list with one row per satellite (its signals side by side) and the
 * satellites grouped by constellation (GNSS) or SBAS system. Each group can be collapsed.
 */
@Composable
fun GroupedStatusCards(
    satStatuses: List<SatelliteStatus>,
    isGnss: Boolean,
    osnmaMarks: Map<Int, OsnmaMark> = emptyMap(),
    sortOrder: GroupedSortOrder = GroupedSortOrder.ID,
) {
    if (satStatuses.isEmpty()) {
        if (isGnss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                elevation = 2.dp
            ) {
                NotAvailable(true)
            }
        }
        // Like the classic table, nothing is shown for SBAS if there are no SBAS satellites
        return
    }
    groupSatellites(satStatuses, isGnss, sortOrder).forEach { group ->
        GroupCard(group, osnmaMarks)
    }
}

/** Keys of the groups the user collapsed, kept while the app runs. Groups are expanded by default. */
private val collapsedGroups = mutableStateListOf<String>()

@Composable
private fun GroupCard(group: SatelliteGroup, osnmaMarks: Map<Int, OsnmaMark>) {
    val expanded = group.key !in collapsedGroups
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp),
        elevation = 2.dp
    ) {
        Column {
            GroupHeader(group, expanded) {
                if (expanded) collapsedGroups.add(group.key) else collapsedGroups.remove(group.key)
            }
            if (expanded) {
                group.satellites.forEach { satellite ->
                    Divider()
                    val mark = if (satellite.gnssType == GnssType.GALILEO) {
                        osnmaMarks[satellite.svid]
                    } else {
                        null
                    }
                    SatelliteRow(satellite, mark)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SatelliteGroup, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Flag(group.representative, Modifier.width(36.dp))
        Text(
            text = groupName(group),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(
                R.string.status_group_summary,
                group.numUsed,
                group.satellites.size
            ) + if (expanded) "  ▴" else "  ▾",
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun groupName(group: SatelliteGroup): String {
    if (group.gnssType == GnssType.SBAS) {
        return stringResource(
            when (group.sbasType) {
                SbasType.WAAS -> R.string.waas_content_description
                SbasType.EGNOS -> R.string.egnos_content_description
                SbasType.GAGAN -> R.string.gagan_content_description
                SbasType.MSAS -> R.string.msas_content_description
                SbasType.SDCM -> R.string.sdcm_content_description
                SbasType.SNAS -> R.string.snas_content_description
                SbasType.SACCSA -> R.string.saccsa_content_description
                SbasType.SOUTHPAN -> R.string.southpan_content_description
                SbasType.UNKNOWN -> R.string.status_group_sbas_unknown
            }
        )
    }
    return stringResource(
        when (group.gnssType) {
            GnssType.NAVSTAR -> R.string.gps_content_description
            GnssType.GLONASS -> R.string.glonass_content_description
            GnssType.GALILEO -> R.string.galileo_content_description
            GnssType.BEIDOU -> R.string.beidou_content_description
            GnssType.QZSS -> R.string.qzss_content_description
            GnssType.IRNSS -> R.string.irnss_content_description
            GnssType.SBAS, GnssType.UNKNOWN -> R.string.status_group_unknown
        }
    )
}

/** Satellite ID with the usual one-letter constellation prefix, e.g. "E06" */
private fun satelliteId(satellite: GroupedSatellite): String {
    val prefix = when (satellite.gnssType) {
        GnssType.NAVSTAR -> "G"
        GnssType.GLONASS -> "R"
        GnssType.GALILEO -> "E"
        GnssType.BEIDOU -> "C"
        GnssType.IRNSS -> "I"
        // QZSS and SBAS use PRNs above 99 on Android, so these are shown as plain numbers
        GnssType.QZSS, GnssType.SBAS, GnssType.UNKNOWN -> ""
    }
    // GLONASS satellites without a known slot are reported with their frequency channel (93-106)
    if (prefix.isEmpty() || satellite.svid > 99) return satellite.svid.toString()
    return prefix + String.format("%02d", satellite.svid)
}

@Composable
private fun SatelliteRow(satellite: GroupedSatellite, osnmaMark: OsnmaMark?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (satellite.usedInFix) 1f else 0.55f)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(54.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = satelliteId(satellite), fontSize = 13.sp)
            Spacer(modifier = Modifier.width(2.dp))
            OsnmaMarkIcon(osnmaMark)
        }
        Column(modifier = Modifier.weight(1f)) {
            // Two signals per line, so phones with three frequencies (e.g. L1, L2, L5) still fit
            satellite.signals.chunked(2).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    line.forEach { SignalCn0(it) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(76.dp)) {
            Text(
                text = elevationAzimuth(satellite.first),
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            Text(
                text = satellite.aeu,
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

/** Carrier label, a C/N0 bar coloured like the sky view, and the C/N0 value */
@Composable
private fun SignalCn0(status: SatelliteStatus) {
    Row(
        modifier = Modifier
            .width(98.dp)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = carrierLabel(status),
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(30.dp)
        )
        val hasCn0 = status.cn0DbHz != SatelliteStatus.NO_DATA
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
        ) {
            if (hasCn0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(cn0Fraction(status.cn0DbHz))
                        .background(cn0Color(status.cn0DbHz))
                )
            }
        }
        Text(
            text = if (hasCn0) String.format("%.1f", status.cn0DbHz) else "",
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun carrierLabel(status: SatelliteStatus): String {
    if (!status.hasCarrierFrequency) return ""
    val label = CarrierFreqUtils.getCarrierFrequencyLabel(status)
    if (label != CarrierFreqUtils.CF_UNKNOWN) return label
    // Unknown band: show the frequency in MHz without decimals to keep it short
    return String.format("%.0f", MathUtils.toMhz(status.carrierFrequencyHz))
}

private fun cn0Fraction(cn0: Float): Float {
    val min = LibUIUtils.MIN_VALUE_CN0
    val max = LibUIUtils.MAX_VALUE_CN0
    return ((cn0 - min) / (max - min)).coerceIn(0.08f, 1f)
}

/** Same thresholds and colours as GpsSkyView.getSatelliteColor() */
@Composable
private fun cn0Color(cn0: Float): Color {
    val thresholds = floatArrayOf(
        LibUIUtils.MIN_VALUE_CN0,
        21.67f,
        33.3f,
        LibUIUtils.MAX_VALUE_CN0
    )
    val colors = listOf(
        colorResource(R.color.gray),
        colorResource(R.color.red),
        colorResource(R.color.yellow),
        colorResource(R.color.green)
    )
    if (cn0 <= thresholds.first()) return colors.first()
    if (cn0 >= thresholds.last()) return colors.last()
    for (i in 0 until thresholds.size - 1) {
        if (cn0 <= thresholds[i + 1]) {
            val f = (cn0 - thresholds[i]) / (thresholds[i + 1] - thresholds[i])
            return lerp(colors[i], colors[i + 1], f)
        }
    }
    return colors.last()
}

@Composable
private fun elevationAzimuth(status: SatelliteStatus): String {
    val elevation = if (status.elevationDegrees != SatelliteStatus.NO_DATA) {
        String.format("%.0f°", status.elevationDegrees)
    } else {
        "–"
    }
    val azimuth = if (status.azimuthDegrees != SatelliteStatus.NO_DATA) {
        String.format("%.0f°", status.azimuthDegrees)
    } else {
        "–"
    }
    return "$elevation · $azimuth"
}
