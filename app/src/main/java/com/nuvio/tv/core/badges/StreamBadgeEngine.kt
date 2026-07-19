package com.nuvio.tv.core.badges

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.tv.core.device.DeviceCapabilityDetector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Elite-Badges stream picker badges (#159).
 *
 * Matches a stream's RELEASE NAME (the raw torrent/file name — see
 * [StreamReleaseName]) against a bundled ruleset (assets/badges.json, sourced from
 * leonevz/Elite-Badges) to pick at most one logo badge per group (4K, HDR, Dolby
 * Vision, Atmos, HEVC, REMUX, ...). Each matched badge is then compared against
 * THIS device's real capabilities ([DeviceCapabilityDetector.Snapshot]); a badge
 * whose capability exceeds the device gets a "↓ target" downgrade pill telling the
 * user what it will actually be played back as (backend transcodes/downscales it).
 *
 * This is DISPLAY-ONLY. Badges the device can't decode at all are already removed
 * upstream by backend device-cap filtering; we never grey badges out here.
 *
 * All parsing / regex / asset loading is wrapped defensively — a bad ruleset entry,
 * a missing PNG, or an unparseable regex must never crash a stream row.
 */
object StreamBadgeEngine {

    /** Fixed display order of groups (task spec). */
    private val GROUP_ORDER = listOf(
        "resolution",
        "source",
        "video-tech",
        "video-codec",
        "bit-depth",
        "audio-tech",
        "audio-channels",
    )

    private const val ASSET_JSON = "badges.json"
    private const val ASSET_DIR = "badges"

    /** HDR tier ranks matching the ruleset's capRank scale for capType == "hdr". */
    private const val HDR_RANK_DV = 4
    private const val HDR_RANK_HDR10 = 2
    private const val HDR_RANK_HDR = 1
    private const val HDR_RANK_SDR = 0

    data class Filter(
        val id: String,
        val group: String,
        val name: String,
        val regex: Regex?,
        val asset: String,
        val capType: String?,   // resolution | hdr | audioChannels | audioObject | null
        val capRank: Int?,
        val downgradeLabel: String?,
    )

    /** One resolved badge ready to render: its bitmap + optional downgrade target. */
    data class MatchedBadge(
        val id: String,
        val asset: String,
        val bitmap: ImageBitmap?,
        /** Non-null when the badge exceeds device capability → render an amber "↓ target" pill. */
        val downgradeTarget: String?,
    )

    @Volatile
    private var filters: List<Filter>? = null

    // Bitmaps are reused across every row → cache in-memory keyed by asset filename.
    private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()
    // Sentinel for "we tried and failed to load this asset" so we don't retry every row.
    private val failedAssets = ConcurrentHashMap.newKeySet<String>()

