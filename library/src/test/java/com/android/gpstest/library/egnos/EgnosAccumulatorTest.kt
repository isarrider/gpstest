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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EgnosAccumulatorTest {

    private val t0 = 2443L * 604800 + 3600

    private val mask = listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 136)

    private fun fc13(start: Double) = List(13) { start + 0.125 * it }

    @Test
    fun fastCorrectionsNeedTheMaskWithTheSameIodp() {
        val acc = EgnosAccumulator()
        // Corrections before any mask can't be assigned to satellites
        acc.add(SbasTestMessage.fast(2, 0, 1, fc13(1.0), List(13) { 3 }), t0)
        assertTrue(acc.snapshot().satellites.isEmpty())

        acc.add(SbasTestMessage.prnMask(1, mask), t0 + 1)
        acc.add(SbasTestMessage.fast(2, 0, 1, fc13(1.0), List(13) { 3 }), t0 + 2)
        acc.add(SbasTestMessage.fast(3, 0, 1, fc13(-2.0), List(13) { 14 }), t0 + 3)
        // Wrong IODP: ignored
        acc.add(SbasTestMessage.fast(3, 0, 2, fc13(50.0), List(13) { 0 }), t0 + 4)

        val s = acc.snapshot()
        assertEquals(1, s.iodp)
        assertEquals(mask, s.satellites.map { it.slot })
        val g01 = s.satellites[0]
        assertEquals("G01", g01.label)
        assertEquals(1.0, g01.fastCorrectionMeters!!, 1e-12)
        assertEquals(3, g01.udrei)
        assertEquals(2L, g01.fastAgeSeconds)
        assertEquals(1.75, g01.udreMeters!!, 1e-12)
        assertTrue(g01.usable)
        // The 14th satellite of the mask is in MT3
        val g27 = s.satellites[13]
        assertEquals("G27", g27.label)
        assertEquals(-2.0, g27.fastCorrectionMeters!!, 1e-12)
        assertTrue(g27.notMonitored)
        assertFalse(g27.usable)
        assertEquals("S136", s.satellites.last().label)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25), s.usableSatellites.map { it.slot })
    }

    @Test
    fun integrityUpdatesUdreisWithMatchingIodf() {
        val acc = EgnosAccumulator()
        acc.add(SbasTestMessage.prnMask(0, mask), t0)
        acc.add(SbasTestMessage.fast(2, 2, 0, fc13(0.0), List(13) { 4 }), t0 + 1)
        // IODF of block 0 doesn't match: no change
        acc.add(SbasTestMessage.integrity(listOf(1, 0, 0, 0), List(51) { 15 }), t0 + 2)
        assertEquals(4, acc.snapshot().satellites[0].udrei)
        // Matching IODF
        acc.add(SbasTestMessage.integrity(listOf(2, 0, 0, 0), List(51) { 15 }), t0 + 3)
        val s = acc.snapshot().satellites[0]
        assertEquals(15, s.udrei)
        assertTrue(s.doNotUse)
        assertNull(s.udreMeters)
    }

    @Test
    fun oldCorrectionsAreNotUsable() {
        val acc = EgnosAccumulator()
        acc.add(SbasTestMessage.prnMask(0, mask), t0)
        acc.add(SbasTestMessage.fast(2, 0, 0, fc13(0.0), List(13) { 2 }), t0)
        assertTrue(acc.snapshot().satellites[0].usable)
        // A later message moves "now" forward
        acc.add(SbasTestMessage.prnMask(0, mask), t0 + EgnosAccumulator.FAST_TIMEOUT_SECONDS + 1)
        assertFalse(acc.snapshot().satellites[0].usable)
    }

    @Test
    fun mt0SetsDontUseForAMinute() {
        val acc = EgnosAccumulator()
        acc.add(SbasTestMessage(0).build(), t0)
        assertTrue(acc.snapshot().dontUse)
        acc.add(SbasTestMessage.prnMask(0, mask), t0 + 61)
        assertFalse(acc.snapshot().dontUse)
    }

    @Test
    fun crcErrorsAreCountedNotUsed() {
        val acc = EgnosAccumulator()
        acc.add(SbasTestMessage(1).build(corruptCrc = true), t0)
        val s = acc.snapshot()
        assertEquals(1, s.crcErrors)
        assertEquals(0, s.messages)
        assertFalse(s.hasMask)
    }

    @Test
    fun longTermCorrectionsByMaskNumber() {
        val acc = EgnosAccumulator()
        acc.add(SbasTestMessage.prnMask(1, mask), t0)
        val lt = SbasTestMessage(25)
            .field(14, 1, 0)
            .field(15, 6, 3).field(21, 8, 10).field(29, 9, 8)
            .field(117, 2, 1)
            .build()
        acc.add(lt, t0 + 1)
        val s = acc.snapshot().satellites
        assertEquals(5, s[2].slot)
        assertEquals(1.0, s[2].longTerm!!.dxMeters, 1e-12)
        assertTrue(s[2].hasCurrentLongTerm)
        assertNull(s[0].longTerm)
    }

    @Test
    fun ionosphereCountsMonitoredIgps() {
        val acc = EgnosAccumulator()
        val igpMask = SbasTestMessage(18).field(14, 4, 1).field(18, 4, 0).field(22, 2, 1)
        (0 until 20).forEach { igpMask.field(24 + it, 1, 1) }
        acc.add(igpMask.build(), t0)
        val block0 = SbasTestMessage(26).field(14, 4, 0).field(18, 4, 0).field(217, 2, 1)
        (0 until 15).forEach { block0.field(22 + 13 * it, 9, 16).field(31 + 13 * it, 4, if (it < 10) 5 else 15) }
        acc.add(block0.build(), t0 + 1)
        val s = acc.snapshot()
        assertEquals(1, s.igpBands)
        assertEquals(20, s.igpsInMask)
        assertEquals(10, s.igpsMonitored)
    }

    @Test
    fun emsLine() {
        val m = SbasTestMessage.prnMask(0, mask)
        val r = EmsParser.parseLine("136 26 11 01 14 00 05 1 ${m.toHex()}")!!
        assertEquals(136, r.prn)
        assertEquals(1, r.type)
        assertTrue(r.message.valid)
        // 2026-11-01 is the Sunday that starts GPS week 2443
        assertEquals(2443L * 604800 + 14 * 3600 + 5, r.gpsSeconds)
        assertNull(EmsParser.parseLine("# comment"))
        assertNull(EmsParser.parseLine("136 26 11 01 14 00 05 1 XYZ"))
    }
}
