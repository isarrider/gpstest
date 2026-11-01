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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.gpstest.R
import com.android.gpstest.library.model.Cn0History
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.model.SbasType
import com.android.gpstest.library.osnma.OsnmaMark
import com.android.gpstest.library.util.CarrierFreqUtils
import com.android.gpstest.library.util.LibUIUtils
import com.android.gpstest.library.util.MathUtils

/**
 * Adds the grouped satellite list to the Status screen's lazy column: one row per satellite (its
 * signals side by side) and the satellites grouped by constellation (GNSS) or SBAS system. Each
 * group looks like a card, its header stays at the top of the screen while its satellites are
 * scrolled, and tapping the header collapses the group.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.groupedStatusItems(
    groups: List<SatelliteGroup>,
    isGnss: Boolean,
    osnmaMarks: Map<Int, OsnmaMark>,
    cn0History: Cn0History?,
) {
    if (groups.isEmpty()) {
        // Like the classic table, nothing is shown for SBAS if there are no SBAS satellites
        if (isGnss) {
            item(key = "gnss_not_available") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    elevation = 2.dp
                ) {
                    NotAvailable(true)
                }
            }
        }
        return
    }
    groups.forEach { group ->
        val expanded = group.key !in collapsedGroups
        stickyHeader(key = "header_" + group.key) {
            GroupSurface(
                shape = if (expanded) {
                    RoundedCornerShape(topStart = cardCorner, topEnd = cardCorner)
                } else {
                    RoundedCornerShape(cardCorner)
                }
            ) {
                GroupHeader(group, groupName(group), expanded) {
                    if (expanded) collapsedGroups.add(group.key) else collapsedGroups.remove(group.key)
                }
            }
        }
        if (expanded) {
            val last = group.satellites.lastOrNull()
            items(group.satellites, key = { "satellite_" + it.key }) { satellite ->
                GroupSurface(
                    shape = if (satellite === last) {
                        RoundedCornerShape(bottomStart = cardCorner, bottomEnd = cardCorner)
                    } else {
                        RectangleShape
                    }
                ) {
                    Column {
                        Divider()
                        val mark = if (satellite.gnssType == GnssType.GALILEO) {
                            osnmaMarks[satellite.svid]
                        } else {
                            null
                        }
                        val showDetails = satellite.key in satellitesWithDetails
                        SatelliteRow(satellite, mark, groupName(group), showDetails) {
                            if (showDetails) {
                                satellitesWithDetails.remove(satellite.key)
                            } else {
                                satellitesWithDetails.add(satellite.key)
                            }
                        }
                        if (showDetails) SatelliteDetails(satellite, cn0History)
                    }
                }
            }
        }
        item(key = "gap_" + group.key) {
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/** Same corner radius as Material cards */
private val cardCorner = 4.dp

/**
 * Background of one part of a group (header or satellite row), so that the parts together look
 * like one card. Each part is a separate item of the list so that the header can stick to the top.
 */
@Composable
private fun GroupSurface(shape: Shape, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp),
        shape = shape,
        elevation = 2.dp,
        content = content
    )
}

/** Keys of the satellites whose signal details the user opened by tapping them */
private val satellitesWithDetails = mutableStateListOf<String>()

/** Keys of the groups the user collapsed, kept while the app runs. Groups are expanded by default. */
private val collapsedGroups = mutableStateListOf<String>()

