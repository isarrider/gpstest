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
 * Builds SBAS messages for tests: fields are written at their DO-229 bit offsets (0-based from
 * the preamble), then the preamble, type and CRC-24Q are filled in.
 */
internal class SbasTestMessage(private val type: Int, private val preamble: Int = 0x53) {
    private val bits = ByteArray(32)

    /** Writes [value] (two's complement if negative) into [length] bits at [offset] */
    fun field(offset: Int, length: Int, value: Long): SbasTestMessage {
        val mask = if (length == 64) -1L else (1L shl length) - 1
        Bits.write(bits, offset, length, value and mask)
        return this
    }

    fun field(offset: Int, length: Int, value: Int) = field(offset, length, value.toLong())

    fun build(corruptCrc: Boolean = false): SbasMessage {
        Bits.write(bits, 0, 8, preamble.toLong())
        Bits.write(bits, 8, 6, type.toLong())
        var crc = Crc24q.compute(bits, SbasMessage.CRC_OFFSET)
        if (corruptCrc) crc = crc xor 1
        Bits.write(bits, SbasMessage.CRC_OFFSET, 24, crc.toLong())
        return SbasMessage.fromBits(bits)
    }

    companion object {
        /** MT1 with the given mask slots */
        fun prnMask(iodp: Int, slots: List<Int>): SbasMessage {
            val b = SbasTestMessage(1)
            slots.forEach { b.field(14 + it - 1, 1, 1) }
            return b.field(224, 2, iodp).build()
        }

        /** MT2-5; corrections in meters (multiples of 0.125) */
        fun fast(
            type: Int,
            iodf: Int,
            iodp: Int,
            corrections: List<Double>,
            udreis: List<Int>
        ): SbasMessage {
            val b = SbasTestMessage(type).field(14, 2, iodf).field(16, 2, iodp)
            corrections.forEachIndexed { i, fc -> b.field(18 + 12 * i, 12, (fc / 0.125).toLong()) }
            udreis.forEachIndexed { i, u -> b.field(174 + 4 * i, 4, u) }
            return b.build()
        }

        fun integrity(iodfs: List<Int>, udreis: List<Int>): SbasMessage {
            val b = SbasTestMessage(6)
            iodfs.forEachIndexed { i, v -> b.field(14 + 2 * i, 2, v) }
            udreis.forEachIndexed { i, u -> b.field(22 + 4 * i, 4, u) }
            return b.build()
        }
    }
}
