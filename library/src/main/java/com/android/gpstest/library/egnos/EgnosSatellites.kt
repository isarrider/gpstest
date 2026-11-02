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
package com.android.gpstest.library.egnos

/**
 * The geostationary satellites that carry (or carried) the EGNOS signal, by SBAS PRN.
 *
 * Which of them is operational changes over time (e.g. PRN 121 became operational and PRN 123
 * moved to test in 2025), so the operational state isn't hard-coded here: a GEO in test broadcasts
 * Message Type 0, which [EgnosAccumulator] reports as "don't use".
 */
object EgnosSatellites {

    data class Geo(
        val prn: Int,
        val name: String,
        /** Orbital position, e.g. "5° W" */
        val position: String,
        /** No longer broadcasts EGNOS */
        val retired: Boolean = false
    )

    private val geos = listOf(
        Geo(120, "Inmarsat 3F2", "15.5° W", retired = true),
        Geo(121, "Eutelsat 5WB", "5° W"),
        Geo(123, "Astra 5B", "31.5° E"),
        Geo(124, "Artemis", "21.5° E", retired = true),
        Geo(126, "Inmarsat 4F2", "63.9° E"),
        Geo(136, "SES-5", "5° E")
    ).associateBy { it.prn }

    /** All PRNs that are (or were) EGNOS GEOs */
    val prns: Set<Int> = geos.keys

    fun isEgnos(prn: Int): Boolean = prn in geos

    fun geo(prn: Int): Geo? = geos[prn]
}
