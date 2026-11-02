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
 * Samples are stored per signal in primitive ring buffers (12 bytes per sample) instead of objects,
 * so an hour at one sample per second stays at about 4 MB for 80 signals in view.
 *
 * Times are in milliseconds of a monotonic clock, passed in by the caller.
 */
class Cn0History(
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    data class Sample(val timeMillis: Long, val cn0DbHz: Float)

    /**
     * All samples of one signal, oldest first, as primitive arrays so that copying every signal
     * for the combined chart stays cheap
     */
    class Series(
        val gnssType: GnssType,
        val sbasType: SbasType,
        val svid: Int,
        val hasCarrierFrequency: Boolean,
        val carrierFrequencyHz: Double,
        val timesMillis: LongArray,
        val cn0DbHz: FloatArray,
    ) {
        val size: Int get() = timesMillis.size
    }

    private val signals = HashMap<String, SignalBuffer>()

    /** Adds the C/N0 of all [statuses] at [nowMillis] and drops samples older than the window */
    @Synchronized
    fun add(statuses: List<SatelliteStatus>, nowMillis: Long) {
        statuses.forEach { status ->
            if (status.cn0DbHz == SatelliteStatus.NO_DATA) return@forEach
            signals.getOrPut(key(status)) { SignalBuffer(status) }.add(nowMillis, status.cn0DbHz)
        }
        val oldest = nowMillis - windowMillis
        val iterator = signals.values.iterator()
        while (iterator.hasNext()) {
            val buffer = iterator.next()
            buffer.dropOlderThan(oldest)
            if (buffer.size == 0) iterator.remove()
        }
    }

    /** Samples of the signal of [status], oldest first */
    @Synchronized
    fun get(status: SatelliteStatus): List<Sample> {
        val buffer = signals[key(status)] ?: return emptyList()
        return List(buffer.size) { Sample(buffer.timeAt(it), buffer.valueAt(it)) }
    }

    /**
     * Samples of every signal still in the window, including signals that are no longer in view
     */
    @Synchronized
    fun all(): List<Series> = signals.values.map { it.toSeries() }

    @Synchronized
    fun clear() {
        signals.clear()
    }

    /** Growable ring buffer of (time, C/N0) pairs, oldest first */
    private class SignalBuffer(status: SatelliteStatus) {
        private val gnssType = status.gnssType
        private val sbasType = status.sbasType
        private val svid = status.svid
        private val hasCarrierFrequency = status.hasCarrierFrequency
        private val carrierFrequencyHz = status.carrierFrequencyHz

        private var times = LongArray(INITIAL_CAPACITY)
        private var values = FloatArray(INITIAL_CAPACITY)
        private var start = 0
        var size = 0
            private set

        fun add(timeMillis: Long, cn0: Float) {
            if (size == times.size) grow()
            val index = (start + size) % times.size
            times[index] = timeMillis
            values[index] = cn0
            size++
        }

        fun dropOlderThan(oldestMillis: Long) {
            while (size > 0 && times[start] < oldestMillis) {
                start = (start + 1) % times.size
                size--
            }
        }

        fun timeAt(i: Int): Long = times[(start + i) % times.size]

        fun valueAt(i: Int): Float = values[(start + i) % values.size]

        fun toSeries(): Series = Series(
            gnssType,
            sbasType,
            svid,
            hasCarrierFrequency,
            carrierFrequencyHz,
            LongArray(size) { timeAt(it) },
            FloatArray(size) { valueAt(it) },
        )

        private fun grow() {
            val newTimes = LongArray(times.size * 2)
            val newValues = FloatArray(values.size * 2)
            for (i in 0 until size) {
                newTimes[i] = timeAt(i)
                newValues[i] = valueAt(i)
            }
            times = newTimes
            values = newValues
            start = 0
        }
    }

    companion object {
        /**
         * One hour. At one sample per second that's 3600 samples per signal, about 4 MB for
         * 80 signals in view.
         */
        const val DEFAULT_WINDOW_MILLIS = 60 * 60_000L

        private const val INITIAL_CAPACITY = 64

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
