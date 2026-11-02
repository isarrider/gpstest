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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.gpstest.R
import com.android.gpstest.library.model.Cn0History
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.ui.status.SatelliteGroup
import com.android.gpstest.ui.status.groupName
import java.util.Locale

private const val HISTORY_MIN_CN0 = 10f
private const val HISTORY_MAX_CN0 = 50f
private val HISTORY_GRID = listOf(10f, 20f, 30f, 40f, 50f)
private val HISTORY_HEIGHT = 660.dp
private val HISTORY_AXIS_WIDTH = 22.dp

/** Samples further apart than this aren't connected, e.g. while a satellite was blocked */
private const val HISTORY_GAP_MILLIS = 3_000L

/** Line colour of each constellation, mid tones that read on light and dark cards */
private fun groupColor(group: HistoryGroup): Color = when (group.gnssType) {
    GnssType.NAVSTAR -> Color(0xFF378ADD)
    GnssType.GALILEO -> Color(0xFFE0A030)
    GnssType.GLONASS -> Color(0xFFD85A30)
    GnssType.BEIDOU -> Color(0xFFD4537E)
    GnssType.QZSS -> Color(0xFF1D9E75)
    GnssType.IRNSS -> Color(0xFF7F77DD)
    GnssType.SBAS -> Color(0xFF8E8C85)
    GnssType.UNKNOWN -> Color(0xFF6F6E69)
}

/** Lines of one constellation or SBAS system, already reduced to pixel columns */
private class GroupLines(
    val group: HistoryGroup,
    val signals: List<FloatArray>,
    val average: FloatArray,
) {
    /** Latest value of the average line, for the legend */
    val latestAverage: Float? get() = average.lastOrNull { !it.isNaN() }
}

/**
 * The C/N0 of all signals in one chart: a thin line per signal and a thick average line per
 * constellation, over the last 5, 15 or 60 minutes. Constellations can be hidden by tapping them
 * in the legend.
 *
 * [history] is read again whenever [tick] changes (every new GNSS status).
 */
@Composable
fun SignalHistoryCard(
    history: Cn0History,
    tick: Any?,
    gnssOrder: List<GnssType>,
    shownTypes: Set<GnssType>,
) {
    val windowMinutes = remember { mutableStateOf(HISTORY_WINDOWS_MINUTES[1]) }
    val hidden = remember { mutableStateListOf<HistoryGroup>() }
    val windowMillis = windowMinutes.value * 60_000L

    // A snapshot of the history at each update. Reduced to pixel columns in the Canvas, where the
    // width is known.
    val series = remember(tick) { history.all() }
    val newestMillis = remember(tick) { System.nanoTime() / 1_000_000 }
    val groups = remember(series, gnssOrder, shownTypes) {
        series.filter { shownTypes.isEmpty() || it.gnssType in shownTypes }
            .groupBy { it.historyGroup() }
            .toList()
            .sortedWith(compareBy({ (g, _) ->
                gnssOrder.indexOf(g.gnssType).let { if (it < 0) Int.MAX_VALUE else it }
            }, { (g, _) -> g.sbasType.ordinal }))
    }
    // Columns for the legend averages; the Canvas recomputes at its own width
    val legendLines = remember(groups, windowMillis, newestMillis) {
        groups.map { (group, list) -> reduce(group, list, newestMillis, windowMillis, LEGEND_COLUMNS) }
    }

    val gridColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
    val axisTextColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.signals_history_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                HISTORY_WINDOWS_MINUTES.forEach { minutes ->
                    WindowChip(minutes, windowMinutes.value == minutes) { windowMinutes.value = minutes }
                }
            }

            if (series.isEmpty()) {
                Text(
                    text = stringResource(R.string.signals_history_empty),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                )
                return@Column
            }

            val chartDescription = stringResource(R.string.signals_a11y_history, windowMinutes.value)
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clearAndSetSemantics { contentDescription = chartDescription }
            ) {
                Box(
                    modifier = Modifier
                        .width(HISTORY_AXIS_WIDTH)
                        .height(HISTORY_HEIGHT)
                ) {
                    HISTORY_GRID.forEach { value ->
                        Text(
                            text = String.format(Locale.US, "%.0f", value),
                            fontSize = 9.sp,
                            color = axisTextColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .width(HISTORY_AXIS_WIDTH - 3.dp)
                                .offset(y = HISTORY_HEIGHT * yFraction(value) - 6.dp)
                        )
                    }
                }
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(HISTORY_HEIGHT)
                ) {
                    HISTORY_GRID.forEach { value ->
                        val y = size.height * yFraction(value)
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    val columns = size.width.toInt().coerceAtLeast(1)
                    val maxGap = maxOf(1, (HISTORY_GAP_MILLIS / (windowMillis.toFloat() / columns)).toInt() + 1)
                    val visible = groups.filter { (group, _) -> group !in hidden }
                        .map { (group, list) -> reduce(group, list, newestMillis, windowMillis, columns) }
                    val thin = 1.dp.toPx()
                    val thick = 3.dp.toPx()
                    // Every signal first, then the averages on top
                    visible.forEach { lines ->
                        val color = groupColor(lines.group).copy(alpha = 0.3f)
                        lines.signals.forEach { drawRuns(it, maxGap, color, thin) }
                    }
                    visible.forEach { lines ->
                        drawRuns(lines.average, maxGap, groupColor(lines.group), thick)
                    }
                }
            }

            // Time axis
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HISTORY_AXIS_WIDTH, top = 2.dp)
                    .clearAndSetSemantics { }
            ) {
                val minutes = windowMinutes.value
                listOf(minutes, minutes * 2 / 3, minutes / 3).forEach { ago ->
                    Text(
                        text = stringResource(R.string.signals_history_ago, ago),
                        fontSize = 9.sp,
                        color = axisTextColor,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = stringResource(R.string.signals_history_now),
                    fontSize = 9.sp,
                    color = axisTextColor,
                )
            }

            // Legend: tap to hide or show a constellation
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                legendLines.forEach { lines ->
                    val isHidden = lines.group in hidden
                    LegendChip(lines, isHidden) {
                        if (isHidden) hidden.remove(lines.group) else hidden.add(lines.group)
                    }
                }
            }
            Text(
                text = stringResource(R.string.signals_history_caption),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
            )
        }
    }
}

