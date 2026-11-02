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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.gpstest.Application.Companion.app
import com.android.gpstest.Application.Companion.prefs
import com.android.gpstest.R
import com.android.gpstest.library.model.SatelliteMetadata
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.ui.SignalInfoViewModel
import com.android.gpstest.library.util.PreferenceUtils
import com.android.gpstest.library.util.PreferenceUtils.gnssFilter
import com.android.gpstest.ui.status.Filter
import com.android.gpstest.ui.status.Flag
import com.android.gpstest.ui.status.GroupedSatellite
import com.android.gpstest.ui.status.GroupedSortOrder
import com.android.gpstest.ui.status.SatelliteGroup
import com.android.gpstest.ui.status.cn0Color
import com.android.gpstest.ui.status.groupName
import com.android.gpstest.ui.status.groupSatellites
import com.android.gpstest.ui.status.parseGnssOrder
import com.android.gpstest.ui.status.preferenceState
import com.android.gpstest.ui.status.satelliteId
import com.android.gpstest.ui.status.splitAroundSbas
import java.util.Locale

/** Top of the bar scale; stronger signals are drawn as full bars */
private const val BAR_SCALE_MAX = 55f
private val GRID_LINES = listOf(10f, 20f, 30f, 40f, 50f)
private val CHART_HEIGHT = 120.dp
private val VALUE_LABEL_HEIGHT = 12.dp
private val BAR_WIDTH = 11.dp
private val BAR_GAP = 2.dp
private val AXIS_WIDTH = 22.dp
private const val NOT_USED_ALPHA = 0.4f

/**
 * C/N0 of every received signal at a glance: per constellation either a bar chart with one bar
 * per signal (grouped by satellite) or a table with one column per band.
 */
@Composable
fun SignalsScreen(viewModel: SignalInfoViewModel) {
    val gnssStatuses: List<SatelliteStatus> by viewModel.filteredGnssStatuses.observeAsState(emptyList())
    val sbasStatuses: List<SatelliteStatus> by viewModel.filteredSbasStatuses.observeAsState(emptyList())
    val allStatuses: List<SatelliteStatus> by viewModel.allStatuses.observeAsState(emptyList())
    val satelliteMetadata: SatelliteMetadata by viewModel.filteredSatelliteMetadata.observeAsState(
        SatelliteMetadata()
    )
    val groupedSortIndex: Int by preferenceState(R.string.pref_key_grouped_sat_sort) { p, key ->
        p.getInt(key, 0)
    }
    val savedGnssOrder: String? by preferenceState(R.string.pref_key_gnss_order) { p, key ->
        p.getString(key, null)
    }
    val tableView: Boolean by preferenceState(R.string.pref_key_signals_table) { p, key ->
        p.getBoolean(key, false)
    }
    val tableKey = stringResource(R.string.pref_key_signals_table)

    val sortOrder = GroupedSortOrder.fromIndex(groupedSortIndex)
    val gnssOrder = remember(savedGnssOrder) { parseGnssOrder(savedGnssOrder) }
    val groups = remember(gnssStatuses, sbasStatuses, sortOrder, gnssOrder) {
        val gnssGroups = groupSatellites(gnssStatuses, true, sortOrder, gnssOrder)
        val sbasGroups = groupSatellites(sbasStatuses, false, sortOrder)
        // SBAS appears where the user put it in the order of the GNSS filter dialog
        val (beforeSbas, afterSbas) = splitAroundSbas(gnssGroups, gnssOrder)
        beforeSbas + sbasGroups + afterSbas
    }
    val summary = remember(groups) { signalSummary(groups) }
    val bandAverages = remember(groups) { averageCn0ByBand(groups) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "summary") {
            SummaryCard(summary, bandAverages, tableView) { table ->
                PreferenceUtils.saveBoolean(tableKey, table, prefs)
            }
        }
        if (gnssFilter(app, prefs).isNotEmpty()) {
            item(key = "filter") {
                Filter(allStatuses.size, satelliteMetadata) { PreferenceUtils.clearGnssFilter(app, prefs) }
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.signals_no_signals),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }
        }
        items(groups, key = { it.key }) { group ->
            GroupCard(group, tableView)
        }
    }
}

