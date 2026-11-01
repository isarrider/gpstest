/*
 * Copyright (C) 2026 The GPSTest contributors
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

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.gpstest.R
import com.android.gpstest.library.data.EgnosConnection
import com.android.gpstest.library.data.EgnosUiState
import com.android.gpstest.library.egnos.EgnosSatellites
import com.android.gpstest.library.egnos.EgnosSnapshot
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.model.SbasType
import kotlinx.coroutines.delay

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFB26A00)
private val Gray = Color(0xFF757575)

private val greenText: Color
    @Composable get() = if (MaterialTheme.colors.isLight) Green else Color(0xFF81C784)

private val amberText: Color
    @Composable get() = if (MaterialTheme.colors.isLight) Amber else Color(0xFFFFB74D)

/** No new message for this long: the data is shown as stale */
private const val STALE_MILLIS = 30_000L

/** An EGNOS GEO tracked by the phone, with all its signals */
private data class TrackedGeo(val prn: Int, val cn0: Float?, val usedInFix: Boolean)

/**
 * EGNOS on the Status screen: the EGNOS GEOs the phone tracks and whether it uses them, and, if
 * EDAS is set up, the EGNOS messages received over the internet (corrections, integrity,
 * ionosphere). Shown when the phone tracks an EGNOS GEO or EDAS is turned on. Tap to expand.
 */
@Composable
fun EgnosCard(state: EgnosUiState, sbasStatuses: List<SatelliteStatus>) {
    val geos = sbasStatuses
        .filter { it.sbasType == SbasType.EGNOS }
        .groupBy { it.svid }
        .map { (prn, signals) ->
            TrackedGeo(
                prn = prn,
                cn0 = signals.map { it.cn0DbHz }.filter { it != SatelliteStatus.NO_DATA }.maxOrNull(),
                usedInFix = signals.any { it.usedInFix }
            )
        }
        .sortedBy { it.prn }
    if (!state.enabled && geos.isEmpty()) return

    var expanded by remember { mutableStateOf(egnosCardExpanded) }
    // Ticks every second so the age of the last message stays current
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val ageMillis = state.lastMessageElapsedMillis?.let { now - it }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(5.dp)
            .clickable {
                expanded = !expanded
                egnosCardExpanded = expanded
            },
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.egnos_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                ConnectionChip(state, ageMillis)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = summary(state, geos) + if (expanded) "  ▴" else "  ▾",
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            if (expanded) {
                Geos(geos)
                when (state.connection) {
                    EgnosConnection.OFF -> SmallText(stringResource(R.string.egnos_off_hint))
                    EgnosConnection.NOT_CONFIGURED -> SmallText(stringResource(R.string.egnos_setup_hint))
                    else -> Messages(state, ageMillis)
                }
                SmallText(stringResource(R.string.egnos_explanation), FontStyle.Italic)
            }
        }
    }
}

/** Remembers whether the card is expanded while the app runs */
private var egnosCardExpanded = true

@Composable
private fun summary(state: EgnosUiState, geos: List<TrackedGeo>): String {
    val parts = mutableListOf<String>()
    if (geos.isNotEmpty()) {
        parts.add(stringResource(R.string.egnos_summary_geos, geos.size, geos.count { it.usedInFix }))
    }
    if (state.connection == EgnosConnection.LIVE && state.snapshot.hasMask) {
        parts.add(
            stringResource(R.string.egnos_summary_corrected, state.snapshot.usableSatellites.size)
        )
    }
    return parts.joinToString(", ")
}

@Composable
private fun ConnectionChip(state: EgnosUiState, ageMillis: Long?) {
    val (color, textId) = when (state.connection) {
        EgnosConnection.OFF -> Pair(Gray, R.string.egnos_chip_on_device)
        EgnosConnection.NOT_CONFIGURED -> Pair(Amber, R.string.egnos_chip_setup)
        EgnosConnection.IDLE -> Pair(Gray, R.string.egnos_chip_idle)
        EgnosConnection.CONNECTING -> Pair(Amber, R.string.egnos_chip_connecting)
        EgnosConnection.AUTH_FAILED -> Pair(MaterialTheme.colors.error, R.string.egnos_chip_login_failed)
        EgnosConnection.ERROR -> Pair(MaterialTheme.colors.error, R.string.egnos_chip_offline)
        EgnosConnection.LIVE -> when {
            state.snapshot.dontUse -> Pair(MaterialTheme.colors.error, R.string.egnos_chip_dont_use)
            ageMillis == null || ageMillis > STALE_MILLIS -> Pair(Amber, R.string.egnos_chip_stale)
            else -> Pair(Green, R.string.egnos_chip_live)
        }
    }
    Text(
        text = stringResource(textId),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(start = 6.dp, end = 6.dp, top = 1.dp, bottom = 1.dp)
    )
}