/** Enough columns that the latest average in the legend reflects the last few seconds */
private const val LEGEND_COLUMNS = 360

private fun reduce(
    group: HistoryGroup,
    list: List<Cn0History.Series>,
    newestMillis: Long,
    windowMillis: Long,
    columns: Int,
): GroupLines {
    val signals = list.map { it.svid to columnAverages(it, newestMillis, windowMillis, columns) }
    return GroupLines(group, signals.map { it.second }, groupAverage(signals, columns))
}

/** Position of a C/N0 from the top, 0 at [HISTORY_MAX_CN0] and 1 at [HISTORY_MIN_CN0] */
private fun yFraction(cn0: Float): Float {
    val clamped = cn0.coerceIn(HISTORY_MIN_CN0, HISTORY_MAX_CN0)
    return 1f - (clamped - HISTORY_MIN_CN0) / (HISTORY_MAX_CN0 - HISTORY_MIN_CN0)
}

private fun DrawScope.drawRuns(values: FloatArray, maxGap: Int, color: Color, width: Float) {
    connectedRuns(values, maxGap).forEach { run ->
        if (run.size == 1) {
            val (column, v) = run[0]
            drawCircle(color, radius = width / 2, center = Offset(column + 0.5f, size.height * yFraction(v)))
            return@forEach
        }
        val path = Path()
        run.forEachIndexed { i, (column, v) ->
            val x = column + 0.5f
            val y = size.height * yFraction(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun WindowChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.signals_history_window, minutes),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.08f)
            )
            .clickable { onClick() }
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
private fun LegendChip(lines: GroupLines, hidden: Boolean, onClick: () -> Unit) {
    val name = groupName(SatelliteGroup("", lines.group.gnssType, lines.group.sbasType, emptyList()))
    val average = lines.latestAverage
    val label = if (average != null) {
        stringResource(R.string.signals_band_average, name, average)
    } else {
        name
    }
    val description = label + ", " + stringResource(
        if (hidden) R.string.signals_a11y_hidden else R.string.signals_a11y_shown
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .alpha(if (hidden) 0.4f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
            .clickable { onClick() }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Switch
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(groupColor(lines.group))
        )
        Text(text = label, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
    }
}

