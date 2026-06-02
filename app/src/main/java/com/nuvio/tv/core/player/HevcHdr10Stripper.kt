package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi

@UnstableApi
internal object HevcHdr10Stripper {

    // HEVC SEI NAL types
    private const val NAL_TYPE_PREFIX_SEI = 39
    private const val NAL_TYPE_SUFFIX_SEI = 40

    // SEI payload type for user_data_registered_itu_t_t35
    private const val SEI_TYPE_USER_DATA_REGISTERED = 4

    private const val HDR10_COUNTRY_CODE = 0xB5
    private const val HDR10_PROVIDER_CODE_HI = 0x00
    private const val HDR10_PROVIDER_CODE_LO = 0x3C
    private const val HDR10_USER_ID_HI = 0x00
    private const val HDR10_USER_ID_LO = 0x01

    fun stripHdr10LengthDelimited(
        sample: ByteArray,
        sampleLen: Int,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (sampleLen < nalLengthFieldLength) return null
        val out = java.io.ByteArrayOutputStream(sampleLen)
        var pos = 0
        var changed = false
        while (pos + nalLengthFieldLength <= sampleLen) {
            var nalSize = 0
            for (i in 0 until nalLengthFieldLength) {
                nalSize = (nalSize shl 8) or (sample[pos + i].toInt() and 0xFF)
            }
            val nalStart = pos + nalLengthFieldLength
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) return null
            val nalType = (sample[nalStart].toInt() ushr 1) and 0x3F
            if (nalType == NAL_TYPE_PREFIX_SEI || nalType == NAL_TYPE_SUFFIX_SEI) {
                val rewritten = rewriteSeiNal(sample, nalStart, nalSize)
                if (rewritten != null) {
                    changed = true
                    if (rewritten.isNotEmpty()) {
                        // Write new length prefix + rewritten NAL
                        for (i in nalLengthFieldLength - 1 downTo 0) {
                            out.write((rewritten.size ushr (i * 8)) and 0xFF)
                        }
                        out.write(rewritten)
                    }
                    // else: entire NAL was HDR10+ only, drop it entirely
                } else {
                    // No HDR10+ in this SEI, forward unchanged
                    for (i in nalLengthFieldLength - 1 downTo 0) {
                        out.write((nalSize ushr (i * 8)) and 0xFF)
                    }
                    out.write(sample, nalStart, nalSize)
                }
            } else {
                // Non-SEI NAL: forward unchanged
                for (i in nalLengthFieldLength - 1 downTo 0) {
                    out.write((nalSize ushr (i * 8)) and 0xFF)
                }
                out.write(sample, nalStart, nalSize)
            }
            pos = nalStart + nalSize
        }
        return if (changed) out.toByteArray() else null
    }

    fun stripHdr10AnnexB(sample: ByteArray, sampleLen: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(sampleLen)
        var scan = 0
        var changed = false
        while (scan < sampleLen) {
            val startCode = findStartCode(sample, scan, sampleLen)
            if (startCode < 0) {
                out.write(sample, scan, sampleLen - scan)
                break
            }
            val scLen = startCodeLength(sample, startCode, sampleLen)
            val nalBegin = startCode + scLen
            val nextStartCode = findStartCode(sample, nalBegin + 2, sampleLen)
            val nalEnd = if (nextStartCode < 0) sampleLen else nextStartCode

            if (startCode > scan) out.write(sample, scan, startCode - scan)

            if (nalBegin < nalEnd) {
                val nalType = (sample[nalBegin].toInt() ushr 1) and 0x3F
                val nalSize = nalEnd - nalBegin
                if (nalType == NAL_TYPE_PREFIX_SEI || nalType == NAL_TYPE_SUFFIX_SEI) {
                    val rewritten = rewriteSeiNal(sample, nalBegin, nalSize)
                    if (rewritten != null) {
                        changed = true
                        if (rewritten.isNotEmpty()) {
                            out.write(sample, startCode, scLen) // start code
                            out.write(rewritten)
                        }
                        // else: entire NAL was HDR10+ only, drop including start code
                    } else {
                        out.write(sample, startCode, nalEnd - startCode)
                    }
                } else {
                    out.write(sample, startCode, nalEnd - startCode)
                }
            }
            scan = nalEnd
        }
        return if (changed) out.toByteArray() else null
    }

    private fun rewriteSeiNal(data: ByteArray, nalOffset: Int, nalSize: Int): ByteArray? {
        if (nalSize < 2) return null
        val out = java.io.ByteArrayOutputStream(nalSize)
        // Copy the 2-byte NAL header
        out.write(data[nalOffset].toInt())
        out.write(data[nalOffset + 1].toInt())
        var pos = nalOffset + 2
        val nalEnd = nalOffset + nalSize
        var changed = false
        while (pos < nalEnd) {
            // Skip RBSP stop bit / padding bytes at end
            if (data[pos].toInt() and 0xFF == 0x80 && pos == nalEnd - 1) {
                out.write(0x80)
                break
            }
            // Read SEI payload type (variable length)
            var payloadType = 0
            while (pos < nalEnd && data[pos].toInt() and 0xFF == 0xFF) {
                payloadType += 255
                pos++
            }
            if (pos >= nalEnd) break
            payloadType += data[pos].toInt() and 0xFF
            pos++
            // Read SEI payload size (variable length)
            var payloadSize = 0
            while (pos < nalEnd && data[pos].toInt() and 0xFF == 0xFF) {
                payloadSize += 255
                pos++
            }
            if (pos >= nalEnd) break
            payloadSize += data[pos].toInt() and 0xFF
            pos++
            val payloadStart = pos
            if (payloadStart + payloadSize > nalEnd) break

            if (payloadType == SEI_TYPE_USER_DATA_REGISTERED && isHdr10Payload(data, payloadStart, payloadSize)) {
                // Drop this SEI message entirely
                changed = true
            } else {
                // Keep: re-encode type, size, payload
                writeVariableLengthValue(out, payloadType)
                writeVariableLengthValue(out, payloadSize)
                out.write(data, payloadStart, payloadSize)
            }
            pos = payloadStart + payloadSize
        }
        if (!changed) return null
        val result = out.toByteArray()
        // If only the 2-byte NAL header remains, the NAL is empty — signal full drop
        return if (result.size <= 2) ByteArray(0) else result
    }

    /**
     * Returns true if this user_data_registered_itu_t_t35 payload is an HDR10+ message.
     * Checks country code (0xB5), provider code (0x003C), user identifier (0x0001).
     * Minimum payload size is 5 bytes: 1 country + 2 provider + 2 user_id.
     */
    private fun isHdr10Payload(data: ByteArray, offset: Int, size: Int): Boolean {
        if (size < 5) return false
        val countryCode = data[offset].toInt() and 0xFF
        if (countryCode != HDR10_COUNTRY_CODE) return false
        val providerHi = data[offset + 1].toInt() and 0xFF
        val providerLo = data[offset + 2].toInt() and 0xFF
        if (providerHi != HDR10_PROVIDER_CODE_HI || providerLo != HDR10_PROVIDER_CODE_LO) return false
        val userIdHi = data[offset + 3].toInt() and 0xFF
        val userIdLo = data[offset + 4].toInt() and 0xFF
        return userIdHi == HDR10_USER_ID_HI && userIdLo == HDR10_USER_ID_LO
    }

    private fun writeVariableLengthValue(out: java.io.ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining >= 255) {
            out.write(0xFF)
            remaining -= 255
        }
        out.write(remaining)
    }

    private fun findStartCode(data: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i + 2 < limit) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) return i
                if (i + 3 < limit && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLength(data: ByteArray, offset: Int, limit: Int): Int {
        return if (offset + 3 < limit &&
            data[offset].toInt() == 0 &&
            data[offset + 1].toInt() == 0 &&
            data[offset + 2].toInt() == 0 &&
            data[offset + 3].toInt() == 1
        ) 4 else 3
    }
}