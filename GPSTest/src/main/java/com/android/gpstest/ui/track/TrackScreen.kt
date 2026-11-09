/*
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
package com.android.gpstest.ui.track

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.gpstest.Application.Companion.app
import com.android.gpstest.Application.Companion.prefs
import com.android.gpstest.R
import com.android.gpstest.io.GpxTrackRecorder
import com.android.gpstest.io.GpxTrackStats
import com.android.gpstest.library.util.PreferenceUtil
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

@Composable
fun TrackScreen() {
    val state by GpxTrackRecorder.state.collectAsState()
    val stats by GpxTrackRecorder.stats.collectAsState()

    // Tick once a second while recording so the duration keeps counting between fixes
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val s = stats
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(5.dp)
    ) {
        if (s == null) {
            Text(
                text = stringResource(R.string.track_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center
            )
        } else {
            TrackContent(s, state, if (state.isRecording) now - s.startTime else s.durationMillis)
        }
    }
}

@Composable
private fun TrackContent(s: GpxTrackStats, state: GpxTrackRecorder.State, duration: Long) {
    val units = TrackUnits.fromPrefs()
    Column {

        TrackCard(if (state.isRecording) R.string.track_title_recording else R.string.track_title_last) {
            StatRow(R.string.track_started, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startTime)))
            StatRow(R.string.track_duration, DateUtils.formatElapsedTime(duration / 1000))
            StatRow(R.string.track_moving_time, DateUtils.formatElapsedTime(s.movingTimeMillis / 1000))
            // The rest of the duration: stops, and gaps without a fix
            StatRow(R.string.track_stopped_time, DateUtils.formatElapsedTime(max(0L, duration - s.movingTimeMillis) / 1000))
            StatRow(R.string.track_distance, units.distance(s.distanceMeters))
        }

        TrackCard(R.string.track_section_speed) {
            // The current speed only means something while recording
            if (state.isRecording) {
                StatRow(R.string.track_speed_current, s.currentSpeed?.let { units.speed(it.toDouble()) } ?: DASH)
                StatRow(R.string.track_direction, s.currentBearing?.let { TrackUnits.direction(it) } ?: DASH)
            }
            StatRow(R.string.track_avg_speed, s.averageMovingSpeed?.let { units.speed(it) } ?: DASH)
            StatRow(R.string.track_avg_speed_total, s.averageSpeed?.let { units.speed(it) } ?: DASH)
            StatRow(R.string.track_max_speed, s.maxSpeed?.let { units.speed(it.toDouble()) } ?: DASH)
        }

        TrackCard(R.string.track_section_elevation) {
            StatRow(R.string.track_elevation_current, s.currentElevation?.let { units.height(it) } ?: DASH)
            StatRow(R.string.track_elevation_min, s.minElevation?.let { units.height(it) } ?: DASH)
            StatRow(R.string.track_elevation_max, s.maxElevation?.let { units.height(it) } ?: DASH)
            StatRow(R.string.track_ascent, units.height(s.ascentMeters))
            StatRow(R.string.track_descent, units.height(s.descentMeters))
        }

        TrackCard(R.string.track_section_gnss) {
            // Current values only while recording, like the current speed
            if (state.isRecording) {
                StatRow(R.string.track_sats_current, s.currentSatellites?.toString() ?: DASH)
            }
            StatRow(R.string.track_avg_sats, s.avgSatellites?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: DASH)
            if (state.isRecording) {
                StatRow(R.string.track_accuracy_current, s.currentAccuracy?.let { units.accuracy(it.toDouble()) } ?: DASH)
            }
            StatRow(R.string.track_avg_accuracy, s.avgAccuracy?.let { units.accuracy(it) } ?: DASH)
            StatRow(R.string.track_avg_hdop, s.avgHdop?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: DASH)
            StatRow(R.string.track_dual_freq, s.dualFrequencyShare?.let { percent(it) } ?: DASH)
            StatRow(R.string.track_sbas, s.sbasShare?.let { percent(it) } ?: DASH)
            if (s.osnmaPoints > 0) {
                StatRow(R.string.track_osnma_all, percent(s.osnmaAllAuthenticated.toDouble() / s.osnmaPoints))
                StatRow(R.string.track_osnma_failed, s.osnmaFailedPoints.toString())
            }
        }

        if (s.batteryStart != null) {
            TrackCard(R.string.track_section_battery) {
                StatRow(R.string.track_battery_start, percentPoints(s.batteryStart))
                StatRow(
                    if (state.isRecording) R.string.track_battery_now else R.string.track_battery_end,
                    s.batteryLast?.let { percentPoints(it) } ?: DASH
                )
                if (s.batteryCharged) {
                    StatRow(R.string.track_battery_used, stringResource(R.string.track_battery_charged))
                } else {
                    StatRow(R.string.track_battery_used, s.batteryUsed?.let { percentPoints(it) } ?: DASH)
                    StatRow(
                        R.string.track_battery_rate,
                        s.batteryPerHour?.let { String.format(Locale.getDefault(), "%.1f %%", it) } ?: DASH
                    )
                }
            }
        }

        TrackCard(R.string.track_section_file) {
            StatRow(R.string.track_points, s.points.toString())
            StatRow(R.string.track_segments, s.segments.toString())
            LogSettingsRows(units)
            if (state.isRecording) {
                StatRow(
                    R.string.track_last_write,
                    if (state.lastWriteTime > 0) DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(state.lastWriteTime))
                    else stringResource(R.string.track_not_yet)
                )
                StatRow(R.string.track_pending, state.pendingPoints.toString())
            }
            StatRow(R.string.track_file, s.fileLocation)
        }

        if (s.samples.size >= 2) {
            val satellitesUsed = stringResource(R.string.track_marker_satellites)
            ChartCard(
                stringResource(R.string.track_chart_distance, units.distanceAxisUnit),
                stringResource(R.string.track_chart_axis_minutes),
                s.samples.map { Entry(it.seconds / 60f, units.distanceAxis(it.distanceMeters)) },
                xText = ::elapsed,
                yText = { units.distanceAxisValue(it) }
            )
            val elevation = s.samples.mapNotNull { p -> p.elevation?.let { Entry(units.distanceAxis(p.distanceMeters), units.heightValue(it)) } }
            if (elevation.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_elevation, units.heightUnit),
                    stringResource(R.string.track_chart_axis_distance, units.distanceAxisUnit),
                    elevation,
                    xText = { units.distanceAxisValue(it) },
                    yText = { units.heightValueText(it) }
                )
            }
            val speed = s.samples.mapNotNull { p -> p.speed?.let { Entry(p.seconds / 60f, units.speedValue(it)) } }
            if (speed.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_speed, units.speedUnit),
                    stringResource(R.string.track_chart_axis_minutes),
                    speed,
                    xText = ::elapsed,
                    yText = { units.speedValueText(it) }
                )
            }
            val sats = s.samples.filter { it.satellites > 0 }.map { Entry(it.seconds / 60f, it.satellites.toFloat()) }
            if (sats.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_satellites),
                    stringResource(R.string.track_chart_axis_minutes),
                    sats,
                    xText = ::elapsed,
                    yText = { String.format(satellitesUsed, it.roundToInt()) }
                )
            }
            val battery = s.samples.mapNotNull { p -> p.battery?.let { Entry(p.seconds / 60f, it.toFloat()) } }
            if (battery.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_battery),
                    stringResource(R.string.track_chart_axis_minutes),
                    battery,
                    xText = ::elapsed,
                    yText = { percentPoints(it.roundToInt()) }
                )
            }
        }
    }
}

/**
 * The settings that decide how often a point is logged and when it's written: GNSS update
 * interval and minimum distance (every fix is recorded), write interval, segment gap and
 * satellite details. They're read when the screen is shown, so they also tell what the next
 * recording will use.
 */