@Composable
private fun GroupHeader(
    group: SatelliteGroup,
    name: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    // Read as one item by screen readers, e.g. "Galileo, 7 of 9 satellites used in fix,
    // average C/N0 34 dB-Hz", with "collapse" or "expand" as the action
    val description = groupDescription(group, name)
    val actionLabel = stringResource(
        if (expanded) R.string.status_a11y_collapse else R.string.status_a11y_expand
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .clearAndSetSemantics {
                contentDescription = description
                heading()
                onClick(label = actionLabel) {
                    onToggle()
                    true
                }
            }
            .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Flag(group.representative, Modifier.width(36.dp))
        Text(
            text = name,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        val averageCn0 = group.averageCn0
        Text(
            text = if (averageCn0 != null) {
                stringResource(
                    R.string.status_group_summary_cn0,
                    group.numUsed,
                    group.satellites.size,
                    averageCn0
                )
            } else {
                stringResource(
                    R.string.status_group_summary,
                    group.numUsed,
                    group.satellites.size
                )
            } + if (expanded) "  ▴" else "  ▾",
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
private fun SatelliteRow(
    satellite: GroupedSatellite,
    osnmaMark: OsnmaMark?,
    groupName: String,
    showDetails: Boolean,
    onToggle: () -> Unit
) {
    // Read as one item by screen readers instead of piece by piece (the C/N0 bars have no text)
    val description = satelliteDescription(satellite, osnmaMark, groupName)
    val actionLabel = stringResource(
        if (showDetails) R.string.status_a11y_hide_details else R.string.status_a11y_show_details
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .clearAndSetSemantics {
                contentDescription = description
                onClick(label = actionLabel) {
                    onToggle()
                    true
                }
            }
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

/**
 * Details shown below a satellite after tapping it: per signal the exact carrier frequency, the
 * C/N0 with one decimal and, if the device reports it, the baseband C/N0; then the exact elevation
 * and azimuth
 */
@Composable
private fun SatelliteDetails(satellite: GroupedSatellite, cn0History: Cn0History?) {
    // Samples of all signals, with the newest sample of the satellite as the right edge of the
    // charts, so the signals' charts line up
    val history = satellite.signals.map { cn0History?.get(it) ?: emptyList() }
    val newest = history.mapNotNull { it.lastOrNull()?.timeMillis }.maxOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
            .padding(start = 66.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
    ) {
        satellite.signals.forEach { status ->
            val label = carrierLabel(status).ifEmpty { "\u2013" }
            val frequency = if (status.hasCarrierFrequency) {
                stringResource(
                    R.string.status_details_frequency,
                    MathUtils.toMhz(status.carrierFrequencyHz)
                )
            } else {
                stringResource(R.string.status_details_no_frequency)
            }
            val cn0 = if (status.cn0DbHz != SatelliteStatus.NO_DATA) {
                stringResource(R.string.status_details_cn0, status.cn0DbHz)
            } else {
                stringResource(R.string.status_details_no_cn0)
            }
            val baseband = if (status.hasBasebandCn0DbHz &&
                status.basebandCn0DbHz != SatelliteStatus.NO_DATA
            ) {
                stringResource(R.string.status_details_baseband_cn0, status.basebandCn0DbHz)
            } else {
                ""
            }
            val used = stringResource(
                if (status.usedInFix) R.string.status_details_used else R.string.status_details_not_used
            )
            Text(
                text = "$label: $frequency, $cn0$baseband$used",
                fontSize = 12.sp
            )
            if (cn0History != null && newest != null) {
                Cn0Chart(
                    samples = history[satellite.signals.indexOf(status)],
                    newestMillis = newest,
                    windowMillis = cn0History.windowMillis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }
        }
        if (cn0History != null && newest != null) {
            Text(
                text = stringResource(
                    R.string.status_details_history_caption,
                    cn0History.windowMillis / 60_000
                ),
                fontSize = 11.sp,
                modifier = Modifier.alpha(0.7f)
            )
        }
        val first = satellite.first
        if (first.elevationDegrees != SatelliteStatus.NO_DATA ||
            first.azimuthDegrees != SatelliteStatus.NO_DATA
        ) {
            Text(
                text = stringResource(
                    R.string.status_details_position,
                    first.elevationDegrees,
                    first.azimuthDegrees
                ),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Screen reader text for a group header */
@Composable
private fun groupDescription(group: SatelliteGroup, name: String): String {
    val averageCn0 = group.averageCn0
    return stringResource(
        R.string.status_a11y_group,
        name,
        group.numUsed,
        group.satellites.size
    ) + if (averageCn0 != null) {
        stringResource(R.string.status_a11y_group_cn0, averageCn0)
    } else {
        ""
    }
}

/**
 * Screen reader text for a satellite row, e.g. "Galileo 6, navigation data authenticated by
 * Galileo OSNMA, E1 41.9 dB-Hz, E5a 28.6 dB-Hz, elevation 62 degrees, azimuth 214 degrees, used in
 * fix"
 */
@Composable
private fun satelliteDescription(
    satellite: GroupedSatellite,
    osnmaMark: OsnmaMark?,
    groupName: String
): String {
    val parts = mutableListOf(
        stringResource(R.string.status_a11y_satellite, groupName, satellite.svid)
    )
    if (osnmaMark != null) {
        parts.add(
            stringResource(
                if (osnmaMark == OsnmaMark.AUTHENTICATED) {
                    R.string.osnma_mark_authenticated
                } else {
                    R.string.osnma_mark_failed
                }
            )
        )
    }
    satellite.signals.forEach { status ->
        val label = carrierLabel(status)
        if (status.cn0DbHz != SatelliteStatus.NO_DATA) {
            parts.add(stringResource(R.string.status_a11y_signal, label, status.cn0DbHz).trim())
        } else if (label.isNotEmpty()) {
            parts.add(label)
        }
    }
    val first = satellite.first
    if (first.elevationDegrees != SatelliteStatus.NO_DATA ||
        first.azimuthDegrees != SatelliteStatus.NO_DATA
    ) {
        parts.add(
            stringResource(
                R.string.status_a11y_position,
                first.elevationDegrees,
                first.azimuthDegrees
            )
        )
    }
    parts.add(
        stringResource(
            if (satellite.usedInFix) R.string.status_a11y_used else R.string.status_a11y_not_used
        )
    )
    return parts.joinToString(", ")
}

private const val CHART_MIN_CN0 = 10f
private const val CHART_MAX_CN0 = 50f

/** Breaks the line where a signal wasn't received for more than this time */
private const val CHART_GAP_MILLIS = 3_000L

/**
 * C/N0 of a signal over time, from 10 to 50 dB-Hz with grid lines at 20, 30 and 40 dB-Hz. The right
 * edge is [newestMillis], the left edge [windowMillis] earlier. The line uses the sky view color of
 * the latest C/N0.
 */
@Composable
private fun Cn0Chart(
    samples: List<Cn0History.Sample>,
    newestMillis: Long,
    windowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val lineColor = cn0Color(samples.lastOrNull()?.cn0DbHz ?: CHART_MIN_CN0)
    val gridColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        fun y(cn0: Float): Float {
            val clamped = cn0.coerceIn(CHART_MIN_CN0, CHART_MAX_CN0)
            return size.height * (1f - (clamped - CHART_MIN_CN0) / (CHART_MAX_CN0 - CHART_MIN_CN0))
        }

        fun x(timeMillis: Long): Float {
            return size.width * (1f - (newestMillis - timeMillis).toFloat() / windowMillis)
        }

        listOf(20f, 30f, 40f).forEach {
            drawLine(gridColor, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1f)
        }
        val stroke = 2.dp.toPx()
        var previous: Cn0History.Sample? = null
        samples.forEach { sample ->
            val p = previous
            if (p != null && sample.timeMillis - p.timeMillis <= CHART_GAP_MILLIS) {
                drawLine(
                    lineColor,
                    Offset(x(p.timeMillis), y(p.cn0DbHz)),
                    Offset(x(sample.timeMillis), y(sample.cn0DbHz)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            } else {
                // Start of the line or after a gap: a dot, so single samples are visible too
                drawCircle(
                    lineColor,
                    radius = stroke / 2,
                    center = Offset(x(sample.timeMillis), y(sample.cn0DbHz))
                )
            }
            previous = sample
        }
    }
}
