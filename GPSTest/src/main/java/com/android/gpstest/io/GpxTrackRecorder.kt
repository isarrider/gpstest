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

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import com.android.gpstest.library.util.PreferenceUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Records GPS fixes into a GPX track between an explicit [start] and [stop].
 *
 * - A new file (one track) is created only by [start]. Recording state lives in memory, so if the
 *   process is killed the recording simply ends there and the user has to press Start again.
 * - Fixes are buffered in memory and written in one batch every N minutes (user setting, 0 = every
 *   fix) to limit flash writes. The buffer is also written on [stop] and on [flush] (low memory,
 *   service shutdown).
 * - A gap between fixes longer than the configured segment gap starts a new track segment.
 */
class GpxTrackRecorder(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    data class State(
        val isRecording: Boolean = false,
        /** Wall clock start of the recording, for display */
        val startTime: Long = 0L,
        val pointCount: Int = 0,
        /** Where the file is, for display */
        val location: String = ""
    )

    private val lock = Any()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var writer: GpxTrackWriter? = null
    private val buffer = ArrayList<GpxTrackWriter.Point>()
    private var lastFixTime = Long.MIN_VALUE
    private var lastFlushElapsed = 0L
    private var pointCount = 0

    val isRecording: Boolean
        get() = state.value.isRecording

    /**
     * Starts a new track in a new file.
     * @return false if the file could not be created
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (writer != null) return true
            val now = Date()
            val w = GpxTrackWriter(context, PreferenceUtil.gpxIncludeSatellites(context, prefs))
            if (!w.create(now)) {
                return false
            }
            writer = w
            buffer.clear()
            lastFixTime = Long.MIN_VALUE
            lastFlushElapsed = SystemClock.elapsedRealtime()
            pointCount = 0
            _state.value = State(true, now.time, 0, w.displayLocation())
            Log.d(TAG, "Recording GPX track to ${w.displayLocation()}")
            return true
        }
    }

    /**
     * Writes any buffered points and ends the track. The file is already valid, nothing else to close.
     */
    fun stop() {
        synchronized(lock) {
            if (writer == null) return
            // The final batch is queued on the single IO thread and written after any earlier ones
            flushLocked()
            val w = writer
            writer = null
            Log.d(TAG, "Stopped GPX track ${w?.displayLocation()}")
            _state.value = State()
        }
    }

    /**
     * Adds a fix to the track, with the GNSS context at that moment. Only GPS_PROVIDER fixes are recorded.
     */
    fun onLocation(location: Location, fix: GpxFixContext) {
        synchronized(lock) {
            if (writer == null || LocationManager.GPS_PROVIDER != location.provider) return
            val time = location.time
            if (time <= lastFixTime) return // duplicate or out of order
            val newSegment = lastFixTime == Long.MIN_VALUE ||
                    time - lastFixTime > PreferenceUtil.gpxSegmentGapMillis(context, prefs)
            lastFixTime = time
            buffer.add(GpxTrackWriter.Point(Location(location), fix, newSegment))
            pointCount++
            _state.value = _state.value.copy(pointCount = pointCount)
            maybeFlushLocked()
        }
    }

    /**
     * Called periodically so that buffered points are written even when no new fixes arrive.
     */
    fun tick() {
        synchronized(lock) {
            if (writer != null) maybeFlushLocked()
        }
    }

    /** Writes the buffer now, e.g. on low memory or service shutdown */
    fun flush() {
        synchronized(lock) {
            if (writer != null) flushLocked()
        }
    }

    private fun maybeFlushLocked() {
        val interval = PreferenceUtil.gpxWriteIntervalMillis(context, prefs)
        if (interval == 0L || SystemClock.elapsedRealtime() - lastFlushElapsed >= interval) {
            flushLocked()
        }
    }

    private fun flushLocked() {
        lastFlushElapsed = SystemClock.elapsedRealtime()
        val w = writer ?: return
        if (buffer.isEmpty()) return
        val batch = ArrayList(buffer)
        buffer.clear()
        io.execute {
            try {
                w.append(batch)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to write GPX points, keeping them for the next write", e)
                synchronized(lock) {
                    if (writer === w) buffer.addAll(0, batch)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GpxTrackRecorder"

        private val _state = MutableStateFlow(State())

        /** Recording state, observed by the UI and the notification */
        val state: StateFlow<State> = _state
    }
}
