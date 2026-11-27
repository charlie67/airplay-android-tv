package com.airplayserver.tv

/** Minimal Annex-B H.264 helpers. */
object H264 {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** Returns the offsets of NAL payloads (just after each 3- or 4-byte start code). */
    fun nalOffsets(data: ByteArray): List<Int> {
        val offsets = ArrayList<Int>(4)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    offsets.add(i + 3); i += 3; continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    offsets.add(i + 4); i += 4; continue
                }
            }
            i++
        }
        return offsets
    }

    const val NAL_SLICE = 1

    /** NAL unit types present in an Annex-B buffer. */
    fun nalTypes(data: ByteArray): Set<Int> =
        nalOffsets(data).filter { it < data.size }.map { data[it].toInt() and 0x1F }.toSet()

    fun containsIdr(data: ByteArray): Boolean =
        nalOffsets(data).any { it < data.size && (data[it].toInt() and 0x1F) == NAL_IDR }

    /** Splits an Annex-B SPS+PPS packet into (SPS, PPS), each with its start code. */
    fun splitParameterSets(data: ByteArray): Pair<ByteArray, ByteArray>? {
        val offsets = nalOffsets(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for ((index, offset) in offsets.withIndex()) {
            if (offset >= data.size) continue
            val start = offset - startCodeLength(data, offset)
            val end = if (index + 1 < offsets.size) {
                offsets[index + 1] - startCodeLength(data, offsets[index + 1])
            } else data.size
            val nal = data.copyOfRange(start, end)
            when (data[offset].toInt() and 0x1F) {
                NAL_SPS -> if (sps == null) sps = nal
                NAL_PPS -> if (pps == null) pps = nal
            }
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    private fun startCodeLength(data: ByteArray, payloadOffset: Int): Int =
        if (payloadOffset >= 4 && data[payloadOffset - 4].toInt() == 0) 4 else 3

    /** Parses the displayed (cropped) picture size from an SPS with start code. */
    fun parseSpsSize(spsWithStartCode: ByteArray): Pair<Int, Int>? = runCatching {
        val offset = nalOffsets(spsWithStartCode).firstOrNull() ?: return null
        val reader = BitReader(unescape(spsWithStartCode, offset + 1))
        val profile = reader.bits(8)
        reader.bits(16) // constraint flags + level
        reader.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = reader.ue()
            if (chromaFormat == 3) reader.bits(1)
            reader.ue(); reader.ue() // bit depths
            reader.bits(1)
            if (reader.bits(1) == 1) {
                val count = if (chromaFormat != 3) 8 else 12
                for (i in 0 until count) {
                    if (reader.bits(1) == 1) skipScalingList(reader, if (i < 6) 16 else 64)
                }
            }
        }
        reader.ue() // log2_max_frame_num_minus4
        when (reader.ue()) { // pic_order_cnt_type
            0 -> reader.ue()
            1 -> {
                reader.bits(1); reader.se(); reader.se()
                repeat(reader.ue()) { reader.se() }
            }
        }
        reader.ue() // max_num_ref_frames
        reader.bits(1)
        val widthMbs = reader.ue() + 1
        val heightMapUnits = reader.ue() + 1
        val frameMbsOnly = reader.bits(1)
        if (frameMbsOnly == 0) reader.bits(1)
        reader.bits(1) // direct_8x8_inference
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightMapUnits * 16
        if (reader.bits(1) == 1) {
            val left = reader.ue(); val right = reader.ue()
            val top = reader.ue(); val bottom = reader.ue()
            val cropX = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            width -= cropX * (left + right)
            height -= cropY * (top + bottom)
        }
        if (width in 16..8192 && height in 16..8192) width to height else null
    }.getOrNull()

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun skipScalingList(reader: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + reader.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Removes emulation-prevention bytes (00 00 03). */
    private fun unescape(data: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size - from)
        var zeros = 0
        for (i in from until data.size) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var value = 0
            repeat(n) {
                val byte = data[pos ushr 3].toInt()
                value = (value shl 1) or ((byte ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return value
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                require(zeros < 32)
            }
            return ((1 shl zeros) - 1) + bits(zeros)
        }
        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
