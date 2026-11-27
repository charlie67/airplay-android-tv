package com.airplayserver.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Annex-B helpers used to route mirroring packets. */
class H264Test {
    // Parameter sets of a 720x1560 High-profile stream (height needs SPS cropping).
    private val sps = hex("0000000167640020acb2016818bf2e022000000300200000" + "0781e30649")
    private val pps = hex("0000000168ebc3cb22c0")
    private val idrSlice = hex("0000000165888400ff")
    private val pSlice = hex("00000001419a2400ff")

    @Test
    fun parsesCroppedSize() {
        assertEquals(720 to 1560, H264.parseSpsSize(sps))
    }

    @Test
    fun splitsParameterSets() {
        val sets = H264.splitParameterSets(sps + pps)
        assertNotNull(sets)
        assertArrayEquals(sps, sets!!.first)
        assertArrayEquals(pps, sets.second)
    }

    @Test
    fun splitParameterSetsNeedsBoth() {
        assertNull(H264.splitParameterSets(idrSlice))
        assertNull(H264.splitParameterSets(sps))
    }

    @Test
    fun classifiesPackets() {
        // A keyframe carrying its own parameter sets: configure and decode.
        assertEquals(setOf(7, 8, 5), H264.nalTypes(sps + pps + idrSlice))
        assertEquals(setOf(1), H264.nalTypes(pSlice))
        assertTrue(H264.containsIdr(sps + pps + idrSlice))
        assertFalse(H264.containsIdr(pSlice))
    }

    @Test
    fun handlesThreeByteStartCodes() {
        val data = hex("000001") + idrSlice.copyOfRange(4, idrSlice.size)
        assertEquals(listOf(3), H264.nalOffsets(data))
        assertTrue(H264.containsIdr(data))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
