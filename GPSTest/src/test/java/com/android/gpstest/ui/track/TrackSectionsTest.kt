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

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSectionsTest {

    @Test
    fun nothingSaved_defaultOrderAllShown() {
        val settings = parseTrackSections(null)
        assertEquals(TrackSection.values().toList(), settings.map { it.section })
        assertEquals(true, settings.all { it.shown })
    }

    @Test
    fun savedOrderAndHidden() {
        val settings = parseTrackSections("SPEED,SUMMARY,-GNSS")
        assertEquals(TrackSection.SPEED, settings[0].section)
        assertEquals(TrackSection.SUMMARY, settings[1].section)
        assertEquals(TrackSectionSetting(TrackSection.GNSS, false), settings[2])
        // The rest follows in the default order, shown
        assertEquals(TrackSection.ELEVATION, settings[3].section)
        assertEquals(TrackSection.values().size, settings.size)
        assertEquals(1, settings.count { !it.shown })
    }

    @Test
    fun unknownAndDuplicateNames_skipped() {
        val settings = parseTrackSections("FOO, SPEED ,SPEED,-")
        assertEquals(TrackSection.SPEED, settings[0].section)
        assertEquals(TrackSection.values().size, settings.size)
    }

    @Test
    fun roundTrip() {
        val settings = parseTrackSections("CHART_SPEED,-FILE,SUMMARY")
        assertEquals(settings, parseTrackSections(formatTrackSections(settings)))
        assertEquals("CHART_SPEED,-FILE,SUMMARY", formatTrackSections(settings).split(',').take(3).joinToString(","))
    }
}
