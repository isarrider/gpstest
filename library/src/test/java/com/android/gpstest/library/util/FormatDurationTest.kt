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
package com.android.gpstest.library.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {

    @Test
    fun seconds() {
        assertEquals("0 sec", FormatUtils.formatDuration(0))
        assertEquals("0 sec", FormatUtils.formatDuration(999))
        assertEquals("45 sec", FormatUtils.formatDuration(45_000))
        assertEquals("59 sec", FormatUtils.formatDuration(59_999))
    }

    @Test
    fun minutes() {
        assertEquals("1 min 0 sec", FormatUtils.formatDuration(60_000))
        assertEquals("13 min 45 sec", FormatUtils.formatDuration(825_000))
        assertEquals("59 min 59 sec", FormatUtils.formatDuration(3_599_000))
    }

    @Test
    fun hours() {
        assertEquals("1 h 0 min 0 sec", FormatUtils.formatDuration(3_600_000))
        assertEquals("1 h 2 min 3 sec", FormatUtils.formatDuration(3_723_000))
        assertEquals("26 h 0 min 5 sec", FormatUtils.formatDuration(93_605_000))
    }

    @Test
    fun negativeIsZero() {
        assertEquals("0 sec", FormatUtils.formatDuration(-5_000))
    }
}
