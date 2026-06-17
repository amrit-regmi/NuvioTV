package com.nuvio.tv.core.device

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityDetector @Inject constructor() {

    data class Snapshot(
        val supportsHdr10: Boolean,
        val supportsDolbyVision: Boolean,
        val supportsHlg: Boolean,
        val supportsHevc: Boolean,
        val supportsAv1: Boolean,
        val supportsHevcMain10: Boolean,
        val maxResolutionWidth: Int,
        val maxResolutionHeight: Int,
    ) {
        val supports4k: Boolean get() = maxResolutionWidth >= 3840
        val supports1080p: Boolean get() = maxResolutionWidth >= 1920

        fun suggestedProfileId(): String = when {
            supports4k && (supportsHdr10 || supportsDolbyVision) -> "4k_hdr"
            supports4k -> "4k_sdr"
            supports1080p && (supportsHdr10 || supportsDolbyVision) -> "1080p"
            else -> "standard"
        }

        companion object {
            val Unknown = Snapshot(
                supportsHdr10 = false, supportsDolbyVision = false,
                supportsHlg = false, supportsHevc = false,
                supportsAv1 = false, supportsHevcMain10 = false,
                maxResolutionWidth = 1920, maxResolutionHeight = 1080,
            )
        }
    }

    fun detect(display: Display?): Snapshot {
        val (maxW, maxH) = detectMaxResolution(display)
        val (hdr10, dv, hlg) = detectHdrCapabilities(display)
        val (hevc, av1, hevcMain10) = detectCodecCapabilities()
        return Snapshot(
            supportsHdr10 = hdr10, supportsDolbyVision = dv, supportsHlg = hlg,
            supportsHevc = hevc, supportsAv1 = av1, supportsHevcMain10 = hevcMain10,
            maxResolutionWidth = maxW, maxResolutionHeight = maxH,
        )
    }

    private fun detectMaxResolution(display: Display?): Pair<Int, Int> {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 1920 to 1080
        return display.supportedModes
            .maxByOrNull { it.physicalWidth * it.physicalHeight }
            ?.let { it.physicalWidth to it.physicalHeight }
            ?: (1920 to 1080)
    }

    private fun detectHdrCapabilities(display: Display?): Triple<Boolean, Boolean, Boolean> {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Triple(false, false, false)
        val hdrCaps = display.hdrCapabilities ?: return Triple(false, false, false)
        val types = hdrCaps.supportedHdrTypes.toSet()
        return Triple(
            Display.HdrCapabilities.HDR_TYPE_HDR10 in types || Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
            Display.HdrCapabilities.HDR_TYPE_HLG in types,
        )
    }

    private fun detectCodecCapabilities(): Triple<Boolean, Boolean, Boolean> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        var hevc = false; var av1 = false; var hevcMain10 = false
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) {
                for (mime in info.supportedTypes) {
                    when (mime.lowercase()) {
                        "video/hevc" -> {
                            hevc = true
                            val caps = info.getCapabilitiesForType(mime)
                            if (caps.profileLevels.any {
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                                }) hevcMain10 = true
                        }
                        "video/av01" -> av1 = true
                    }
                }
            }
        }
        return Triple(hevc, av1, hevcMain10)
    }
}
