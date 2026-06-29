package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.StreamCacheStatus
import com.nuvio.tv.domain.model.StreamInfo
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Renders the backend-provided structured [StreamInfo] in the fixed 6-line layout from the
 * API bridge contract ("Structured stream object — streamInfo"). Both the details stream
 * list (StreamCard) and the in-player source list (StreamItem) call this so TV + mobile look
 * identical. We render THIS instead of the raw torrent name/description.
 *
 *   Line 1: <title>  ·  S<season> E<episode>   [cache badge: Instant | Cached | Not Cached]
 *   Line 2: <quality>            (e.g. "4K UHD", "1080p")
 *   Line 3: <videoCodec> · <dynamicRange joined by " · ">   (e.g. "HEVC · DV · HDR10")
 *   Line 4: <audioCodec>         (e.g. "Atmos", "E-AC3")
 *   Line 5: <audioChannels>      (e.g. "5.1")
 *   Line 6: <sizeLabel> · <bitrateLabel>   (bitrate hidden when null — common for packs)
 *
 * Any line whose value is null/empty is omitted entirely (no empty bullet). Icons are
 * single-tint monochrome matching the text color (no flashy colors).
 */
@Composable
fun StreamInfoContent(
    streamInfo: StreamInfo,
    modifier: Modifier = Modifier
) {
    // Line 1: title · S.E + cache badge.
    val seasonEpisode = formatSeasonEpisode(streamInfo.season, streamInfo.episode)
    val line1 = listOfNotNull(
        streamInfo.title?.takeIf { it.isNotBlank() },
        seasonEpisode
    ).joinToString("  ·  ")

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (line1.isNotBlank()) {
            Text(
                text = line1,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }
        CacheBadge(streamInfo.cacheStatus)
    }

    // Line 2: quality.
    streamInfo.quality?.takeIf { it.isNotBlank() }?.let { quality ->
        Text(
            text = quality,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }

    // Line 3: videoCodec · dynamicRange...
    val line3 = listOfNotNull(streamInfo.videoCodec?.takeIf { it.isNotBlank() })
        .plus(streamInfo.dynamicRange.filter { it.isNotBlank() })
        .joinToString(" · ")
    if (line3.isNotBlank()) {
        Text(
            text = line3,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }

    // Line 4: audioCodec.
    streamInfo.audioCodec?.takeIf { it.isNotBlank() }?.let { audioCodec ->
        Text(
            text = audioCodec,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }

    // Line 5: audioChannels.
    streamInfo.audioChannels?.takeIf { it.isNotBlank() }?.let { channels ->
        Text(
            text = channels,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }

    // Line 6: sizeLabel · bitrateLabel (bitrate hidden when null).
    val line6 = listOfNotNull(
        streamInfo.sizeLabel?.takeIf { it.isNotBlank() },
        streamInfo.bitrateLabel?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    if (line6.isNotBlank()) {
        Text(
            text = line6,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }
}

/** "S4 E7", "S4" (episode null), or null when both are null (e.g. movies). */
private fun formatSeasonEpisode(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "S$season E$episode"
    season != null -> "S$season"
    episode != null -> "E$episode"
    else -> null
}

/**
 * Minimalist monochrome cache badge: a small single-tint glyph + label. Not colorful —
 * the glyph and text use the app's secondary text color so the row stays monochrome.
 *   instant -> "Instant" (bolt), cached -> "Cached" (check), not_cached -> "Not Cached" (cloud).
 */
@Composable
private fun CacheBadge(status: StreamCacheStatus) {
    val (icon: ImageVector, label: String) = when (status) {
        StreamCacheStatus.INSTANT -> Icons.Default.Bolt to "Instant"
        StreamCacheStatus.CACHED -> Icons.Default.Check to "Cached"
        StreamCacheStatus.NOT_CACHED -> Icons.Default.CloudQueue to "Not Cached"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(14.dp),
            tint = NuvioTheme.extendedColors.textSecondary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }
}
