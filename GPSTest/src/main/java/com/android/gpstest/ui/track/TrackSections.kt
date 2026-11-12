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
package com.android.gpstest.ui.track

/** The cards of the Track screen, in their default order */
enum class TrackSection {
    SUMMARY,
    SPEED,
    ELEVATION,
    GNSS,
    BATTERY,
    FILE,
    CHART_DISTANCE,
    SPLITS,
    CHART_ELEVATION,
    CHART_SPEED,
    CHART_SPEED_DISTRIBUTION,
    CHART_SATELLITES,
    CHART_BATTERY
}

/** Position and visibility of one Track screen card */
data class TrackSectionSetting(val section: TrackSection, val shown: Boolean)

/**
 * Reads the card order saved by the "Arrange cards" dialog: the card names in order, hidden ones
 * with a leading "-", e.g. "SPEED,SUMMARY,-GNSS". Unknown names are skipped, and cards that
 * aren't in the saved value (e.g. added in a later version) follow in their default order, shown.
 */
fun parseTrackSections(saved: String?): List<TrackSectionSetting> {
    val result = mutableListOf<TrackSectionSetting>()
    saved.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
        val hidden = entry.startsWith("-")
        val section = TrackSection.values().firstOrNull { it.name == entry.removePrefix("-") }
        if (section != null && result.none { it.section == section }) {
            result.add(TrackSectionSetting(section, !hidden))
        }
    }
    TrackSection.values()
        .filter { s -> result.none { it.section == s } }
        .forEach { result.add(TrackSectionSetting(it, true)) }
    return result
}

fun formatTrackSections(settings: List<TrackSectionSetting>): String =
    settings.joinToString(",") { (if (it.shown) "" else "-") + it.section.name }
