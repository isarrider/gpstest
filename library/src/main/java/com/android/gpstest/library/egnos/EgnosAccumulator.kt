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
 * Collects decoded EGNOS messages into the current picture of the corrections: the PRN mask,
 * the fast and long-term corrections and integrity (UDREI) of each satellite, and the
 * ionospheric grid. Message times are GPS seconds (week * 604800 + time of week); ages in the
 * [EgnosSnapshot] are relative to the newest message.
 *
 * This is a monitor, not a receiver: corrections are shown with their age, they aren't applied to
 * positions, and the timeouts used to call data "current" are simplified (see the constants).
 *
 * Not thread safe; feed and read it from one thread.
 */
class EgnosAccumulator {

    private var messages = 0L
    private var crcErrors = 0L
    private val typeCounts = sortedMapOf<Int, Long>()
    private var lastTime: Long? = null
    private var lastType: Int? = null
    private var dontUseTime: Long? = null

    // PRN masks by IODP, and the IODP of the newest MT1
    private val masks = HashMap<Int, List<Int>>()
    private var currentIodp: Int? = null

    // By mask slot (1-210)
    private val fast = HashMap<Int, Timed<Double>>()
    private val udrei = HashMap<Int, Timed<Int>>()
    private val longTerm = HashMap<Int, Timed<LongTermCorrection>>()

    // IODF and IODP of the last MT2-5 per block (0 = MT2), for MT6
    private val blockIodf = IntArray(4) { -1 }
    private val blockIodp = IntArray(4) { -1 }

    // Ionosphere, by band (0-10)
    private val igpMasks = HashMap<Int, SbasPayload.IgpMask>()
    private val igpDelays = HashMap<Int, Array<Timed<IgpDelay>?>>()

    private val geoAlmanac = HashMap<Int, SbasPayload.GeoAlmanac.Entry>()
    private var geoNavTime: Long? = null

    private data class Timed<T>(val value: T, val time: Long)

    /**
     * Adds a message received at [gpsSeconds]. Returns the decoded payload, or null if the CRC
     * or preamble is wrong.
     */
    fun add(message: SbasMessage, gpsSeconds: Long): SbasPayload? {
        if (!message.valid) {
            crcErrors++
            return null
        }
        messages++
        typeCounts[message.type] = (typeCounts[message.type] ?: 0L) + 1
        lastTime = maxOf(lastTime ?: gpsSeconds, gpsSeconds)
        lastType = message.type
        val payload = SbasDecoder.decode(message)
        when (payload) {
            is SbasPayload.DoNotUse -> dontUseTime = gpsSeconds
            is SbasPayload.PrnMask -> {
                masks[payload.iodp] = payload.slots
                currentIodp = payload.iodp
            }
            is SbasPayload.FastCorrections -> applyFast(
                payload.iodp, payload.iodf, payload.firstMaskIndex,
                payload.correctionsMeters, payload.udreis, gpsSeconds, payload.type - 2
            )
            is SbasPayload.Mixed -> {
                applyFast(
                    payload.iodp, payload.iodf, payload.firstMaskIndex,
                    payload.correctionsMeters, payload.udreis, gpsSeconds, null
                )
                applyLongTerm(payload.longTerm, gpsSeconds)
            }
            is SbasPayload.Integrity -> applyIntegrity(payload, gpsSeconds)
            is SbasPayload.LongTerm -> applyLongTerm(payload.corrections, gpsSeconds)
            is SbasPayload.IgpMask -> {
                val previous = igpMasks[payload.band]
                if (previous == null || previous.iodi != payload.iodi ||
                    previous.positions != payload.positions
                ) {
                    igpDelays.remove(payload.band)
                }
                igpMasks[payload.band] = payload
            }
            is SbasPayload.IonoDelays -> applyIono(payload, gpsSeconds)
            is SbasPayload.GeoAlmanac -> payload.entries.forEach { geoAlmanac[it.prn] = it }
            is SbasPayload.GeoNavigation -> geoNavTime = gpsSeconds
            is SbasPayload.FastDegradation, is SbasPayload.Other -> Unit
        }
        return payload
    }

