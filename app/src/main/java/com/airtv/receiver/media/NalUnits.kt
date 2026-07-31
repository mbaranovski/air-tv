package com.airtv.receiver.media

/** Helpers for inspecting Annex-B H.264 / H.265 access units. */
object NalUnits {

    fun isCodecConfig(data: ByteArray, length: Int, isH265: Boolean): Boolean {
        var hasParameterSet = false
        var hasSlice = false
        forEachNalType(data, length, isH265) { type ->
            if (isParameterSet(type, isH265)) hasParameterSet = true
            if (isVideoSlice(type, isH265)) hasSlice = true
        }
        return hasParameterSet && !hasSlice
    }

    fun isKeyFrame(data: ByteArray, length: Int, isH265: Boolean): Boolean {
        var keyFrame = false
        forEachNalType(data, length, isH265) { type ->
            val isIrap = if (isH265) type in 16..21 else type == 5
            if (isIrap) keyFrame = true
        }
        return keyFrame
    }

    fun describe(data: ByteArray, length: Int, isH265: Boolean): String {
        val types = ArrayList<Int>(4)
        forEachNalType(data, length, isH265) { types.add(it) }
        return types.joinToString(",")
    }

    /** H.264: SPS/PPS/SPS-ext/subset-SPS. H.265: VPS/SPS/PPS. */
    private fun isParameterSet(type: Int, isH265: Boolean): Boolean =
        if (isH265) type in 32..34 else type == 7 || type == 8 || type == 13 || type == 15

    private fun isVideoSlice(type: Int, isH265: Boolean): Boolean =
        if (isH265) type in 0..31 else type in 1..5

    private inline fun forEachNalType(
        data: ByteArray,
        length: Int,
        isH265: Boolean,
        action: (Int) -> Unit,
    ) {
        val limit = minOf(length, data.size)
        var i = 0
        while (i + 2 < limit) {
            if (data[i] != 0.toByte() || data[i + 1] != 0.toByte()) {
                i++
                continue
            }
            val startCodeLength = when {
                data[i + 2] == 1.toByte() -> 3
                i + 3 < limit && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (startCodeLength == 0) {
                i++
                continue
            }
            val headerIndex = i + startCodeLength
            if (headerIndex < limit) {
                val header = data[headerIndex].toInt() and 0xFF
                action(if (isH265) (header shr 1) and 0x3F else header and 0x1F)
            }
            i += startCodeLength
        }
    }
}