    /** Parse + compile the ruleset once (idempotent, thread-safe). */
    private fun ensureLoaded(context: Context): List<Filter> {
        filters?.let { return it }
        synchronized(this) {
            filters?.let { return it }
            val parsed = try {
                val json = context.assets.open(ASSET_JSON).bufferedReader().use { it.readText() }
                val arr = JSONObject(json).getJSONArray("filters")
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pattern = o.optString("pattern", "")
                    val regex = try {
                        if (pattern.isBlank()) null else Regex(pattern)
                    } catch (_: Exception) {
                        null
                    }
                    Filter(
                        id = o.optString("id"),
                        group = o.optString("group"),
                        name = o.optString("name"),
                        regex = regex,
                        asset = o.optString("asset"),
                        capType = o.optString("capType").takeIf { it.isNotBlank() && it != "null" },
                        capRank = if (o.isNull("capRank")) null else o.optInt("capRank"),
                        downgradeLabel = o.optString("downgradeLabel").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
            filters = parsed
            return parsed
        }
    }

    private fun loadBitmap(context: Context, asset: String): ImageBitmap? {
        if (asset.isBlank()) return null
        bitmapCache[asset]?.let { return it }
        if (asset in failedAssets) return null
        return try {
            context.assets.open("$ASSET_DIR/$asset").use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                    ?: run { failedAssets.add(asset); return null }
                val img = bmp.asImageBitmap()
                bitmapCache[asset] = img
                img
            }
        } catch (_: Exception) {
            failedAssets.add(asset)
            null
        }
    }

    /**
     * Match [releaseName] against the ruleset, reduce to one badge per group (highest
     * capRank wins; ties → first in file order), order the groups per [GROUP_ORDER],
     * and compute per-badge downgrade targets vs [caps]. Returns an empty list when
     * nothing matches (caller renders nothing).
     */
    fun badgesFor(
        context: Context,
        releaseName: String?,
        caps: DeviceCapabilityDetector.Snapshot,
    ): List<MatchedBadge> {
        val name = releaseName?.takeIf { it.isNotBlank() } ?: return emptyList()
        val all = ensureLoaded(context)
        if (all.isEmpty()) return emptyList()

        // Group -> best matching filter (kept in file order for tie-break stability).
        val bestByGroup = LinkedHashMap<String, Filter>()
        try {
            for (f in all) {
                val re = f.regex ?: continue
                if (!re.containsMatchIn(name)) continue
                val existing = bestByGroup[f.group]
                if (existing == null) {
                    bestByGroup[f.group] = f
                } else {
                    // Higher capRank wins; ties keep the earlier (existing) entry.
                    val cur = existing.capRank ?: Int.MIN_VALUE
                    val cand = f.capRank ?: Int.MIN_VALUE
                    if (cand > cur) bestByGroup[f.group] = f
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return GROUP_ORDER.mapNotNull { group ->
            val f = bestByGroup[group] ?: return@mapNotNull null
            MatchedBadge(
                id = f.id,
                asset = f.asset,
                bitmap = loadBitmap(context, f.asset),
                downgradeTarget = downgradeTargetFor(f, caps),
            )
        }
    }

    /**
     * Returns the amber pill label (e.g. "1080p", "HDR10", "5.1", "core") when [f]'s
     * capability exceeds [caps], or null when the device natively supports it.
     */
    private fun downgradeTargetFor(f: Filter, caps: DeviceCapabilityDetector.Snapshot): String? {
        val capType = f.capType ?: return null
        return try {
            when (capType) {
                "resolution" -> {
                    val rank = f.capRank ?: return null
                    val deviceMax = caps.maxResolutionHeight
                    if (rank > deviceMax) resolutionLabel(deviceMax) else null
                }
                "hdr" -> {
                    val rank = f.capRank ?: return null
                    val deviceRank = deviceHdrRank(caps)
                    if (rank > deviceRank) hdrLabel(deviceRank) else null
                }
                "audioChannels" -> {
                    val rank = f.capRank ?: return null // channel count (7.1=8, 5.1=6)
                    val deviceMax = deviceMaxChannels(caps)
                    if (rank > deviceMax) channelLabel(deviceMax) else null
                }
                "audioObject" -> {
                    // Object-audio (Atmos/TrueHD/DTS:X/DTS-HD MA). Downgradeable when the
                    // device doesn't list support for that specific format. We map the
                    // badge id → the format string produced by
                    // DeviceCapabilityDetector.detectAudioCaps(). If we can't resolve a
                    // format, fall back to the channel heuristic (object audio typically
                    // needs >= 6ch). Target label is always "core" (the lossy/base track).
                    val supported = objectAudioSupported(f.id, caps)
                    if (supported) null else "core"
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- resolution helpers ---

    private fun resolutionLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        else -> "480p"
    }

    // --- HDR helpers ---

    /** Device's best supported HDR tier on the ruleset's rank scale. */
    private fun deviceHdrRank(caps: DeviceCapabilityDetector.Snapshot): Int = when {
        caps.supportsDolbyVision -> HDR_RANK_DV
        // The device model doesn't distinguish HDR10 vs HDR10+; treat HDR10 support as
        // the HDR10 tier (rank 2). HLG-only panels report the base HDR tier (rank 1).
        caps.supportsHdr10 -> HDR_RANK_HDR10
        caps.supportsHlg -> HDR_RANK_HDR
        else -> HDR_RANK_SDR
    }

    private fun hdrLabel(rank: Int): String = when {
        rank >= HDR_RANK_DV -> "Dolby Vision"
        rank >= 3 -> "HDR10+"
        rank >= HDR_RANK_HDR10 -> "HDR10"
        rank >= HDR_RANK_HDR -> "HDR"
        else -> "SDR"
    }

    // --- audio helpers ---

    private fun deviceMaxChannels(caps: DeviceCapabilityDetector.Snapshot): Int =
        when (caps.audioChannelsLabel) {
            "7.1" -> 8
            "5.1" -> 6
            else -> 2
        }

    private fun channelLabel(channels: Int): String = when {
        channels >= 8 -> "7.1"
        channels >= 6 -> "5.1"
        else -> "Stereo"
    }

    /**
     * Whether the device natively supports a specific object-audio format.
     *
     * DeviceCapabilityDetector.detectAudioCaps() emits these format strings:
     *   "Dolby Atmos", "Dolby TrueHD", "Dolby Digital Plus", "Dolby Digital",
     *   "DTS", "DTS-HD", "DTS:X".
     *
     * TODO(#159): the ruleset has no DTS:X / DTS-HD-MA specific format emitted by the
     * detector beyond "DTS:X"/"DTS-HD"; we map best-effort. DTS-HD MA has no dedicated
     * detector string, so we treat "DTS-HD" support as covering it. If neither the
     * mapped format is present, we fall back to the channel heuristic (object formats
     * need >= 6 channels) rather than always flagging a downgrade.
     */
    private fun objectAudioSupported(badgeId: String, caps: DeviceCapabilityDetector.Snapshot): Boolean {
        val formats = caps.audioFormats
        fun has(vararg names: String) = names.any { n -> formats.any { it.equals(n, ignoreCase = true) } }
        return when (badgeId) {
            "dolby-atmos" -> has("Dolby Atmos")
            "truehd" -> has("Dolby TrueHD")
            "dts-x" -> has("DTS:X")
            "dts-hd-master-audio" -> has("DTS-HD", "DTS-HD MA")
            else -> deviceMaxChannels(caps) >= 6 // heuristic fallback for any future object format
        }
    }
}