    /** Counts a message that couldn't be parsed at all (e.g. wrong length) as a CRC error */
    fun addCorrupted() {
        crcErrors++
    }

    private fun applyFast(
        iodp: Int,
        iodf: Int,
        firstIndex: Int,
        corrections: List<Double>,
        udreis: List<Int>,
        time: Long,
        block: Int?
    ) {
        val mask = masks[iodp] ?: return
        corrections.forEachIndexed { i, fc ->
            val slot = mask.getOrNull(firstIndex + i) ?: return@forEachIndexed
            fast[slot] = Timed(fc, time)
            udrei[slot] = Timed(udreis[i], time)
        }
        if (block != null) {
            blockIodf[block] = iodf
            blockIodp[block] = iodp
        }
    }

    private fun applyIntegrity(payload: SbasPayload.Integrity, time: Long) {
        for (block in 0 until 4) {
            val iodp = blockIodp[block]
            val iodf = payload.iodfs[block]
            // IODF 3 means the UDREIs apply regardless of the fast corrections' IODF
            if (iodp < 0 || (iodf != 3 && iodf != blockIodf[block])) continue
            val mask = masks[iodp] ?: continue
            for (i in block * 13 until minOf(block * 13 + 13, SbasDecoder.MAX_MASK)) {
                val slot = mask.getOrNull(i) ?: break
                udrei[slot] = Timed(payload.udreis[i], time)
            }
        }
    }

    private fun applyLongTerm(corrections: List<LongTermCorrection>, time: Long) {
        corrections.forEach { c ->
            val slot = masks[c.iodp]?.getOrNull(c.maskNumber - 1) ?: return@forEach
            longTerm[slot] = Timed(c, time)
        }
    }

    private fun applyIono(payload: SbasPayload.IonoDelays, time: Long) {
        val mask = igpMasks[payload.band] ?: return
        if (mask.iodi != payload.iodi) return
        val delays = igpDelays.getOrPut(payload.band) { arrayOfNulls(mask.positions.size) }
        payload.igps.forEachIndexed { i, igp ->
            val index = payload.blockId * 15 + i
            if (index < delays.size) delays[index] = Timed(igp, time)
        }
    }

    fun snapshot(): EgnosSnapshot {
        val now = lastTime
        val mask = currentIodp?.let { masks[it] } ?: emptyList()
        val satellites = mask.map { slot ->
            val fc = fast[slot]
            val u = udrei[slot]
            val lt = longTerm[slot]
            CorrectedSatellite(
                slot = slot,
                fastCorrectionMeters = fc?.value,
                fastAgeSeconds = fc?.let { age(now, it.time) },
                udrei = u?.value,
                udreiAgeSeconds = u?.let { age(now, it.time) },
                longTerm = lt?.value,
                longTermAgeSeconds = lt?.let { age(now, it.time) }
            )
        }
        val bands = igpMasks.values.toList()
        val igpsInMask = bands.sumOf { it.positions.size }
        val igpsMonitored = bands.sumOf { band ->
            igpDelays[band.band]?.count {
                it != null && it.value.monitored && age(now, it.time) <= IONO_TIMEOUT_SECONDS
            } ?: 0
        }
        return EgnosSnapshot(
            messages = messages,
            crcErrors = crcErrors,
            typeCounts = typeCounts.toMap(),
            lastMessageGpsSeconds = now,
            lastType = lastType,
            dontUse = dontUseTime?.let { age(now, it) <= DONT_USE_SECONDS } ?: false,
            iodp = currentIodp,
            satellites = satellites,
            igpBands = bands.size,
            igpsInMask = igpsInMask,
            igpsMonitored = igpsMonitored,
            geoAlmanac = geoAlmanac.values.sortedBy { it.prn },
            geoNavigationAgeSeconds = geoNavTime?.let { age(now, it) }
        )
    }

    private fun age(now: Long?, time: Long): Long = if (now == null) 0 else maxOf(0, now - time)

