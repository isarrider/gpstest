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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbasDecoderTest {

    @Test
    fun crc24q_checkValue() {
        // The standard check value of CRC-24Q (CRC-24/LTE-A) over "123456789"
        val data = "123456789".toByteArray()
        assertEquals(0xCDE703, Crc24q.compute(data, data.size * 8))
    }

    @Test
    fun message_crcAndPreamble() {
        val ok = SbasTestMessage(2, preamble = 0x9A).build()
        assertTrue(ok.valid)
        assertEquals(2, ok.type)
        assertFalse(SbasTestMessage(2).build(corruptCrc = true).valid)
        assertFalse(SbasTestMessage(2, preamble = 0x12).build().valid)
    }

    @Test
    fun message_hexRoundTrip() {
        val m = SbasTestMessage.prnMask(2, listOf(1, 5, 32, 138))
        assertEquals(63, m.toHex().length)
        val again = SbasMessage.fromHex(m.toHex())
        assertTrue(again.valid)
        // 64 digits (padded to whole bytes) are accepted too
        assertTrue(SbasMessage.fromHex(m.toHex() + "0").valid)
    }

    @Test
    fun mt1_prnMask() {
        val p = SbasDecoder.decode(SbasTestMessage.prnMask(3, listOf(2, 7, 31, 40, 136)))
        p as SbasPayload.PrnMask
        assertEquals(3, p.iodp)
        assertEquals(listOf(2, 7, 31, 40, 136), p.slots)
    }

    @Test
    fun mt2_fastCorrections() {
        val corrections = listOf(1.5, -2.25, 0.0, 255.875, -256.0) + List(8) { 0.125 * it }
        val udreis = listOf(0, 13, 14, 15) + List(9) { 5 }
        val p = SbasDecoder.decode(SbasTestMessage.fast(3, 1, 2, corrections, udreis))
        p as SbasPayload.FastCorrections
        assertEquals(3, p.type)
        assertEquals(13, p.firstMaskIndex)
        assertEquals(1, p.iodf)
        assertEquals(2, p.iodp)
        assertEquals(corrections, p.correctionsMeters)
        assertEquals(udreis, p.udreis)
    }

    @Test
    fun mt6_integrity() {
        val udreis = List(51) { it % 16 }
        val p = SbasDecoder.decode(SbasTestMessage.integrity(listOf(0, 1, 2, 3), udreis))
        p as SbasPayload.Integrity
        assertEquals(listOf(0, 1, 2, 3), p.iodfs)
        assertEquals(udreis, p.udreis)
    }

    @Test
    fun mt25_longTerm_bothVelocityCodes() {
        val m = SbasTestMessage(25)
            // First half, velocity code 0, two satellites
            .field(14, 1, 0)
            .field(15, 6, 4).field(21, 8, 77).field(29, 9, -8).field(38, 9, 16).field(47, 9, -1)
            .field(56, 10, -512)
            .field(66, 6, 0)
            .field(117, 2, 1)
            // Second half, velocity code 1
            .field(120, 1, 1)
            .field(121, 6, 9).field(127, 8, 200).field(135, 11, 1023).field(146, 11, -1024)
            .field(157, 11, 3).field(168, 11, -3).field(224, 2, 1)
            .build()
        val p = SbasDecoder.decode(m) as SbasPayload.LongTerm
        assertEquals(2, p.corrections.size)
        val a = p.corrections[0]
        assertEquals(4, a.maskNumber)
        assertEquals(77, a.iode)
        assertEquals(1, a.iodp)
        assertEquals(-1.0, a.dxMeters, 1e-12)
        assertEquals(2.0, a.dyMeters, 1e-12)
        assertEquals(-0.125, a.dzMeters, 1e-12)
        assertEquals(-512.0 / (1L shl 31), a.clockSeconds, 1e-18)
        assertFalse(a.withRates)
        val b = p.corrections[1]
        assertEquals(9, b.maskNumber)
        assertEquals(200, b.iode)
        assertEquals(1, b.iodp)
        assertEquals(127.875, b.dxMeters, 1e-12)
        assertEquals(-128.0, b.dyMeters, 1e-12)
        assertEquals(0.375, b.dzMeters, 1e-12)
        assertTrue(b.withRates)
    }

    @Test
    fun mt18_mt26_ionosphere() {
        val mask = SbasTestMessage(18)
            .field(14, 4, 5).field(18, 4, 3).field(22, 2, 2)
            .field(24 + 0, 1, 1).field(24 + 9, 1, 1).field(24 + 200, 1, 1)
            .build()
        val m = SbasDecoder.decode(mask) as SbasPayload.IgpMask
        assertEquals(5, m.numBands)
        assertEquals(3, m.band)
        assertEquals(2, m.iodi)
        assertEquals(listOf(1, 10, 201), m.positions)

        val delays = SbasTestMessage(26)
            .field(14, 4, 3).field(18, 4, 0)
            .field(22, 9, 24).field(31, 4, 3)
            .field(35, 9, 511).field(44, 4, 2)
            .field(48, 9, 8).field(57, 4, 15)
            .field(217, 2, 2)
            .build()
        val d = SbasDecoder.decode(delays) as SbasPayload.IonoDelays
        assertEquals(3, d.band)
        assertEquals(0, d.blockId)
        assertEquals(2, d.iodi)
        assertEquals(3.0, d.igps[0].delayMeters, 1e-12)
        assertTrue(d.igps[0].monitored)
        assertFalse("delay 63.875 m means don't use", d.igps[1].monitored)
        assertFalse("GIVEI 15 means not monitored", d.igps[2].monitored)
    }

    @Test
    fun mt17_geoAlmanac() {
        val m = SbasTestMessage(17)
            .field(14 + 2, 8, 136).field(14 + 10, 8, 0x10)
            .field(81 + 2, 8, 121).field(81 + 10, 8, 0x11)
            .field(215, 11, 100)
            .build()
        val p = SbasDecoder.decode(m) as SbasPayload.GeoAlmanac
        assertEquals(listOf(136, 121), p.entries.map { it.prn })
        assertEquals(1, p.entries[0].providerId)
        assertTrue(p.entries[0].ranging)
        assertFalse(p.entries[1].ranging)
        assertEquals(6400, p.t0Seconds)
    }

    @Test
    fun mt0_doNotUse() {
        val zero = SbasDecoder.decode(SbasTestMessage(0).build()) as SbasPayload.DoNotUse
        assertTrue(zero.allZero)
        val withData = SbasDecoder.decode(SbasTestMessage(0).field(20, 4, 9).build())
        assertFalse((withData as SbasPayload.DoNotUse).allZero)
    }

    @Test
    fun otherTypes() {
        assertEquals(SbasPayload.Other(63), SbasDecoder.decode(SbasTestMessage(63).build()))
    }
}
