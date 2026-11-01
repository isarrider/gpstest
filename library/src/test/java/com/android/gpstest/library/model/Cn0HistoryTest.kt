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
package com.android.gpstest.library.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cn0HistoryTest {

    private fun signal(
        gnssType: GnssType = GnssType.GALILEO,
        svid: Int = 6,
        cn0: Float = 40f,
        frequencyHz: Double? = 1575.42e6,
    ): SatelliteStatus {
        val status = SatelliteStatus(svid, gnssType, cn0, true, true, true, 45f, 90f)
        if (frequencyHz != null) {
            status.hasCarrierFrequency = true
            status.carrierFrequencyHz = frequencyHz
        }
        return status
    }

    @Test
    fun keepsSamplesPerSignalInOrder() {
        val history = Cn0History()
        history.add(listOf(signal(cn0 = 40f), signal(cn0 = 28f, frequencyHz = 1176.45e6)), 1_000)
        history.add(listOf(signal(cn0 = 41f), signal(cn0 = 29f, frequencyHz = 1176.45e6)), 2_000)

        assertEquals(
            listOf(Cn0History.Sample(1_000, 40f), Cn0History.Sample(2_000, 41f)),
            history.get(signal())
        )
        assertEquals(
            listOf(29f),
            history.get(signal(frequencyHz = 1176.45e6)).drop(1).map { it.cn0DbHz }
        )
    }

    @Test
    fun dropsSamplesOlderThanTheWindow() {
        val history = Cn0History(windowMillis = 10_000)
        history.add(listOf(signal(cn0 = 30f)), 0)
        history.add(listOf(signal(cn0 = 31f)), 5_000)
        history.add(listOf(signal(cn0 = 32f)), 12_000)

        assertEquals(listOf(31f, 32f), history.get(signal()).map { it.cn0DbHz })
    }

    @Test
    fun forgetsSignalsThatDisappeared() {
        val history = Cn0History(windowMillis = 10_000)
        history.add(listOf(signal(svid = 21)), 0)
        history.add(listOf(signal(svid = 6)), 20_000)

        assertTrue(history.get(signal(svid = 21)).isEmpty())
        assertEquals(1, history.get(signal(svid = 6)).size)
    }

    @Test
    fun skipsSamplesWithoutCn0() {
        val history = Cn0History()
        history.add(listOf(signal(cn0 = SatelliteStatus.NO_DATA)), 1_000)
        assertTrue(history.get(signal()).isEmpty())
    }

    @Test
    fun keySeparatesConstellationsAndBandsButNotSmallFrequencyChanges() {
        assertNotEquals(
            Cn0History.key(signal(gnssType = GnssType.GALILEO)),
            Cn0History.key(signal(gnssType = GnssType.BEIDOU))
        )
        assertNotEquals(
            Cn0History.key(signal(frequencyHz = 1575.42e6)),
            Cn0History.key(signal(frequencyHz = 1176.45e6))
        )
        assertEquals(
            Cn0History.key(signal(frequencyHz = 1575.42e6)),
            Cn0History.key(signal(frequencyHz = 1575.4213e6))
        )
        assertNotEquals(
            Cn0History.key(signal(frequencyHz = null)),
            Cn0History.key(signal())
        )
    }
}