@Composable
private fun LogSettingsRows(units: TrackUnits) {
    val seconds = DecimalFormat("0.##").format(PreferenceUtil.minTimeMillis(app, prefs) / 1000.0)
    val minDistance = PreferenceUtil.minDistance(app, prefs)
    StatRow(
        R.string.track_logging,
        if (minDistance > 0) {
            stringResource(R.string.track_logging_every_after, seconds, units.height(minDistance.toDouble()))
        } else {
            stringResource(R.string.track_logging_every, seconds)
        }
    )
    val writeMinutes = PreferenceUtil.gpxWriteIntervalMillis(app, prefs) / 60_000L
    StatRow(
        R.string.track_write_interval,
        if (writeMinutes > 0) stringResource(R.string.track_write_every_min, writeMinutes.toInt())
        else stringResource(R.string.track_write_every_point)
    )
    StatRow(
        R.string.track_segment_gap,
        stringResource(
            R.string.track_segment_gap_value,
            (PreferenceUtil.gpxSegmentGapMillis(app, prefs) / 1000L).toInt()
        )
    )
    StatRow(
        R.string.track_satellite_details,
        stringResource(if (PreferenceUtil.gpxIncludeSatellites(app, prefs)) R.string.track_on else R.string.track_off)
    )
}

@Composable
private fun TrackCard(@StringRes title: Int, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            content()
        }
    }
}