    companion object {
        /**
         * Fast corrections and UDREIs older than this aren't current. DO-229 derives the timeout
         * from the degradation parameters (MT7/MT10); 18 s is a typical en route/NPA value.
         */
        const val FAST_TIMEOUT_SECONDS = 18L

        /** Long-term corrections older than this aren't current (en route/NPA) */
        const val LONG_TERM_TIMEOUT_SECONDS = 360L

        /** Ionospheric delays older than this aren't current (en route/NPA) */
        const val IONO_TIMEOUT_SECONDS = 600L

        /** After an MT0, the SBAS isn't used for this long */
        const val DONT_USE_SECONDS = 60L
    }
}

/** EGNOS corrections for one satellite of the PRN mask */
data class CorrectedSatellite(
    /** PRN mask slot: 1-37 GPS, 38-61 GLONASS, 120-158 SBAS */
    val slot: Int,
    val fastCorrectionMeters: Double?,
    val fastAgeSeconds: Long?,
    val udrei: Int?,
    val udreiAgeSeconds: Long?,
    val longTerm: LongTermCorrection?,
    val longTermAgeSeconds: Long?
) {
    /** E.g. "G05", "R12", "S136" */
    val label: String get() = slotLabel(slot)

    /** Current fast correction and UDREI, and the satellite is monitored and usable */
    val usable: Boolean
        get() = udrei != null && udrei < UDREI_NOT_MONITORED &&
                (udreiAgeSeconds ?: Long.MAX_VALUE) <= EgnosAccumulator.FAST_TIMEOUT_SECONDS &&
                (fastAgeSeconds ?: Long.MAX_VALUE) <= EgnosAccumulator.FAST_TIMEOUT_SECONDS

    val doNotUse: Boolean get() = udrei == UDREI_DO_NOT_USE

    val notMonitored: Boolean get() = udrei == UDREI_NOT_MONITORED

    /** UDRE in meters for the current UDREI, or null if not monitored or don't use */
    val udreMeters: Double? get() = udrei?.let { UDRE_METERS.getOrNull(it) }

    val hasCurrentLongTerm: Boolean
        get() = longTerm != null &&
                (longTermAgeSeconds ?: Long.MAX_VALUE) <= EgnosAccumulator.LONG_TERM_TIMEOUT_SECONDS

    companion object {
        const val UDREI_NOT_MONITORED = 14
        const val UDREI_DO_NOT_USE = 15

        /** UDRE (99.9 %) in meters by UDREI 0-13, DO-229 Table A-6 */
        val UDRE_METERS = listOf(
            0.75, 1.0, 1.25, 1.75, 2.25, 3.0, 3.75, 4.5, 5.25, 6.0, 7.5, 15.0, 50.0, 150.0
        )

        fun slotLabel(slot: Int): String = when (slot) {
            in 1..37 -> "G%02d".format(slot)
            in 38..61 -> "R%02d".format(slot - 37)
            in 120..158 -> "S$slot"
            else -> "#$slot"
        }
    }
}

/** What has been received from EGNOS so far */
data class EgnosSnapshot(
    val messages: Long = 0,
    val crcErrors: Long = 0,
    val typeCounts: Map<Int, Long> = emptyMap(),
    /** GPS time (week * 604800 + time of week) of the newest message, or null */
    val lastMessageGpsSeconds: Long? = null,
    val lastType: Int? = null,
    /** An MT0 was received in the last minute: EGNOS (this GEO) must not be used */
    val dontUse: Boolean = false,
    val iodp: Int? = null,
    /** Satellites in the current PRN mask, in mask order */
    val satellites: List<CorrectedSatellite> = emptyList(),
    val igpBands: Int = 0,
    val igpsInMask: Int = 0,
    /** IGPs with a current, monitored vertical delay */
    val igpsMonitored: Int = 0,
    val geoAlmanac: List<SbasPayload.GeoAlmanac.Entry> = emptyList(),
    val geoNavigationAgeSeconds: Long? = null
) {
    val hasMask: Boolean get() = iodp != null

    val usableSatellites: List<CorrectedSatellite> get() = satellites.filter { it.usable }
}
