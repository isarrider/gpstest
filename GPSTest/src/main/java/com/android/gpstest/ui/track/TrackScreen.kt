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
import com.android.gpstest.R
import com.android.gpstest.io.GpxTrackRecorder
import com.android.gpstest.io.GpxTrackStats
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
            StatRow(R.string.track_distance, units.distance(s.distanceMeters))
        }

        TrackCard(R.string.track_section_speed) {
            // The current speed only means something while recording
            if (state.isRecording) {
                StatRow(R.string.track_speed_current, s.currentSpeed?.let { units.speed(it.toDouble()) } ?: DASH)
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
            StatRow(R.string.track_avg_sats, s.avgSatellites?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: DASH)
            StatRow(R.string.track_avg_hdop, s.avgHdop?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: DASH)
            StatRow(R.string.track_dual_freq, s.dualFrequencyShare?.let { percent(it) } ?: DASH)
            StatRow(R.string.track_sbas, s.sbasShare?.let { percent(it) } ?: DASH)
        }

        TrackCard(R.string.track_section_file) {
            StatRow(R.string.track_points, s.points.toString())
            StatRow(R.string.track_segments, s.segments.toString())
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
            ChartCard(
                stringResource(R.string.track_chart_distance, units.distanceAxisUnit),
                stringResource(R.string.track_chart_axis_minutes),
                s.samples.map { Entry(it.seconds / 60f, units.distanceAxis(it.distanceMeters)) }
            )
            val elevation = s.samples.mapNotNull { p -> p.elevation?.let { Entry(units.distanceAxis(p.distanceMeters), units.heightValue(it)) } }
            if (elevation.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_elevation, units.heightUnit),
                    stringResource(R.string.track_chart_axis_distance, units.distanceAxisUnit),
                    elevation
                )
            }
            val speed = s.samples.mapNotNull { p -> p.speed?.let { Entry(p.seconds / 60f, units.speedValue(it)) } }
            if (speed.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_speed, units.speedUnit),
                    stringResource(R.string.track_chart_axis_minutes),
                    speed
                )
            }
            val sats = s.samples.filter { it.satellites > 0 }.map { Entry(it.seconds / 60f, it.satellites.toFloat()) }
            if (sats.size >= 2) {
                ChartCard(
                    stringResource(R.string.track_chart_satellites),
                    stringResource(R.string.track_chart_axis_minutes),
                    sats
                )
            }
        }
    }
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

@Composable
private fun ChartCard(title: String, xLabel: String, entries: List<Entry>) {
    val lineColor = MaterialTheme.colors.primary.toArgb()
    val textColor = MaterialTheme.colors.onSurface.toArgb()
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
                    }
                },
                update = { chart ->
                    val set = LineDataSet(entries, title).apply {
                        color = lineColor
                        lineWidth = 2f
                        setDrawCircles(false)
                        setDrawValues(false)
                        setDrawFilled(true)
                        fillColor = lineColor
                        fillAlpha = 40
                        isHighlightEnabled = false
                    }
                    chart.data = LineData(set)
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

private fun percent(share: Double) = String.format(Locale.getDefault(), "%.0f %%", share * 100)
