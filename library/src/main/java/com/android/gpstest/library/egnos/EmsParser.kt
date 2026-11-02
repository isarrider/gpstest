/*
 * Copyright (C) 2026 The GPSTest contributors
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
package com.android.gpstest.library.egnos

/**
 * Reads the hourly files of ESA's EGNOS Message Server (EMS), one message per line:
 * `PRN YY MM DD HH MM SS MT HEX`, e.g. `136 26 11 01 14 00 00 2 9A08...`. Used to test the
 * decoder with recorded data and to replay a file.
 */
object EmsParser {

    data class Record(val prn: Int, val gpsSeconds: Long, val type: Int, val message: SbasMessage)

    /** Returns null for lines that aren't messages (comments, empty lines, damaged lines) */
    fun parseLine(line: String): Record? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 9) return null
        return try {
            val prn = t[0].toInt()
            val year = t[1].toInt().let { if (it < 100) 2000 + it else it }
            // EMS time stamps are GPS time; they're only used for ages and display
            val month = t[2].toInt()
            val day = t[3].toInt()
            require(month in 1..12 && day in 1..31)
            val time = (daysFromCivil(year, month, day) - GPS_EPOCH_DAYS) * 86_400L +
                    t[4].toInt() * 3600L + t[5].toInt() * 60L + t[6].toDouble().toLong()
            Record(prn, time, t[7].toInt(), SbasMessage.fromHex(t[8]))
        } catch (e: RuntimeException) {
            null
        }
    }

    /** 1980-01-06, the GPS epoch, in days since 1970-01-01 */
    private val GPS_EPOCH_DAYS = daysFromCivil(1980, 1, 6)

    /**
     * Days since 1970-01-01 of a Gregorian date (H. Hinnant's algorithm). java.time would need
     * API level 26.
     */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun parse(lines: Sequence<String>): Sequence<Record> = lines.mapNotNull { parseLine(it) }
}