@Composable
private fun StatRow(@StringRes label: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = stringResource(label), fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

/**
 * A line chart of [entries]. Touching it selects the nearest point and shows its values
 * ([xText], [yText]) in a window above it; dragging moves the selection while the chart isn't
 * zoomed in (zoomed in, dragging pans), and a tap elsewhere or on the same point clears it.
 */
@Composable
private fun ChartCard(
    title: String,
    xLabel: String,
    entries: List<Entry>,
    xText: (Float) -> String,
    yText: (Float) -> String
) {
    val lineColor = MaterialTheme.colors.primary.toArgb()
    val textColor = MaterialTheme.colors.onSurface.toArgb()
    val surfaceColor = MaterialTheme.colors.surface.toArgb()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold
            )
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                factory = { context ->
                    LineChart(context).apply {
                        description.isEnabled = false
                        legend.isEnabled = false
                        setTouchEnabled(true)
                        setPinchZoom(true)
                        setScaleEnabled(true)
                        axisRight.isEnabled = false
                        xAxis.position = XAxis.XAxisPosition.BOTTOM
                        xAxis.setDrawGridLines(false)
                        xAxis.textColor = textColor
                        axisLeft.textColor = textColor
                        setNoDataTextColor(textColor)
                        isHighlightPerTapEnabled = true
                        isHighlightPerDragEnabled = true
                    }
                },
                update = { chart ->
                    // Keep the selected point while the recording adds new ones
                    val selectedX = chart.highlighted?.firstOrNull()?.x
                    val set = LineDataSet(entries, title).apply {
                        color = lineColor
                        lineWidth = 2f
                        setDrawCircles(false)
                        setDrawValues(false)
                        setDrawFilled(true)
                        fillColor = lineColor
                        fillAlpha = 40
                        isHighlightEnabled = true
                        highLightColor = textColor
                        highlightLineWidth = 1f
                        setDrawHorizontalHighlightIndicator(false)
                    }
                    chart.marker =
                        TrackChartMarker(chart.context, xText, yText, textColor, surfaceColor)
                    chart.data = LineData(set)
                    if (selectedX != null) chart.highlightValue(selectedX, 0, false)
                    chart.invalidate()
                }
            )
            Text(
                text = xLabel,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

private const val DASH = "–"

private fun percentPoints(value: Int) = String.format(Locale.getDefault(), "%d %%", value)

/** Time since the start of the track, from minutes on the x axis */
private fun elapsed(minutes: Float) = DateUtils.formatElapsedTime((minutes * 60).roundToLong())

private fun percent(share: Double) = String.format(Locale.getDefault(), "%.0f %%", share * 100)
