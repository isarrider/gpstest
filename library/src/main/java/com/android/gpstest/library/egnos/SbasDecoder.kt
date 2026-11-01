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
 * The content of an SBAS L1 message, decoded per RTCA DO-229 Appendix A. Only the message types
 * that EGNOS broadcasts and that are shown in the app are decoded; the others are [Other].
 */
sealed class SbasPayload {

    /** MT0: don't use this SBAS (e.g. a GEO in test). The data may repeat an MT2 or be zeros. */
    data class DoNotUse(val allZero: Boolean) : SbasPayload()

    /** MT1: which satellites (mask slots 1-210) get corrections, max. 51 */
    data class PrnMask(val iodp: Int, val slots: List<Int>) : SbasPayload()

    /**
     * MT2-5: fast corrections for 13 satellites of the mask each (MT2 the 1st-13th, MT3 the
     * 14th-26th, ...), in meters, and their UDREIs
     */
    data class FastCorrections(
        val type: Int,
        val iodf: Int,
        val iodp: Int,
        val correctionsMeters: List<Double>,
        val udreis: List<Int>
    ) : SbasPayload() {
        /** Index in the PRN mask (0-based) of the first satellite of this message */
        val firstMaskIndex: Int get() = (type - 2) * 13
    }

    /** MT6: UDREIs of all 51 mask positions, and the IODFs of the MT2-5 they refer to */
    data class Integrity(val iodfs: List<Int>, val udreis: List<Int>) : SbasPayload()

    /** MT7: fast correction degradation factors */
    data class FastDegradation(val latencySeconds: Int, val iodp: Int, val ais: List<Int>) :
        SbasPayload()

    /** MT9: GEO navigation message (position in ECEF meters) */
    data class GeoNavigation(
        val t0Seconds: Int,
        val ura: Int,
        val xMeters: Double,
        val yMeters: Double,
        val zMeters: Double
    ) : SbasPayload()

    /** MT17: almanacs of up to three SBAS GEOs */
    data class GeoAlmanac(val entries: List<Entry>, val t0Seconds: Int) : SbasPayload() {
        data class Entry(val prn: Int, val health: Int) {
            /** Service provider ID from the health bits (0 WAAS, 1 EGNOS, 2 MSAS, 3 GAGAN, ...) */
            val providerId: Int get() = (health ushr 4) and 0x0F

            /** Health bit 0: ranging available */
            val ranging: Boolean get() = health and 0x01 == 0
        }
    }

    /** MT18: which ionospheric grid points (IGPs) of a band are broadcast */
    data class IgpMask(
        val numBands: Int,
        val band: Int,
        val iodi: Int,
        /** Positions 1-201 of the band's IGPs that are in the mask */
        val positions: List<Int>
    ) : SbasPayload()

    /** MT24: 6 fast corrections plus one half of a long-term correction message */
    data class Mixed(
        val iodp: Int,
        val blockId: Int,
        val iodf: Int,
        val correctionsMeters: List<Double>,
        val udreis: List<Int>,
        val longTerm: List<LongTermCorrection>
    ) : SbasPayload() {
        val firstMaskIndex: Int get() = blockId * 13
    }

    /** MT25: long-term (slow) corrections of up to four satellites */
    data class LongTerm(val corrections: List<LongTermCorrection>) : SbasPayload()

    /** MT26: vertical delays and GIVEIs of 15 IGPs of a band */
    data class IonoDelays(
        val band: Int,
        val blockId: Int,
        val iodi: Int,
        val igps: List<IgpDelay>
    ) : SbasPayload()

    /** A message type that isn't decoded */
    data class Other(val type: Int) : SbasPayload()
}

/** Long-term correction of one satellite, from MT24 or MT25 */
data class LongTermCorrection(
    /** Position in the PRN mask, 1-51 */
    val maskNumber: Int,
    val iode: Int,
    val iodp: Int,
    val dxMeters: Double,
    val dyMeters: Double,
    val dzMeters: Double,
    val clockSeconds: Double,
    /** Includes rates (velocity code 1) */
    val withRates: Boolean
)

/** Ionospheric vertical delay at one IGP */
data class IgpDelay(val delayMeters: Double, val givei: Int) {
    /** GIVEI 15 means not monitored; a delay of 63.875 m means don't use */
    val monitored: Boolean get() = givei < GIVEI_NOT_MONITORED && delayMeters < DELAY_DONT_USE

    companion object {
        const val GIVEI_NOT_MONITORED = 15
        const val DELAY_DONT_USE = 63.875
    }
}

/** Decodes the 212 data bits of an [SbasMessage] by message type */
object SbasDecoder {

    const val MAX_MASK = 51

    fun decode(m: SbasMessage): SbasPayload = when (val type = m.type) {
        0 -> SbasPayload.DoNotUse(m.dataAllZero)
        1 -> prnMask(m)
        2, 3, 4, 5 -> fastCorrections(m, type)
        6 -> integrity(m)
        7 -> fastDegradation(m)
        9 -> geoNavigation(m)
        17 -> geoAlmanac(m)
        18 -> igpMask(m)
        24 -> mixed(m)
        25 -> SbasPayload.LongTerm(longTermHalf(m, 14) + longTermHalf(m, 120))
        26 -> ionoDelays(m)
        else -> SbasPayload.Other(type)
    }

