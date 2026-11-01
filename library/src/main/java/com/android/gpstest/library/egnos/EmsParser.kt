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

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Reads the hourly files of ESA's EGNOS Message Server (EMS), one message per line:
 * `PRN YY MM DD HH MM SS MT HEX`, e.g. `136 26 11 01 14 00 00 2 9A08...`. Used to test the
 * decoder with recorded data and to replay a file.
 */
object EmsParser {

    data class Record(val prn: Int, val gpsSeconds: Long, val type: Int, val message: SbasMessage)

    private val GPS_EPOCH = LocalDateTime.of(1980, 1, 6, 0, 0).toEpochSecond(ZoneOffset.UTC)

    /** Returns null for lines that aren't messages (comments, empty lines, damaged lines) */
    fun parseLine(line: String): Record? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 9) return null
        return try {
            val prn = t[0].toInt()
            val year = t[1].toInt().let { if (it < 100) 2000 + it else it }
            // EMS time stamps are GPS time; they're only used for ages and display
            val time = LocalDateTime.of(
                year, t[2].toInt(), t[3].toInt(), t[4].toInt(), t[5].toInt(),
                t[6].toDouble().toInt()
            ).toEpochSecond(ZoneOffset.UTC) - GPS_EPOCH
            Record(prn, time, t[7].toInt(), SbasMessage.fromHex(t[8]))
        } catch (e: RuntimeException) {
            null
        }
    }

    fun parse(lines: Sequence<String>): Sequence<Record> = lines.mapNotNull { parseLine(it) }
}