@Composable
private fun SummaryCard(
    summary: SignalSummary,
    bandAverages: List<Pair<String, Float>>,
    tableView: Boolean,
    onViewChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.signals_summary,
                        summary.satellites,
                        summary.signals,
                        summary.signalsUsed,
                        summary.multiBandSatellites
                    ),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                ViewToggle(tableView, onViewChange)
            }
            if (bandAverages.isNotEmpty()) {
                val description = stringResource(R.string.signals_a11y_band_averages) + ": " +
                        bandAverages.joinToString(", ") { (label, cn0) ->
                            String.format(Locale.US, "%s %.0f dB-Hz", label, cn0)
                        }
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState())
                        .clearAndSetSemantics { contentDescription = description },
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bandAverages.forEach { (label, cn0) -> BandAverageChip(label, cn0) }
                }
            }
            Text(
                text = stringResource(R.string.signals_legend),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun BandAverageChip(label: String, cn0: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(cn0Color(cn0))
        )
        Text(
            text = stringResource(R.string.signals_band_average, label, cn0),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** Bars | Table switch */
@Composable
private fun ViewToggle(tableView: Boolean, onViewChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
    ) {
        ToggleOption(
            text = stringResource(R.string.signals_view_bars),
            description = stringResource(R.string.signals_a11y_view_bars),
            selected = !tableView
        ) { onViewChange(false) }
        ToggleOption(
            text = stringResource(R.string.signals_view_table),
            description = stringResource(R.string.signals_a11y_view_table),
            selected = tableView
        ) { onViewChange(true) }
    }
}

@Composable
private fun ToggleOption(text: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.surface.copy(alpha = 0f),
        contentColor = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
        modifier = Modifier
            .clickable { onClick() }
            .semantics {
                contentDescription = description
                role = Role.Tab
                this.selected = selected
            }
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun GroupCard(group: SatelliteGroup, tableView: Boolean) {
    val name = groupName(group)
    // One fixed slot per band seen in this constellation, so bars and columns don't jump
    val seenBands = remember(group.key) { SeenBands() }
    val table = remember(group) { signalTable(group, knownColumns = seenBands.update(group)) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            GroupHeader(group, name)
            if (tableView) {
                BandTable(table, name)
            } else {
                SignalBars(table, name)
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SatelliteGroup, name: String) {
    val averageCn0 = group.averageCn0
    val summary = if (averageCn0 != null) {
        stringResource(
            R.string.status_group_summary_cn0,
            group.numUsed,
            group.satellites.size,
            averageCn0
        )
    } else {
        stringResource(R.string.status_group_summary, group.numUsed, group.satellites.size)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
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
        Text(text = summary, fontSize = 12.sp, maxLines = 1)
    }
}

/** Screen reader text of a satellite, e.g. "Galileo 6, used in fix, E1 38.0 dB-Hz, E5a 33.5 dB-Hz" */
@Composable
private fun satelliteDescription(satellite: GroupedSatellite, groupName: String): String {
    val parts = mutableListOf(stringResource(R.string.status_a11y_satellite, groupName, satellite.svid))
    parts.add(
        stringResource(
            if (satellite.usedInFix) R.string.status_a11y_used else R.string.status_a11y_not_used
        )
    )
    satellite.signals.filter { it.hasCn0 }.forEach {
        parts.add(stringResource(R.string.status_a11y_signal, defaultSignalLabel(it), it.cn0DbHz).trim())
    }
    return parts.joinToString(", ")
}

/** Height of a bar for a C/N0 */
private fun barHeight(cn0: Float): Dp =
    CHART_HEIGHT * (cn0.coerceIn(0f, BAR_SCALE_MAX) / BAR_SCALE_MAX)

/**
 * One bar per signal, grouped by satellite. The y axis and grid lines stay in place while the bars
 * scroll sideways.
 */
@Composable
private fun SignalBars(table: SignalTable, name: String) {
    val gridColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
    val axisTextColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    Row(modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp)) {
        // y axis labels, aligned with the grid lines
        Box(
            modifier = Modifier
                .width(AXIS_WIDTH)
                .height(VALUE_LABEL_HEIGHT + CHART_HEIGHT)
                .clearAndSetSemantics { }
        ) {
            GRID_LINES.forEach { value ->
                Text(
                    text = String.format(Locale.US, "%.0f", value),
                    fontSize = 9.sp,
                    color = axisTextColor,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(AXIS_WIDTH - 3.dp)
                        .offset(y = VALUE_LABEL_HEIGHT + CHART_HEIGHT - barHeight(value) - 6.dp)
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = VALUE_LABEL_HEIGHT)
                    .height(CHART_HEIGHT)
            ) {
                GRID_LINES.forEach { value ->
                    val y = size.height * (1f - value / BAR_SCALE_MAX)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawLine(
                    gridColor.copy(alpha = 0.3f),
                    Offset(0f, size.height),
                    Offset(size.width, size.height),
                    strokeWidth = 2f
                )
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                table.rows.forEach { row ->
                    SatelliteBars(row, table.columns, name)
                }
            }
        }
    }
}

/**
 * The bars of one satellite: one slot per band of the constellation (see [SeenBands]), empty if
 * the satellite doesn't send that band right now, so the bars stay in place.
 */
@Composable
private fun SatelliteBars(row: SignalTableRow, columns: List<SignalColumn>, groupName: String) {
    val satellite = row.satellite
    val description = satelliteDescription(satellite, groupName)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
            modifier = Modifier.height(VALUE_LABEL_HEIGHT + CHART_HEIGHT)
        ) {
            row.cells.forEach { status ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(BAR_WIDTH)
                        .alpha(if (status?.usedInFix == true) 1f else NOT_USED_ALPHA)
                ) {
                    if (status != null && status.hasCn0) {
                        Text(
                            text = String.format(Locale.US, "%.0f", status.cn0DbHz),
                            fontSize = 8.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Box(
                            modifier = Modifier
                                .width(BAR_WIDTH)
                                .height(barHeight(status.cn0DbHz))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(cn0Color(status.cn0DbHz))
                        )
                    }
                }
            }
        }
        // Band label under each bar
        Row(horizontalArrangement = Arrangement.spacedBy(BAR_GAP)) {
            columns.forEachIndexed { index, column ->
                Text(
                    text = column.label,
                    fontSize = 7.sp,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    // Faint label for a band the satellite doesn't send right now
                    color = MaterialTheme.colors.onSurface.copy(
                        alpha = if (row.cells[index] != null) 0.7f else 0.25f
                    ),
                    modifier = Modifier.width(BAR_WIDTH)
                )
            }
        }
        Text(
            text = satelliteId(satellite),
            fontSize = 10.sp,
            fontWeight = if (satellite.usedInFix) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val ID_COLUMN_WIDTH = 48.dp
private val BAND_COLUMN_WIDTH = 58.dp

/** One row per satellite, one column per band, C/N0 values with the sky view colours */
@Composable
private fun BandTable(table: SignalTable, name: String) {
    Column(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                text = stringResource(R.string.signals_table_id),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(ID_COLUMN_WIDTH)
            )
            table.columns.forEach { column ->
                Text(
                    text = column.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(BAND_COLUMN_WIDTH)
                )
            }
        }
        table.rows.forEach { row ->
            val description = satelliteDescription(row.satellite, name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clearAndSetSemantics { contentDescription = description }
            ) {
                Text(
                    text = satelliteId(row.satellite),
                    fontSize = 12.sp,
                    fontWeight = if (row.satellite.usedInFix) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(ID_COLUMN_WIDTH)
                )
                row.cells.forEach { status -> BandCell(status) }
            }
        }
    }
}

@Composable
private fun BandCell(status: SatelliteStatus?) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(BAND_COLUMN_WIDTH)
            .alpha(if (status?.usedInFix == true) 1f else NOT_USED_ALPHA)
    ) {
        if (status != null && status.hasCn0) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(cn0Color(status.cn0DbHz))
            )
            Text(
                text = String.format(Locale.US, "%.1f", status.cn0DbHz),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        } else {
            Text(text = if (status != null) "0" else "·", fontSize = 12.sp)
        }
    }
}