    private fun prnMask(m: SbasMessage): SbasPayload.PrnMask {
        val slots = (1..210).filter { m.u(14 + it - 1, 1) == 1 }
        return SbasPayload.PrnMask(iodp = m.u(224, 2), slots = slots)
    }

    private fun fastCorrections(m: SbasMessage, type: Int) = SbasPayload.FastCorrections(
        type = type,
        iodf = m.u(14, 2),
        iodp = m.u(16, 2),
        correctionsMeters = List(13) { m.s(18 + 12 * it, 12) * FC_SCALE },
        udreis = List(13) { m.u(174 + 4 * it, 4) }
    )

    private fun integrity(m: SbasMessage) = SbasPayload.Integrity(
        iodfs = List(4) { m.u(14 + 2 * it, 2) },
        udreis = List(MAX_MASK) { m.u(22 + 4 * it, 4) }
    )

    private fun fastDegradation(m: SbasMessage) = SbasPayload.FastDegradation(
        latencySeconds = m.u(14, 4),
        iodp = m.u(18, 2),
        ais = List(MAX_MASK) { m.u(22 + 4 * it, 4) }
    )

    private fun geoNavigation(m: SbasMessage) = SbasPayload.GeoNavigation(
        t0Seconds = m.u(22, 13) * 16,
        ura = m.u(35, 4),
        xMeters = m.s(39, 30) * 0.08,
        yMeters = m.s(69, 30) * 0.08,
        zMeters = m.s(99, 25) * 0.4
    )

    private fun geoAlmanac(m: SbasMessage): SbasPayload.GeoAlmanac {
        val entries = (0 until 3).mapNotNull { k ->
            val p = 14 + 67 * k
            val prn = m.u(p + 2, 8)
            // PRN 0 marks an unused entry
            if (prn == 0) null else SbasPayload.GeoAlmanac.Entry(prn, m.u(p + 10, 8))
        }
        return SbasPayload.GeoAlmanac(entries, m.u(215, 11) * 64)
    }

    private fun igpMask(m: SbasMessage) = SbasPayload.IgpMask(
        numBands = m.u(14, 4),
        band = m.u(18, 4),
        iodi = m.u(22, 2),
        positions = (1..201).filter { m.u(24 + it - 1, 1) == 1 }
    )

    private fun mixed(m: SbasMessage) = SbasPayload.Mixed(
        correctionsMeters = List(6) { m.s(14 + 12 * it, 12) * FC_SCALE },
        udreis = List(6) { m.u(86 + 4 * it, 4) },
        iodp = m.u(110, 2),
        blockId = m.u(112, 2),
        iodf = m.u(114, 2),
        longTerm = longTermHalf(m, 120)
    )

    /** One 106-bit half of a long-term correction message, starting at bit [p] */
    private fun longTermHalf(m: SbasMessage, p: Int): List<LongTermCorrection> {
        return if (m.u(p, 1) == 0) {
            val iodp = m.u(p + 103, 2)
            listOf(p + 1, p + 52).mapNotNull { q ->
                val maskNumber = m.u(q, 6)
                if (maskNumber == 0) {
                    null
                } else {
                    LongTermCorrection(
                        maskNumber = maskNumber,
                        iode = m.u(q + 6, 8),
                        iodp = iodp,
                        dxMeters = m.s(q + 14, 9) * 0.125,
                        dyMeters = m.s(q + 23, 9) * 0.125,
                        dzMeters = m.s(q + 32, 9) * 0.125,
                        clockSeconds = m.s(q + 41, 10) * TWO_POW_M31,
                        withRates = false
                    )
                }
            }
        } else {
            val maskNumber = m.u(p + 1, 6)
            if (maskNumber == 0) {
                emptyList()
            } else {
                listOf(
                    LongTermCorrection(
                        maskNumber = maskNumber,
                        iode = m.u(p + 7, 8),
                        iodp = m.u(p + 104, 2),
                        dxMeters = m.s(p + 15, 11) * 0.125,
                        dyMeters = m.s(p + 26, 11) * 0.125,
                        dzMeters = m.s(p + 37, 11) * 0.125,
                        clockSeconds = m.s(p + 48, 11) * TWO_POW_M31,
                        withRates = true
                    )
                )
            }
        }
    }

    private fun ionoDelays(m: SbasMessage) = SbasPayload.IonoDelays(
        band = m.u(14, 4),
        blockId = m.u(18, 4),
        iodi = m.u(217, 2),
        igps = List(15) {
            val p = 22 + 13 * it
            IgpDelay(delayMeters = m.u(p, 9) * 0.125, givei = m.u(p + 9, 4))
        }
    )

    private const val FC_SCALE = 0.125
    private const val TWO_POW_M31 = 1.0 / (1L shl 31)
}
