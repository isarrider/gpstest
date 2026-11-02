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

package com.android.gpstest.io;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.android.gpstest.Application;
import com.android.gpstest.BuildConfig;
import com.android.gpstest.R;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Records GPS_PROVIDER fixes as a GPX 1.1 track.
 * <p>
 * One {@code <trk>} per logging session. A new {@code <trkseg>} is started whenever the gap between
 * two consecutive fixes exceeds {@link #SEGMENT_GAP_MS}, so tunnels or signal loss don't get drawn
 * as straight lines. Each point is flushed right away so that a recording interrupted by the system
 * loses at most the closing tags (which most GPX tools tolerate or can be appended by hand).
 */
public class GpxFileLogger extends BaseFileLogger implements FileLogger {

    /** Gap between fixes after which a new track segment is started */
    static final long SEGMENT_GAP_MS = 30_000L;

    private static final String GPX_NS = "http://www.topografix.com/GPX/1/1";
    private static final String TPX_NS = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2";
    private static final String GPSTEST_NS = "https://github.com/barbeau/gpstest/gpx/1";

    private final SimpleDateFormat isoFormat;

    private boolean segmentOpen = false;
    private long lastFixTime = Long.MIN_VALUE;
    private int pointCount = 0;

    public GpxFileLogger(Context context) {
        super(context);
        isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    String getFileExtension() {
        return "gpx";
    }

    @Override
    void writeFileHeader(BufferedWriter writer, String filePath) {
        try {
            String now = isoFormat.format(new Date());
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<gpx version=\"1.1\" creator=\"GPSTest " + escape(BuildConfig.VERSION_NAME) + "\"\n");
            writer.write("     xmlns=\"" + GPX_NS + "\"\n");
            writer.write("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            writer.write("     xmlns:gpxtpx=\"" + TPX_NS + "\"\n");
            writer.write("     xmlns:gpstest=\"" + GPSTEST_NS + "\"\n");
            writer.write("     xsi:schemaLocation=\"" + GPX_NS + " http://www.topografix.com/GPX/1/1/gpx.xsd "
                    + TPX_NS + " https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd\">\n");
            writer.write("  <metadata>\n");
            writer.write("    <name>" + escape(new File(filePath).getName()) + "</name>\n");
            writer.write("    <desc>" + escape(Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE) + "</desc>\n");
            writer.write("    <time>" + now + "</time>\n");
            writer.write("  </metadata>\n");
            writer.write("  <trk>\n");
            writer.write("    <name>GPSTest " + now + "</name>\n");
            writer.write("    <src>" + escape(Build.MANUFACTURER + " " + Build.MODEL) + "</src>\n");
            writer.write("    <type>GNSS</type>\n");
            writer.flush();
        } catch (IOException e) {
            logException(Application.Companion.getApp().getString(R.string.error_writing_file), e);
        }
    }

    @Override
    boolean postFileInit(BufferedWriter fileWriter, boolean isNewFile) {
        segmentOpen = false;
        lastFixTime = Long.MIN_VALUE;
        pointCount = 0;
        ContextCompat.getMainExecutor(context).execute(() -> Toast.makeText(
                Application.Companion.getApp().getApplicationContext(),
                Application.Companion.getApp().getString(
                        R.string.logging_to_new_file,
                        file.getAbsolutePath()
                ),
                Toast.LENGTH_LONG
        ).show());
        return true;
    }

    /**
     * Appends a track point for the given location. Only GPS_PROVIDER fixes are recorded.
     */
    public synchronized void onLocationChanged(Location location) {
        if (fileWriter == null || location == null
                || !LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            return;
        }
        long time = location.getTime();
        if (time <= lastFixTime) {
            // Duplicate or out-of-order fix
            return;
        }
        try {
            if (segmentOpen && time - lastFixTime > SEGMENT_GAP_MS) {
                fileWriter.write("    </trkseg>\n");
                segmentOpen = false;
            }
            if (!segmentOpen) {
                fileWriter.write("    <trkseg>\n");
                segmentOpen = true;
            }
            fileWriter.write(toTrackPoint(location));
            fileWriter.flush();
            lastFixTime = time;
            pointCount++;
        } catch (IOException e) {
            logException(Application.Companion.getApp().getString(R.string.error_writing_file), e);
        }
    }

    /**
     * @return number of track points written in the current file
     */
    public synchronized int getPointCount() {
        return pointCount;
    }

    @Override
    public synchronized void close() {
        if (fileWriter != null) {
            try {
                if (segmentOpen) {
                    fileWriter.write("    </trkseg>\n");
                    segmentOpen = false;
                }
                fileWriter.write("  </trk>\n");
                fileWriter.write("</gpx>\n");
            } catch (IOException e) {
                logException("Unable to write GPX footer.", e);
            }
        }
        super.close();
    }

    private String toTrackPoint(Location l) {
        StringBuilder sb = new StringBuilder(384);
        sb.append("      <trkpt lat=\"").append(coord(l.getLatitude()))
                .append("\" lon=\"").append(coord(l.getLongitude())).append("\">\n");

        // Child order follows the GPX 1.1 schema (wptType sequence)
        Double msl = mslAltitude(l);
        if (msl != null) {
            sb.append("        <ele>").append(num(msl, 2)).append("</ele>\n");
        } else if (l.hasAltitude()) {
            sb.append("        <ele>").append(num(l.getAltitude(), 2)).append("</ele>\n");
        }
        sb.append("        <time>").append(isoFormat.format(new Date(l.getTime()))).append("</time>\n");
        if (msl != null && l.hasAltitude()) {
            // Height of the geoid above the WGS84 ellipsoid
            sb.append("        <geoidheight>").append(num(l.getAltitude() - msl, 2)).append("</geoidheight>\n");
        }
        sb.append("        <fix>").append(l.hasAltitude() ? "3d" : "2d").append("</fix>\n");
        int sats = satellitesUsed(l);
        if (sats > 0) {
            sb.append("        <sat>").append(sats).append("</sat>\n");
        }

        boolean hasTpx = l.hasSpeed() || l.hasBearing();
        boolean hasAcc = l.hasAccuracy() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasVerticalAccuracy());
        if (hasTpx || hasAcc) {
            sb.append("        <extensions>\n");
            if (hasTpx) {
                sb.append("          <gpxtpx:TrackPointExtension>\n");
                if (l.hasSpeed()) {
                    sb.append("            <gpxtpx:speed>").append(num(l.getSpeed(), 2)).append("</gpxtpx:speed>\n");
                }
                if (l.hasBearing()) {
                    sb.append("            <gpxtpx:course>").append(num(l.getBearing(), 1)).append("</gpxtpx:course>\n");
                }
                sb.append("          </gpxtpx:TrackPointExtension>\n");
            }
            if (l.hasAccuracy()) {
                sb.append("          <gpstest:hacc>").append(num(l.getAccuracy(), 1)).append("</gpstest:hacc>\n");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && l.hasVerticalAccuracy()) {
                sb.append("          <gpstest:vacc>").append(num(l.getVerticalAccuracyMeters(), 1)).append("</gpstest:vacc>\n");
            }
            sb.append("        </extensions>\n");
        }
        sb.append("      </trkpt>\n");
        return sb.toString();
    }

    /** MSL altitude (Android 14+), or null if unavailable */
    private static Double mslAltitude(Location l) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && l.hasMslAltitude()) {
            return l.getMslAltitudeMeters();
        }
        return null;
    }

    /** Satellites used in fix as reported by the "satellites" extra, or 0 if not provided */
    private static int satellitesUsed(Location l) {
        Bundle extras = l.getExtras();
        if (extras == null) {
            return 0;
        }
        return extras.getInt("satellites", 0);
    }

    private static String coord(double v) {
        return String.format(Locale.US, "%.8f", v);
    }

    private static String num(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
