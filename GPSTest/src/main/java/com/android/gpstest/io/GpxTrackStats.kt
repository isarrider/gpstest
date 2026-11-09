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

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Immutable summary of a GPX track, shown on the Track screen.
 */
data class GpxTrackStats(
    val fileLocation: String,
    /** File name, e.g. gpstest_track_2026-11-02_10-00-00.gpx */
    val fileName: String,
    /** content:// URI for sharing, or null */
    val fileUri: String?,
    val startTime: Long,
    /** Time of the latest fix, or [startTime] if there is none yet */
    val lastFixTime: Long,
    val points: Int,
    val segments: Int,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val maxSpeed: Float?,
    /** Speed of the latest fix, or null if it had none */
    val currentSpeed: Float? = null,
    val currentElevation: Double?,
    val minElevation: Double?,
    val maxElevation: Double?,
    val ascentMeters: Double,
    val descentMeters: Double,
    val avgSatellites: Double?,
    val avgHdop: Double?,
    /** Share of points (0..1) with signals on more than one band */
    val dualFrequencyShare: Double?,
    /** Share of points (0..1) with SBAS corrections (e.g. EGNOS) */
    val sbasShare: Double?,
    /** Battery charge (%) at the first point, or null if unknown */
    val batteryStart: Int? = null,
    /** Battery charge (%) at the latest point, or null if unknown */
    val batteryLast: Int? = null,
    /** True if the phone was charging at any point of the track */
    val batteryCharged: Boolean = false,
    /** Points with OSNMA data and at least one Galileo satellite used in the fix */
    val osnmaPoints: Int = 0,
    /** Of [osnmaPoints], those where every Galileo satellite used was authenticated */
    val osnmaAllAuthenticated: Int = 0,
    /** Points with at least one Galileo satellite used whose OSNMA tags failed */
    val osnmaFailedPoints: Int = 0,
    /** Downsampled time series for the charts */
    val samples: List<Sample>
) {
    data class Sample(
        /** Seconds since the start of the track */
        val seconds: Float,
        val distanceMeters: Float,
        val elevation: Float?,
        val speed: Float?,
        val satellites: Int,
        /** Battery charge (%), or null if unknown */
        val battery: Int? = null
    )

    val durationMillis: Long get() = max(0L, lastFixTime - startTime)

    val averageMovingSpeed: Double?
        get() = if (movingTimeMillis > 0) distanceMeters / (movingTimeMillis / 1000.0) else null

    /** Distance over the whole duration, stops included */
    val averageSpeed: Double?
        get() = if (durationMillis > 0) distanceMeters / (durationMillis / 1000.0) else null

    /** Battery percentage points used, or null if unknown or the phone was charging */
    val batteryUsed: Int?
        get() = if (batteryStart != null && batteryLast != null && !batteryCharged) batteryStart - batteryLast else null

    /** Battery percentage points used per hour, once the track is long enough for a useful value */
    val batteryPerHour: Double?
        get() {
            val used = batteryUsed ?: return null
            if (durationMillis < MIN_BATTERY_RATE_MILLIS) return null
            return used / (durationMillis / 3_600_000.0)
        }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1)
        o.put("file", fileLocation)
        o.put("name", fileName)
        o.putOpt("uri", fileUri)
        o.put("start", startTime)
        o.put("last", lastFixTime)
        o.put("points", points)
        o.put("segments", segments)
        o.put("distance", distanceMeters)
        o.put("moving", movingTimeMillis)
        o.putOpt("maxSpeed", maxSpeed?.toDouble())
        o.putOpt("speed", currentSpeed?.toDouble())
        o.putOpt("ele", currentElevation)
        o.putOpt("minEle", minElevation)
        o.putOpt("maxEle", maxElevation)
        o.put("ascent", ascentMeters)
        o.put("descent", descentMeters)
        o.putOpt("sats", avgSatellites)
        o.putOpt("hdop", avgHdop)
        o.putOpt("dual", dualFrequencyShare)
        o.putOpt("sbas", sbasShare)
        o.putOpt("battStart", batteryStart)
        o.putOpt("battLast", batteryLast)
        o.put("battCharged", batteryCharged)
        o.put("osnmaPts", osnmaPoints)
        o.put("osnmaAll", osnmaAllAuthenticated)
        o.put("osnmaFail", osnmaFailedPoints)
        val a = JSONArray()
        for (s in samples) {
            val r = JSONArray()
            r.put(s.seconds.toDouble())
            r.put(s.distanceMeters.toDouble())
            r.put(s.elevation?.toDouble() ?: JSONObject.NULL)
            r.put(s.speed?.toDouble() ?: JSONObject.NULL)
            r.put(s.satellites)
            r.put(s.battery ?: JSONObject.NULL)
            a.put(r)
        }
        o.put("samples", a)
        return o
    }

    companion object {
        /** Battery use per hour is only shown for tracks at least this long */
        const val MIN_BATTERY_RATE_MILLIS = 10 * 60_000L

        fun fromJson(o: JSONObject): GpxTrackStats {
            fun optD(k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.getDouble(k)
            val a = o.optJSONArray("samples") ?: JSONArray()
            val samples = ArrayList<Sample>(a.length())
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                samples.add(
                    Sample(
                        r.getDouble(0).toFloat(),
                        r.getDouble(1).toFloat(),
                        if (r.isNull(2)) null else r.getDouble(2).toFloat(),
                        if (r.isNull(3)) null else r.getDouble(3).toFloat(),
                        r.optInt(4, 0),
                        if (r.length() > 5 && !r.isNull(5)) r.getInt(5) else null
                    )
                )
            }
            return GpxTrackStats(
                fileLocation = o.optString("file"),
                fileName = o.optString("name"),
                fileUri = if (o.isNull("uri") || !o.has("uri")) null else o.getString("uri"),
                startTime = o.getLong("start"),
                lastFixTime = o.getLong("last"),
                points = o.getInt("points"),
                segments = o.getInt("segments"),
                distanceMeters = o.getDouble("distance"),
                movingTimeMillis = o.getLong("moving"),
                maxSpeed = optD("maxSpeed")?.toFloat(),
                currentSpeed = optD("speed")?.toFloat(),
                currentElevation = optD("ele"),
                minElevation = optD("minEle"),
                maxElevation = optD("maxEle"),
                ascentMeters = o.getDouble("ascent"),
                descentMeters = o.getDouble("descent"),
                avgSatellites = optD("sats"),
                avgHdop = optD("hdop"),
                dualFrequencyShare = optD("dual"),
                sbasShare = optD("sbas"),
                batteryStart = optD("battStart")?.toInt(),
                batteryLast = optD("battLast")?.toInt(),
                batteryCharged = o.optBoolean("battCharged", false),
                osnmaPoints = o.optInt("osnmaPts", 0),
                osnmaAllAuthenticated = o.optInt("osnmaAll", 0),
                osnmaFailedPoints = o.optInt("osnmaFail", 0),
                samples = samples
            )
        }
    }
}

