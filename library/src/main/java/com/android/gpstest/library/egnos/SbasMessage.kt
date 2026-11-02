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

import com.android.gpstest.library.osnma.Bits

/**
 * One 250-bit SBAS L1 message (RTCA DO-229 Appendix A): an 8-bit preamble, a 6-bit message
 * type, 212 data bits and a 24-bit CRC (CRC-24Q over the first 226 bits).
 *
 * [bits] holds the 250 bits MSB first, left aligned in 32 bytes. Bit offsets used by the decoder
 * are 0-based from the start of the preamble.
 */
class SbasMessage private constructor(val bits: ByteArray) {

    val preamble: Int get() = Bits.readInt(bits, 0, 8)

    val type: Int get() = Bits.readInt(bits, 8, 6)

    val crc: Int get() = Bits.readInt(bits, CRC_OFFSET, 24)

    val crcOk: Boolean get() = Crc24q.compute(bits, CRC_OFFSET) == crc

    /** One of the three preambles that rotate from message to message (0x53, 0x9A, 0xC6) */
    val preambleOk: Boolean get() = preamble in PREAMBLES

    val valid: Boolean get() = crcOk && preambleOk

    /** Unsigned field at [offset] (0-based from the preamble) */
    fun u(offset: Int, length: Int): Int = Bits.readInt(bits, offset, length)

    /** Two's complement field at [offset] */
    fun s(offset: Int, length: Int): Int {
        val v = Bits.readInt(bits, offset, length)
        return if (v and (1 shl (length - 1)) != 0) v - (1 shl length) else v
    }

    /** True if all 212 data bits are zero (e.g. Message Type 0 of a GEO in test) */
    val dataAllZero: Boolean
        get() = (DATA_OFFSET until CRC_OFFSET).all { Bits.get(bits, it) == 0 }

    fun toHex(): String = Bits.toHex(bits).substring(0, HEX_LENGTH)

    companion object {
        const val LENGTH_BITS = 250
        const val DATA_OFFSET = 14
        const val CRC_OFFSET = 226

        /** 250 bits as hex, the last digit padded with 2 zero bits */
        const val HEX_LENGTH = 63

        val PREAMBLES = setOf(0x53, 0x9A, 0xC6)

        /**
         * Parses a message from hex, as delivered by SISNeT and the EGNOS Message Server: 63 or
         * 64 hex digits, the 250 message bits followed by padding.
         */
        fun fromHex(hex: String): SbasMessage {
            val clean = hex.trim()
            require(clean.length >= HEX_LENGTH && clean.all { it.isHexDigit() }) {
                "Expected at least $HEX_LENGTH hex digits"
            }
            val padded = clean.substring(0, HEX_LENGTH) + "0"
            val bits = Bits.fromHex(padded)
            // Clear the padding so that messages compare and re-encode cleanly
            for (i in LENGTH_BITS until 256) Bits.set(bits, i, 0)
            return SbasMessage(bits)
        }

        /** Wraps 250 message bits (left aligned, at least 32 bytes) */
        fun fromBits(bits: ByteArray): SbasMessage {
            require(bits.size >= 32)
            return SbasMessage(bits.copyOf(32))
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** CRC-24Q, the CRC of SBAS messages (generator 0x1864CFB, initial value 0) */
object Crc24q {
    private const val POLY = 0x864CFB

    /** CRC over the first [lengthBits] bits of [data] (MSB first) */
    fun compute(data: ByteArray, lengthBits: Int): Int {
        var crc = 0
        for (i in 0 until lengthBits) {
            val top = ((crc ushr 23) and 1) xor Bits.get(data, i)
            crc = (crc shl 1) and 0xFFFFFF
            if (top != 0) crc = crc xor POLY
        }
        return crc
    }
}