/** One line per EGNOS GEO the phone tracks: name, position, C/N0, used in fix */
@Composable
private fun Geos(geos: List<TrackedGeo>) {
    if (geos.isEmpty()) {
        DetailRow(R.string.egnos_label_geos, stringResource(R.string.egnos_geos_none))
        return
    }
    geos.forEachIndexed { i, tracked ->
        val geo = EgnosSatellites.geo(tracked.prn)
        val name = geo?.let { "${tracked.prn} ${it.name} (${it.position})" }
            ?: "${tracked.prn} " + stringResource(R.string.egnos_geo_unknown_name)
        val cn0 = tracked.cn0?.let { stringResource(R.string.egnos_geo_cn0, it) }
            ?: stringResource(R.string.egnos_geo_no_cn0)
        val used = stringResource(
            if (tracked.usedInFix) R.string.egnos_geo_used else R.string.egnos_geo_not_used
        )
        DetailRow(
            label = if (i == 0) R.string.egnos_label_geos else null,
            value = stringResource(R.string.egnos_geo_value, name, cn0, used),
            color = if (tracked.usedInFix) greenText else Color.Unspecified
        )
    }
}

/** What has been received from EDAS */
@Composable
private fun Messages(state: EgnosUiState, ageMillis: Long?) {
    val s = state.snapshot
    val connectionText = stringResource(
        when (state.connection) {
            EgnosConnection.OFF -> R.string.egnos_state_off
            EgnosConnection.NOT_CONFIGURED -> R.string.egnos_state_not_configured
            EgnosConnection.IDLE -> R.string.egnos_state_idle
            EgnosConnection.CONNECTING -> R.string.egnos_state_connecting
            EgnosConnection.LIVE -> R.string.egnos_state_live
            EgnosConnection.AUTH_FAILED -> R.string.egnos_state_auth_failed
            EgnosConnection.ERROR -> R.string.egnos_state_error
        }
    ) + (state.detail?.let { " ($it)" } ?: "")
    DetailRow(
        R.string.egnos_label_connection,
        stringResource(R.string.egnos_connection_value, state.server, connectionText),
        color = when (state.connection) {
            EgnosConnection.AUTH_FAILED, EgnosConnection.ERROR -> MaterialTheme.colors.error
            else -> Color.Unspecified
        }
    )
    if (s.dontUse) {
        Text(
            text = stringResource(R.string.egnos_dont_use_warning),
            fontSize = 13.sp,
            color = MaterialTheme.colors.error,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    DetailRow(
        R.string.egnos_label_messages,
        if (s.messages == 0L && s.crcErrors == 0L) {
            stringResource(R.string.egnos_messages_none)
        } else {
            stringResource(
                R.string.egnos_messages_value,
                s.messages,
                (ageMillis ?: 0L) / 1000,
                s.crcErrors
            )
        }
    )
    if (s.typeCounts.isNotEmpty()) {
        DetailRow(
            R.string.egnos_label_types,
            s.typeCounts.entries.joinToString(" ") { "MT${it.key}×${it.value}" }
        )
    }
    Corrections(s)
}

@Composable
private fun Corrections(s: EgnosSnapshot) {
    val waiting = stringResource(R.string.egnos_value_waiting)
    val none = stringResource(R.string.egnos_value_none)
    if (!s.hasMask) {
        DetailRow(R.string.egnos_label_mask, waiting)
    } else {
        DetailRow(
            R.string.egnos_label_mask,
            stringResource(R.string.egnos_mask_value, s.iodp ?: 0, s.satellites.size)
        )
        val usable = s.usableSatellites
        DetailRow(
            R.string.egnos_label_corrected,
            usable.joinToString(" ") { it.label }.ifEmpty { none },
            color = if (usable.isNotEmpty()) greenText else Color.Unspecified
        )
        val notMonitored = s.satellites.filter { it.notMonitored }
        if (notMonitored.isNotEmpty()) {
            DetailRow(
                R.string.egnos_label_not_monitored,
                notMonitored.joinToString(" ") { it.label },
                color = amberText
            )
        }
        val dontUse = s.satellites.filter { it.doNotUse }
        if (dontUse.isNotEmpty()) {
            DetailRow(
                R.string.egnos_label_dont_use,
                dontUse.joinToString(" ") { it.label },
                color = MaterialTheme.colors.error
            )
        }
        DetailRow(
            R.string.egnos_label_long_term,
            stringResource(
                R.string.egnos_long_term_value,
                s.satellites.count { it.hasCurrentLongTerm }
            )
        )
    }
    DetailRow(
        R.string.egnos_label_iono,
        if (s.igpsInMask == 0) {
            waiting
        } else {
            stringResource(R.string.egnos_iono_value, s.igpsMonitored, s.igpsInMask, s.igpBands)
        }
    )
    if (s.geoAlmanac.isNotEmpty()) {
        val ranging = stringResource(R.string.egnos_almanac_ranging)
        val noRanging = stringResource(R.string.egnos_almanac_no_ranging)
        DetailRow(
            R.string.egnos_label_almanac,
            // map is inline, so stringResource can be called in it (unlike joinToString)
            s.geoAlmanac.map {
                stringResource(R.string.egnos_almanac_entry, it.prn, if (it.ranging) ranging else noRanging)
            }.joinToString(", ")
        )
    }
}

@Composable
private fun DetailRow(@StringRes label: Int?, value: String, color: Color = Color.Unspecified) {
    Row(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = label?.let { stringResource(it) } ?: "",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.defaultMinSize(minWidth = 110.dp)
        )
        Text(text = value, fontSize = 13.sp, color = color)
    }
}

@Composable
private fun SmallText(text: String, fontStyle: FontStyle = FontStyle.Normal) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontStyle = fontStyle,
        modifier = Modifier.padding(top = 6.dp)
    )
}
