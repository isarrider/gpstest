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

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.android.gpstest.BuildConfig
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.util.CarrierFreqUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes one GPX 1.1 file containing a single track.
 *
 * The file is kept valid at all times: every [append] overwrites the closing tags with the new
 * points and writes the closing tags again behind them. A recording that is interrupted (app killed,
 * phone rebooted) therefore leaves a complete GPX file up to the last append.
 *
 * On Android 10+ the file is created directly in Download/GPSTest through MediaStore, so there is
 * no private working copy. On older versions it goes to the app's external files directory, which
 * file managers can reach there without any storage permission.
 */
class GpxTrackWriter(
    private val context: Context,
    /** Write the full list of satellite signals for every point (large files) */
    private val includeSatellites: Boolean
) {

    /** A point to write; [newSegment] starts a new trkseg before this point */
    data class Point(val location: Location, val fix: GpxFixContext, val newSegment: Boolean)

    var displayName: String = ""
        private set

    private var uri: Uri? = null
    private var file: File? = null

    /** Byte offset where the closing tags start, i.e. where the next points are written */
    private var bodyEnd = 0L
    private var segmentOpen = false

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Creates the file and writes the header.
     * @return true if the file was created
     */
    fun create(start: Date): Boolean {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(start)
        displayName = "gpstest_track_$stamp.gpx"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY)
                }
                uri = context.contentResolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: return false
            } else {
                val dir = File(context.getExternalFilesDir(null), DIRECTORY)
                dir.mkdirs()
                file = File(dir, displayName)
            }
            val header = header(start).toByteArray(Charsets.UTF_8)
            bodyEnd = 0L
            segmentOpen = false
            writeAt(header)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create GPX track file", e)
            return false
        }
    }

    /**
     * Writes the points and closes the file again with valid closing tags.
     */
    @Throws(IOException::class)
    fun append(points: List<Point>) {
        if (points.isEmpty()) return
        val segmentOpenBefore = segmentOpen
        val sb = StringBuilder(points.size * 400)
        for (p in points) {
            if (p.newSegment || !segmentOpen) {
                if (segmentOpen) sb.append(SEG_CLOSE)
                sb.append(SEG_OPEN)
                segmentOpen = true
            }
            appendTrackPoint(sb, p.location, p.fix)
        }
        try {
            writeAt(sb.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Nothing was committed, so the next attempt has to start from the same state
            segmentOpen = segmentOpenBefore
            throw e
        }
    }

    /** content:// URI of the file for sharing (Android 10+), or null */
    val contentUri: Uri?
        get() = uri

    /** Location of the file for messages to the user */
    fun displayLocation(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY + "/" + displayName
        } else {
            file?.absolutePath ?: displayName
        }
    }

    /**
     * Writes [body] at [bodyEnd], followed by the closing tags, and truncates anything behind them.
     */
    private fun writeAt(body: ByteArray) {
        val closing = (if (segmentOpen) SEG_CLOSE else "") + TRK_CLOSE
        val tail = closing.toByteArray(Charsets.UTF_8)
        withChannel { channel ->
            channel.position(bodyEnd)
            writeFully(channel, ByteBuffer.wrap(body))
            writeFully(channel, ByteBuffer.wrap(tail))
            channel.truncate(bodyEnd + body.size + tail.size)
            channel.force(false)
        }
        bodyEnd += body.size
    }

    /** Opens the file read/write, runs [block] on its channel and closes everything afterwards */
    private fun <T> withChannel(block: (FileChannel) -> T): T {
        val u = uri
        if (u != null) {
            val pfd = context.contentResolver.openFileDescriptor(u, "rw")
                ?: throw IOException("Unable to open $u")
            return pfd.use { FileOutputStream(it.fileDescriptor).use { out -> block(out.channel) } }
        }
        return RandomAccessFile(file!!, "rw").use { block(it.channel) }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun header(start: Date): String {
        val now = iso.format(start)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"GPSTest ").append(escape(BuildConfig.VERSION_NAME)).append("\"\n")
            append("     xmlns=\"").append(GPX_NS).append("\"\n")
            append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            append("     xmlns:gpxtpx=\"").append(TPX_NS).append("\"\n")
            append("     xmlns:gpstest=\"").append(GPSTEST_NS).append("\"\n")
            append("     xsi:schemaLocation=\"").append(GPX_NS).append(" http://www.topografix.com/GPX/1/1/gpx.xsd ")
                .append(TPX_NS).append(" https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd\">\n")
            append("  <metadata>\n")
            append("    <name>").append(escape(displayName)).append("</name>\n")
            append("    <desc>").append(escape("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")).append("</desc>\n")
            append("    <time>").append(now).append("</time>\n")
            append("  </metadata>\n")
            append("  <trk>\n")
            append("    <name>GPSTest ").append(now).append("</name>\n")
            append("    <src>").append(escape("${Build.MANUFACTURER} ${Build.MODEL}")).append("</src>\n")
            append("    <type>GNSS</type>\n")
        }
    }

    private fun appendTrackPoint(sb: StringBuilder, l: Location, fix: GpxFixContext) {
        sb.append("      <trkpt lat=\"").append(fmt(l.latitude, 8))
            .append("\" lon=\"").append(fmt(l.longitude, 8)).append("\">\n")

        // Child order follows the GPX 1.1 schema (wptType sequence):
        // ele, time, magvar, geoidheight, ..., fix, sat, hdop, vdop, pdop, ageofdgpsdata, dgpsid, extensions

        // Elevation above mean sea level: Android 14+ Location, else the chip's NMEA GGA, else WGS84
        val locationMsl = mslAltitude(l)
        val msl = locationMsl ?: fix.mslAltitude
        val ele = elevationOf(l, fix)
        if (ele != null) {
            sb.append("        <ele>").append(fmt(ele, 2)).append("</ele>\n")
        }
        sb.append("        <time>").append(iso.format(Date(l.time))).append("</time>\n")
        val geoid = if (locationMsl != null && l.hasAltitude()) l.altitude - locationMsl else fix.geoidSeparation
        if (msl != null && geoid != null) {
            sb.append("        <geoidheight>").append(fmt(geoid, 2)).append("</geoidheight>\n")
        }

        sb.append("        <fix>").append(fixType(l, fix)).append("</fix>\n")
        val sats = if (fix.satellitesUsed > 0) fix.satellitesUsed else l.extras?.getInt("satellites", 0) ?: 0
        if (sats > 0) {
            sb.append("        <sat>").append(sats).append("</sat>\n")
        }
        fix.dop?.let {
            sb.append("        <hdop>").append(fmt(it.horizontalDop, 1)).append("</hdop>\n")
            sb.append("        <vdop>").append(fmt(it.verticalDop, 1)).append("</vdop>\n")
            sb.append("        <pdop>").append(fmt(it.positionDop, 1)).append("</pdop>\n")
        }
        fix.ageOfDgpsData?.let {
            sb.append("        <ageofdgpsdata>").append(fmt(it, 1)).append("</ageofdgpsdata>\n")
        }
        fix.dgpsId?.takeIf { it in 0..1023 }?.let {
            sb.append("        <dgpsid>").append(it).append("</dgpsid>\n")
        }

        sb.append("        <extensions>\n")
        if (l.hasSpeed() || l.hasBearing()) {
            sb.append("          <gpxtpx:TrackPointExtension>\n")
            if (l.hasSpeed()) {
                sb.append("            <gpxtpx:speed>").append(fmt(l.speed.toDouble(), 2)).append("</gpxtpx:speed>\n")
            }
            if (l.hasBearing()) {
                sb.append("            <gpxtpx:course>").append(fmt(l.bearing.toDouble(), 1)).append("</gpxtpx:course>\n")
            }
            sb.append("          </gpxtpx:TrackPointExtension>\n")
        }
        appendGpsTestExtensions(sb, l, fix)
        sb.append("        </extensions>\n")
        sb.append("      </trkpt>\n")
    }

    /** GPX "fix": dgps when corrections are applied (GGA quality or an SBAS satellite in use) */
    private fun fixType(l: Location, fix: GpxFixContext): String {
        return when {
            fix.ggaQuality == 3 -> "pps"
            fix.ggaQuality == 2 || fix.ggaQuality == 4 || fix.ggaQuality == 5 || fix.sbasUsed -> "dgps"
            l.hasAltitude() -> "3d"
            else -> "2d"
        }
    }

    private fun appendGpsTestExtensions(sb: StringBuilder, l: Location, fix: GpxFixContext) {
        // Accuracy (meters, m/s, degrees)
        if (l.hasAccuracy()) {
            sb.append("          <gpstest:hacc>").append(fmt(l.accuracy.toDouble(), 1)).append("</gpstest:hacc>\n")
        }
        verticalAccuracy(l)?.let {
            sb.append("          <gpstest:vacc>").append(fmt(it.toDouble(), 1)).append("</gpstest:vacc>\n")
        }
        speedAccuracy(l)?.let {
            sb.append("          <gpstest:sacc>").append(fmt(it.toDouble(), 2)).append("</gpstest:sacc>\n")
        }
        bearingAccuracy(l)?.let {
            sb.append("          <gpstest:bacc>").append(fmt(it.toDouble(), 1)).append("</gpstest:bacc>\n")
        }
        fix.heading?.let {
            sb.append("          <gpstest:heading>").append(fmt(it, 1)).append("</gpstest:heading>\n")
        }
        fix.batteryPercent?.let {
            sb.append("          <gpstest:battery level=\"").append(it)
                .append("\" charging=\"").append(fix.batteryCharging == true).append("\"/>\n")
        }
        fix.pressureHpa?.let {
            sb.append("          <gpstest:pressure>").append(fmt(it.toDouble(), 2)).append("</gpstest:pressure>\n")
        }

        if (fix.satellites.isEmpty()) return

        // Satellites used / in view per constellation (distinct satellites, not signals)
        val byType = fix.satellites.groupBy { it.gnssType }
        sb.append("          <gpstest:constellations>\n")
        for ((type, signals) in byType.toSortedMap(compareBy { it.ordinal })) {
            val inView = signals.map { it.svid }.toSet().size
            val used = signals.filter { it.usedInFix }.map { it.svid }.toSet().size
            sb.append("            <gpstest:c sys=\"").append(type.name)
                .append("\" used=\"").append(used).append("\" view=\"").append(inView).append("\"/>\n")
        }
        sb.append("          </gpstest:constellations>\n")

        // C/N0 over the signals used in the fix
        val cn0 = fix.usedSignals.map { it.cn0DbHz }.filter { it > 0f }
        if (cn0.isNotEmpty()) {
            sb.append("          <gpstest:cn0 avg=\"").append(fmt(cn0.average(), 1))
                .append("\" max=\"").append(fmt(cn0.maxOrNull()!!.toDouble(), 1)).append("\"/>\n")
        }

        if (fix.bandsUsed.isNotEmpty()) {
            sb.append("          <gpstest:bands dual=\"").append(fix.dualFrequency).append("\">")
                .append(escape(fix.bandsUsed.joinToString(","))).append("</gpstest:bands>\n")
        }

        // SBAS corrections (e.g. EGNOS)
        sb.append("          <gpstest:sbas used=\"").append(fix.sbasUsed).append("\"")
        if (fix.sbasUsed) {
            fix.sbasSystem?.let { sb.append(" system=\"").append(it.name).append("\"") }
            val prns = fix.usedSignals.filter { it.gnssType == GnssType.SBAS }
                .map { it.svid }.toSortedSet()
            sb.append(" prn=\"").append(prns.joinToString(",")).append("\"")
        }
        sb.append("/>\n")

        // Galileo OSNMA: how many of the Galileo satellites used in the fix had authenticated
        // navigation data. This vouches for the data, not for the position (see LOGGING.md).
        fix.osnma?.let {
            sb.append("          <gpstest:osnma phase=\"").append(it.phase)
                .append("\" auth=\"").append(fix.osnmaAuthenticated)
                .append("\" failed=\"").append(fix.osnmaFailed)
                .append("\" galileo=\"").append(fix.galileoUsed.size)
                .append("\" time=\"").append(if (it.timeNetwork) "network" else "device")
                .append("\"/>\n")
        }

        if (includeSatellites) {
            sb.append("          <gpstest:sats>\n")
            for (s in fix.satellites) {
                val band = CarrierFreqUtils.getCarrierFrequencyLabel(s)
                sb.append("            <gpstest:s sys=\"").append(s.gnssType.name)
                    .append("\" id=\"").append(s.svid).append("\"")
                if (band != CarrierFreqUtils.CF_UNKNOWN &&
                    band != CarrierFreqUtils.CF_UNSUPPORTED
                ) {
                    sb.append(" band=\"").append(escape(band)).append("\"")
                }
                sb.append(" cn0=\"").append(fmt(s.cn0DbHz.toDouble(), 1))
                    .append("\" el=\"").append(fmt(s.elevationDegrees.toDouble(), 0))
                    .append("\" az=\"").append(fmt(s.azimuthDegrees.toDouble(), 0))
                    .append("\" used=\"").append(if (s.usedInFix) 1 else 0).append("\"/>\n")
            }
            sb.append("          </gpstest:sats>\n")
        }
    }

    private fun speedAccuracy(l: Location): Float? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasSpeedAccuracy()) {
            l.speedAccuracyMetersPerSecond
        } else {
            null
        }
    }

    private fun bearingAccuracy(l: Location): Float? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasBearingAccuracy()) {
            l.bearingAccuracyDegrees
        } else {
            null
        }
    }

    private fun verticalAccuracy(l: Location): Float? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasVerticalAccuracy()) {
            l.verticalAccuracyMeters
        } else {
            null
        }
    }


    companion object {
        private const val TAG = "GpxTrackWriter"

        /** MSL altitude from the Location (Android 14+), or null */
        fun mslAltitude(l: Location): Double? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && l.hasMslAltitude()) {
                l.mslAltitudeMeters
            } else {
                null
            }
        }

        /**
         * Elevation as written to the GPX file: MSL from the Location (Android 14+), else MSL from the
         * chip's NMEA GGA, else the WGS84 altitude. Null if the fix has no altitude.
         */
        fun elevationOf(l: Location, fix: GpxFixContext): Double? {
            return mslAltitude(l) ?: fix.mslAltitude ?: if (l.hasAltitude()) l.altitude else null
        }
        const val DIRECTORY = "GPSTest"
        private const val MIME_TYPE = "application/gpx+xml"

        private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
        private const val TPX_NS = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
        private const val GPSTEST_NS = "https://github.com/barbeau/gpstest/gpx/1"

        private const val SEG_OPEN = "    <trkseg>\n"
        private const val SEG_CLOSE = "    </trkseg>\n"
        private const val TRK_CLOSE = "  </trk>\n</gpx>\n"

        private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

        fun escape(s: String?): String {
            if (s == null) return ""
            return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }
    }
}
