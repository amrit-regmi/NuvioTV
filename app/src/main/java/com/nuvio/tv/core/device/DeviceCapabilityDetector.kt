package com.nuvio.tv.core.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
        // Real, detected audio capability (never hardcoded). Mirrors NuvioMobile's DeviceCapabilityRegistrar.
        val audioChannelsLabel: String = "2.0",
        val audioFormats: List<String> = listOf("AAC"),
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

    /**
     * Detect real device capabilities. [context] is optional (nullable) purely so existing callers
     * that only had a [Display] don't break, but pass a Context whenever possible so the audio
     * capability probe (decoders + HDMI/ARC/eARC sinks + bitstream passthrough) can run.
     */
    fun detect(display: Display?, context: Context? = null): Snapshot {
        val decode = detectDecodeCaps()
        // Cap the reported resolution by what the hardware can DECODE, not by the panel. A device with
        // a sub-4K panel but a 4K-capable HEVC decoder (common on tablets/boxes) can still decode a
        // 2160p source and downscale it; the decoder limit is the real black-screen guard, the panel
        // is not. Never report below 1080p. This mirrors NuvioMobile's detectMaxResolution.
        val (panelW, panelH) = detectMaxResolution(display)
        val decodeHeight = if (decode.maxHeight > 0) decode.maxHeight else panelH
        val effectiveHeight = maxOf(decodeHeight, 1080)
        val (maxW, maxH) = when {
            effectiveHeight >= 2160 -> 3840 to 2160
            effectiveHeight >= 1080 -> 1920 to 1080
            else -> 1280 to 720
        }
        // Intersect the panel's advertised HDR formats with what a decoder can actually produce —
        // otherwise the backend serves an HDR remux that black-screens on decode. Mirrors mobile.
        val (panelHdr10, panelDv, panelHlg) = detectHdrCapabilities(display)
        val hdr10 = panelHdr10 && decode.hevcHdr10
        val dv = panelDv && decode.dolbyVision
        val hlg = panelHlg && decode.hevc10bit
        val audio = detectAudioCaps(context)
        return Snapshot(
            supportsHdr10 = hdr10, supportsDolbyVision = dv, supportsHlg = hlg,
            supportsHevc = decode.hevc, supportsAv1 = decode.av1, supportsHevcMain10 = decode.hevc10bit,
            maxResolutionWidth = maxW, maxResolutionHeight = maxH,
            audioChannelsLabel = audio.channelsLabel, audioFormats = audio.formats,
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

    /**
     * What the device can actually DECODE (not what the panel advertises): the max decodable video
     * height, whether HEVC exposes a 10-bit / HDR10 profile, whether a Dolby Vision decoder exists,
     * and codec support. Mirrors NuvioMobile's detectDecodeCaps + detectCodecs.
     */
    private data class DecodeCaps(
        val maxHeight: Int,
        val hevcHdr10: Boolean,
        val hevc10bit: Boolean,
        val dolbyVision: Boolean,
        val hevc: Boolean,
        val av1: Boolean,
    )

    private fun detectDecodeCaps(): DecodeCaps {
        var maxHeight = 0
        var hevcHdr10 = false
        var hevc10 = false
        var dv = false
        var hevc = false
        var av1 = false
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val t = type.lowercase()
                    val isHevc = "hevc" in t || "h265" in t
                    val isAvc = "avc" in t || "h264" in t
                    val isAv1 = "av01" in t || "av1" in t
                    if ("dolby-vision" in t || "dolbyvision" in t) { dv = true; continue }
                    if (isHevc) hevc = true
                    if (isAv1) av1 = true
                    if (!isHevc && !isAvc && !isAv1) continue
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        val h = runCatching { vc.supportedHeights.upper }.getOrNull() ?: 0
                        if (h > maxHeight) maxHeight = h
                    }
                    if (isHevc) {
                        for (pl in caps.profileLevels) {
                            when (pl.profile) {
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> hevc10 = true
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> {
                                    hevc10 = true; hevcHdr10 = true
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return DecodeCaps(maxHeight, hevcHdr10, hevc10, dv, hevc, av1)
    }

    private data class AudioCaps(
        val channelsLabel: String,
        val formats: List<String>,
    )

    /**
     * What the device can actually RENDER for audio — detected, never hardcoded — so a TV passing
     * Atmos/DTS:X through HDMI to an AVR, a box with only stereo, or a device with on-board surround
     * decoders each report their real capability. Ported verbatim (structure) from NuvioMobile's
     * DeviceCapabilityRegistrar.detectAudioCaps. Three signals are unioned:
     *
     *  1. On-board decoders — enumerate MediaCodecList audio decoders → format labels
     *     (ac3→Dolby Digital, eac3→Dolby Digital Plus, eac3-joc/ac4→Dolby Atmos, true-hd→Dolby TrueHD,
     *     dts→DTS, dts-hd→DTS-HD, dts-uhd→DTS:X) + maxInputChannelCount.
     *  2. HDMI / ARC / eARC sink channel counts via AudioManager.getDevices() (API 23+).
     *  3. Bitstream passthrough probe via AudioTrack.isDirectPlaybackSupported (API 29+) for
     *     E-AC3-JOC / AC4 / E-AC3 / AC3 / TrueHD / DTS / DTS-HD.
     *
     * AAC is always included. Channel label: >=8→"7.1", >=6→"5.1", else "2.0". Every API-gated
     * constant is guarded by Build.VERSION.SDK_INT.
     */
    private fun detectAudioCaps(context: Context?): AudioCaps {
        val formats = linkedSetOf("AAC")
        var maxChannels = 2

        // (1) On-board decoders.
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val t = type.lowercase()
                    if (!t.startsWith("audio/")) continue
                    when {
                        "eac3-joc" in t -> { formats += "Dolby Digital Plus"; formats += "Dolby Atmos" }
                        "ac4" in t -> formats += "Dolby Atmos"
                        "eac3" in t -> formats += "Dolby Digital Plus"
                        "ac3" in t -> formats += "Dolby Digital"
                        "true-hd" in t || "truehd" in t -> formats += "Dolby TrueHD"
                        "dts.uhd" in t || "dts-uhd" in t -> formats += "DTS:X"
                        "dts.hd" in t || "dts-hd" in t -> formats += "DTS-HD"
                        "dts" in t -> formats += "DTS"
                    }
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                    val ch = runCatching { caps.audioCapabilities?.maxInputChannelCount ?: 0 }.getOrNull() ?: 0
                    if (ch > maxChannels) maxChannels = ch
                }
            }
        } catch (_: Exception) {
        }

        // (2) HDMI / ARC / eARC passthrough to an external AVR or soundbar.
        if (context != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
                    for (d in devices) {
                        if (d.type == AudioDeviceInfo.TYPE_HDMI ||
                            d.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && d.type == AudioDeviceInfo.TYPE_HDMI_EARC)
                        ) {
                            val chans = d.channelCounts
                            if (chans != null && chans.isNotEmpty()) {
                                val hi = chans.max()
                                if (hi > maxChannels) maxChannels = hi
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // (2b) Direct-playback (bitstream passthrough) probe — a sink can pass Atmos/DTS:X even when
        // the device has no software decoder for them. API 29+ only; guarded.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fun supports(encoding: Int): Boolean = runCatching {
                    val fmt = AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .setSampleRate(48000)
                        .build()
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                    AudioTrack.isDirectPlaybackSupported(fmt, attrs)
                }.getOrDefault(false)

                if (supports(AudioFormat.ENCODING_E_AC3_JOC)) { formats += "Dolby Digital Plus"; formats += "Dolby Atmos" }
                if (supports(AudioFormat.ENCODING_AC4)) formats += "Dolby Atmos"
                if (supports(AudioFormat.ENCODING_E_AC3)) formats += "Dolby Digital Plus"
                if (supports(AudioFormat.ENCODING_AC3)) formats += "Dolby Digital"
                if (supports(AudioFormat.ENCODING_DOLBY_TRUEHD)) formats += "Dolby TrueHD"
                if (supports(AudioFormat.ENCODING_DTS)) formats += "DTS"
                if (supports(AudioFormat.ENCODING_DTS_HD)) formats += "DTS-HD"
            }
        } catch (_: Exception) {
        }

        val label = when {
            maxChannels >= 8 -> "7.1"
            maxChannels >= 6 -> "5.1"
            else -> "2.0"
        }
        return AudioCaps(label, formats.toList())
    }
}