/**
 * Accumulates [GpxTrackStats] from track points as they are recorded. Not thread safe; the
 * recorder calls it under its lock.
 *
 * GNSS noise is filtered so the numbers stay realistic:
 * - Distance only grows once the position has moved further from the last counted position than
 *   the fix accuracy (min. [MIN_STEP_METERS]), so standing still doesn't add distance.
 * - Ascent/descent only count changes beyond [ELEVATION_HYSTERESIS_METERS].
 * - Moving time counts intervals at [MOVING_SPEED] or faster that are not a segment gap.
 */
class GpxTrackStatsAccumulator(
    private val fileLocation: String,
    private val fileName: String,
    private val fileUri: String?,
    private val startTime: Long
) {

    private var lastFixTime = startTime
    private var points = 0
    private var segments = 0
    private var distance = 0.0
    private var movingMillis = 0L
    private var maxSpeed: Float? = null
    private var currentSpeed: Float? = null
    private var currentEle: Double? = null
    private var minEle: Double? = null
    private var maxEle: Double? = null
    private var ascent = 0.0
    private var descent = 0.0
    private var eleRef: Double? = null
    private var satSum = 0L
    private var satCount = 0
    private var hdopSum = 0.0
    private var hdopCount = 0
    private var dualCount = 0
    private var bandInfoCount = 0
    private var sbasCount = 0
    private var batteryStart: Int? = null
    private var batteryLast: Int? = null
    private var batteryCharged = false
    private var osnmaPoints = 0
    private var osnmaAll = 0
    private var osnmaFailedPoints = 0

    private var anchor: Location? = null
    private var previousTime: Long? = null

    private var samples = ArrayList<GpxTrackStats.Sample>()
    /** Keep every n-th point as a chart sample; doubles whenever the sample list is full */
    private var sampleStride = 1
    private var sinceLastSample = 0

    fun add(l: Location, fix: GpxFixContext, newSegment: Boolean) {
        points++
        if (newSegment) segments++

        // Distance with drift filter
        val a = anchor
        if (a == null) {
            anchor = Location(l)
        } else {
            val d = a.distanceTo(l)
            val threshold = max(MIN_STEP_METERS, if (l.hasAccuracy()) l.accuracy else MIN_STEP_METERS)
            if (d > threshold) {
                distance += d
                anchor = Location(l)
            }
        }

        // Moving time
        val prev = previousTime
        if (prev != null && !newSegment && l.hasSpeed() && l.speed >= MOVING_SPEED) {
            movingMillis += l.time - prev
        }
        previousTime = l.time
        lastFixTime = l.time

        if (l.hasSpeed()) {
            maxSpeed = max(maxSpeed ?: 0f, l.speed)
        }
        currentSpeed = if (l.hasSpeed()) l.speed else null

        // Elevation with hysteresis for ascent/descent
        val ele = GpxTrackWriter.elevationOf(l, fix)
        if (ele != null) {
            currentEle = ele
            minEle = minOf(minEle ?: ele, ele)
            maxEle = maxOf(maxEle ?: ele, ele)
            val ref = eleRef
            if (ref == null) {
                eleRef = ele
            } else if (ele - ref >= ELEVATION_HYSTERESIS_METERS) {
                ascent += ele - ref
                eleRef = ele
            } else if (ref - ele >= ELEVATION_HYSTERESIS_METERS) {
                descent += ref - ele
                eleRef = ele
            }
        }

        // GNSS quality
        if (fix.satellitesUsed > 0) {
            satSum += fix.satellitesUsed
            satCount++
        }
        fix.dop?.let {
            hdopSum += it.horizontalDop
            hdopCount++
        }
        if (fix.bandsUsed.isNotEmpty()) {
            bandInfoCount++
            if (fix.dualFrequency) dualCount++
        }
        if (fix.sbasUsed) sbasCount++

        // Battery
        fix.batteryPercent?.let {
            if (batteryStart == null) batteryStart = it
            batteryLast = it
        }
        if (fix.batteryCharging == true) batteryCharged = true

        // OSNMA
        if (fix.osnma != null && fix.galileoUsed.isNotEmpty()) {
            osnmaPoints++
            if (fix.osnmaAuthenticated == fix.galileoUsed.size) osnmaAll++
        }
        if (fix.osnmaFailed > 0) osnmaFailedPoints++

        // Chart samples
        sinceLastSample++
        if (samples.isEmpty() || sinceLastSample >= sampleStride) {
            sinceLastSample = 0
            samples.add(
                GpxTrackStats.Sample(
                    seconds = (l.time - startTime) / 1000f,
                    distanceMeters = distance.toFloat(),
                    elevation = ele?.toFloat(),
                    speed = if (l.hasSpeed()) l.speed else null,
                    satellites = fix.satellitesUsed,
                    battery = fix.batteryPercent
                )
            )
            if (samples.size >= MAX_SAMPLES) {
                // Halve the resolution and keep sampling at the new stride
                val thinned = ArrayList<GpxTrackStats.Sample>(MAX_SAMPLES / 2 + 1)
                for (i in samples.indices step 2) thinned.add(samples[i])
                samples = thinned
                sampleStride *= 2
            }
        }
    }

    fun snapshot(): GpxTrackStats = GpxTrackStats(
        fileLocation = fileLocation,
        fileName = fileName,
        fileUri = fileUri,
        startTime = startTime,
        lastFixTime = lastFixTime,
        points = points,
        segments = segments,
        distanceMeters = distance,
        movingTimeMillis = movingMillis,
        maxSpeed = maxSpeed,
        currentSpeed = currentSpeed,
        currentElevation = currentEle,
        minElevation = minEle,
        maxElevation = maxEle,
        ascentMeters = ascent,
        descentMeters = descent,
        avgSatellites = if (satCount > 0) satSum.toDouble() / satCount else null,
        avgHdop = if (hdopCount > 0) hdopSum / hdopCount else null,
        dualFrequencyShare = if (bandInfoCount > 0) dualCount.toDouble() / bandInfoCount else null,
        sbasShare = if (points > 0) sbasCount.toDouble() / points else null,
        batteryStart = batteryStart,
        batteryLast = batteryLast,
        batteryCharged = batteryCharged,
        osnmaPoints = osnmaPoints,
        osnmaAllAuthenticated = osnmaAll,
        osnmaFailedPoints = osnmaFailedPoints,
        samples = ArrayList(samples)
    )

    companion object {
        const val MIN_STEP_METERS = 3f
        const val ELEVATION_HYSTERESIS_METERS = 3.0
        const val MOVING_SPEED = 0.5f
        const val MAX_SAMPLES = 1000
    }
}
