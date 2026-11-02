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
class GpxTrackWriter(private val context: Context) {

    /** A point to write; [newSegment] starts a new trkseg before this point */
    data class Point(val location: Location, val newSegment: Boolean)

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
            appendTrackPoint(sb, p.location)
        }
        try {
            writeAt(sb.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Nothing was committed, so the next attempt has to start from the same state
            segmentOpen = segmentOpenBefore
            throw e
        }
    }

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

    private fun appendTrackPoint(sb: StringBuilder, l: Location) {
        sb.append("      <trkpt lat=\"").append(fmt(l.latitude, 8))
            .append("\" lon=\"").append(fmt(l.longitude, 8)).append("\">\n")

        // Child order follows the GPX 1.1 schema (wptType sequence)
        val msl = mslAltitude(l)
        if (msl != null) {
            sb.append("        <ele>").append(fmt(msl, 2)).append("</ele>\n")
        } else if (l.hasAltitude()) {
            sb.append("        <ele>").append(fmt(l.altitude, 2)).append("</ele>\n")
        }
        sb.append("        <time>").append(iso.format(Date(l.time))).append("</time>\n")
        if (msl != null && l.hasAltitude()) {
            // Height of the geoid above the WGS84 ellipsoid
            sb.append("        <geoidheight>").append(fmt(l.altitude - msl, 2)).append("</geoidheight>\n")
        }
        sb.append("        <fix>").append(if (l.hasAltitude()) "3d" else "2d").append("</fix>\n")
        val sats = l.extras?.getInt("satellites", 0) ?: 0
        if (sats > 0) {
            sb.append("        <sat>").append(sats).append("</sat>\n")
        }

        val vAcc = verticalAccuracy(l)
        val hasVAcc = vAcc != null
        val hasTpx = l.hasSpeed() || l.hasBearing()
        if (hasTpx || l.hasAccuracy() || hasVAcc) {
            sb.append("        <extensions>\n")
            if (hasTpx) {
                sb.append("          <gpxtpx:TrackPointExtension>\n")
                if (l.hasSpeed()) {
                    sb.append("            <gpxtpx:speed>").append(fmt(l.speed.toDouble(), 2)).append("</gpxtpx:speed>\n")
                }
                if (l.hasBearing()) {
                    sb.append("            <gpxtpx:course>").append(fmt(l.bearing.toDouble(), 1)).append("</gpxtpx:course>\n")
                }
                sb.append("          </gpxtpx:TrackPointExtension>\n")
            }
            if (l.hasAccuracy()) {
                sb.append("          <gpstest:hacc>").append(fmt(l.accuracy.toDouble(), 1)).append("</gpstest:hacc>\n")
            }
            if (hasVAcc) {
                sb.append("          <gpstest:vacc>").append(fmt(vAcc!!.toDouble(), 1)).append("</gpstest:vacc>\n")
            }
            sb.append("        </extensions>\n")
        }
        sb.append("      </trkpt>\n")
    }

    private fun verticalAccuracy(l: Location): Float? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasVerticalAccuracy()) {
            l.verticalAccuracyMeters
        } else {
            null
        }
    }

    private fun mslAltitude(l: Location): Double? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && l.hasMslAltitude()) {
            l.mslAltitudeMeters
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "GpxTrackWriter"
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
