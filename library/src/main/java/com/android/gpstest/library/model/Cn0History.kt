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

import kotlin.math.roundToLong

/**
 * Keeps the C/N0 of each signal (satellite and carrier frequency) for the last [windowMillis], so
 * the Status screen can show how a signal's strength changed.
 *
 * Times are in milliseconds of a monotonic clock, passed in by the caller.
 */
class Cn0History(
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    data class Sample(val timeMillis: Long, val cn0DbHz: Float)

    private val samples = HashMap<String, ArrayDeque<Sample>>()

    /** Adds the C/N0 of all [statuses] at [nowMillis] and drops samples older than the window */
    @Synchronized
    fun add(statuses: List<SatelliteStatus>, nowMillis: Long) {
        statuses.forEach { status ->
            if (status.cn0DbHz == SatelliteStatus.NO_DATA) return@forEach
            samples.getOrPut(key(status)) { ArrayDeque() }
                .addLast(Sample(nowMillis, status.cn0DbHz))
        }
        val oldest = nowMillis - windowMillis
        val iterator = samples.values.iterator()
        while (iterator.hasNext()) {
            val deque = iterator.next()
            while (deque.isNotEmpty() && deque.first().timeMillis < oldest) {
                deque.removeFirst()
            }
            if (deque.isEmpty()) iterator.remove()
        }
    }

    /** Samples of the signal of [status], oldest first */
    @Synchronized
    fun get(status: SatelliteStatus): List<Sample> {
        return samples[key(status)]?.toList() ?: emptyList()
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    companion object {
        /**
         * 15 minutes. At one sample per second that's 900 samples per signal, roughly 3 MB for
         * 80 signals in view.
         */
        const val DEFAULT_WINDOW_MILLIS = 15 * 60_000L

        /**
         * Identifies a signal: constellation, SBAS system, satellite ID and carrier frequency,
         * rounded to 100 kHz so that small variations in the reported frequency don't split it
         */
        fun key(status: SatelliteStatus): String {
            val band = if (status.hasCarrierFrequency) {
                (status.carrierFrequencyHz / 1e5).roundToLong().toString()
            } else {
                "-"
            }
            return "${status.gnssType.name}_${status.sbasType.name}_${status.svid}_$band"
        }
    }
}
