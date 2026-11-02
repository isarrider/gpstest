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
package com.android.gpstest.io

import android.os.SystemClock
import com.android.gpstest.library.model.DilutionOfPrecision
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.model.SatelliteStatus
import com.android.gpstest.library.model.SbasType
import com.android.gpstest.library.util.CarrierFreqUtils
import com.android.gpstest.library.util.NmeaUtils

/**
 * GNSS context at the moment of a fix, attached to each GPX track point: the latest satellite
 * status, DOP and fix quality from NMEA, and the compass heading.
 */
data class GpxFixContext(
    val satellites: List<SatelliteStatus>,
    val dop: DilutionOfPrecision?,
    /** NMEA GGA fix quality (0 invalid, 1 GNSS, 2 DGNSS/SBAS, 3 PPS, 4/5 RTK, 6 estimated), or null */
    val ggaQuality: Int?,
    /** Age of the differential correction data in seconds, or null */
    val ageOfDgpsData: Double?,
    /** Differential reference station ID, or null */
    val dgpsId: Int?,
    /** Compass heading in degrees, or null */
    val heading: Double?,
    /** Altitude above mean sea level from NMEA GGA, or null */
    val mslAltitude: Double? = null,
    /** Geoid separation (geoid above WGS84 ellipsoid) from NMEA GGA, or null */
    val geoidSeparation: Double? = null
) {
    /** Signals used in the fix (one satellite can contribute several signals on different bands) */
    val usedSignals: List<SatelliteStatus> by lazy { satellites.filter { it.usedInFix } }

    /** Number of distinct satellites used in the fix */
    val satellitesUsed: Int by lazy { usedSignals.map { it.gnssType to it.svid }.toSet().size }

    /** True if an SBAS satellite (e.g. EGNOS) contributes to the fix */
    val sbasUsed: Boolean by lazy { usedSignals.any { it.gnssType == GnssType.SBAS } }

    /** Bands of the signals used in the fix, e.g. L1, L5, E1, E5a, sorted */
    val bandsUsed: List<String> by lazy {
        usedSignals.map { CarrierFreqUtils.getCarrierFrequencyLabel(it) }
            .filter { it != CarrierFreqUtils.CF_UNKNOWN && it != CarrierFreqUtils.CF_UNSUPPORTED }
            .toSortedSet()
            .toList()
    }

    /** True if signals from more than one band are used */
    val dualFrequency: Boolean by lazy { bandsUsed.size > 1 }

    /** Most common SBAS system among the used SBAS satellites, if any */
    val sbasSystem: SbasType? by lazy {
        usedSignals.filter { it.gnssType == GnssType.SBAS }
            .groupingBy { it.sbasType }.eachCount().maxByOrNull { it.value }?.key
    }

    companion object {
        val EMPTY = GpxFixContext(emptyList(), null, null, null, null, null)
    }
}

/**
 * Collects the inputs for [GpxFixContext] from the service's flows. NMEA-derived values older than
 * [MAX_NMEA_AGE_MS] are ignored so a stale GSA/GGA isn't attached to a later fix.
 */
class GpxFixContextCollector {
    @Volatile
    private var satellites: List<SatelliteStatus> = emptyList()
    @Volatile
    private var dop: DilutionOfPrecision? = null
    @Volatile
    private var dopTime = 0L
    @Volatile
    private var gga: Gga? = null
    @Volatile
    private var ggaTime = 0L
    @Volatile
    private var heading: Double? = null
    @Volatile
    private var headingTime = 0L

    private data class Gga(
        val quality: Int?,
        val msl: Double?,
        val geoidSep: Double?,
        val age: Double?,
        val stationId: Int?
    )

    fun onSatellites(statuses: List<SatelliteStatus>) {
        satellites = statuses
    }

    fun onNmea(message: String) {
        if (message.startsWith("\$GNGSA") || message.startsWith("\$GPGSA")) {
            // With several constellations there is one GSA per system, all carrying the combined DOP
            val d = NmeaUtils.getDop(message) ?: return
            dop = d
            dopTime = SystemClock.elapsedRealtime()
        } else if (message.length > 6 && message.startsWith("GGA", 3) && message[0] == '$') {
            gga = parseGga(message) ?: return
            ggaTime = SystemClock.elapsedRealtime()
        }
    }

    fun onHeading(degrees: Double) {
        heading = degrees
        headingTime = SystemClock.elapsedRealtime()
    }

    fun snapshot(): GpxFixContext {
        val now = SystemClock.elapsedRealtime()
        val g = if (now - ggaTime <= MAX_NMEA_AGE_MS) gga else null
        return GpxFixContext(
            satellites = satellites,
            dop = if (now - dopTime <= MAX_NMEA_AGE_MS) dop else null,
            ggaQuality = g?.quality,
            ageOfDgpsData = g?.age,
            dgpsId = g?.stationId,
            heading = if (now - headingTime <= MAX_NMEA_AGE_MS) heading else null,
            mslAltitude = g?.msl,
            geoidSeparation = g?.geoidSep
        )
    }

    private fun parseGga(message: String): Gga? {
        // $xxGGA,time,lat,N,lon,E,quality,numSats,hdop,alt,M,geoidSep,M,ageOfDgps,stationId*cs
        val body = message.substringBefore('*')
        val t = body.split(',')
        if (t.size < 7) return null
        return Gga(
            quality = t.getOrNull(6)?.toIntOrNull(),
            msl = t.getOrNull(9)?.toDoubleOrNull(),
            geoidSep = t.getOrNull(11)?.toDoubleOrNull(),
            age = t.getOrNull(13)?.toDoubleOrNull(),
            stationId = t.getOrNull(14)?.toIntOrNull()
        )
    }

    companion object {
        private const val MAX_NMEA_AGE_MS = 3_000L
    }
}
