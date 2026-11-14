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
package com.android.gpstest.ui.status

import com.android.gpstest.library.model.Cn0History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Cn0AverageTest {

    @Test
    fun averagesOnlyTheWindow() {
        val samples = listOf(
            Cn0History.Sample(0, 10f), // older than the window
            Cn0History.Sample(50_000, 30f),
            Cn0History.Sample(60_000, 40f),
        )
        assertEquals(35f, windowAverage(samples, newestMillis = 60_000, windowMillis = 30_000)!!, 0.001f)
        assertEquals(80f / 3, windowAverage(samples, newestMillis = 60_000, windowMillis = 60_000)!!, 0.001f)
    }

    @Test
    fun noSamplesInTheWindowHasNoAverage() {
        assertNull(windowAverage(emptyList(), 60_000, 30_000))
        assertNull(windowAverage(listOf(Cn0History.Sample(0, 20f)), 60_000, 30_000))
    }
}
