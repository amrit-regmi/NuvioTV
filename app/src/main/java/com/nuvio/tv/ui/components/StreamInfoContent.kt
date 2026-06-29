package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.StreamCacheStatus
import com.nuvio.tv.domain.model.StreamInfo
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Renders the backend-provided structured [StreamInfo] in the unified stream-row layout
 * shared with NuvioMobile (see API bridge contract "Structured stream object — streamInfo").
 * Both the details stream list (StreamCard) and the in-player source list (StreamItem) call
 * this so TV + mobile look identical. We render THIS instead of the raw torrent name/description.
 *
 *   Title line:  <title> (<year>)                                   [cache pill]
 *   Quality:     [Hd]      S05 E16  ·  1080p  ·  BluRay
 *   Video+audio: [Movie]   HEVC · 10bit · DV · HDR10     [Speaker]  DTS-HD MA 5.1
 *   Size:        [Storage] 9 GB  ·  56m  ·  18 Mbps
 *   Languages:   [Language] EN · ES                      [Subtitles] EN · ES
 *
 * Every icon is MONOCHROME (textSecondary). The ONLY coloured element is the cache pill
 * (Instant=gold, Cached=green, Not Cached=grey). Any line/segment whose text is blank is
 * omitted entirely (no empty bullet). On TV the optional [currentLabel] "Playing" badge
 * renders under the title line (D-pad focus model is handled by the surrounding Card).
 */
@Composable
fun StreamInfoContent(
    streamInfo: StreamInfo,
    modifier: Modifier = Modifier,
    isCurrentStream: Boolean = false,
    currentLabel: String? = null
) {
    val info = streamInfo
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )
    val lineStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
    val secondary = NuvioTheme.extendedColors.textSecondary
    val dot = "  ·  "

    // Title (+ year) only; cache pill trails on the right.
    val titleLine = buildString {
        append(info.title?.takeIf { it.isNotBlank() } ?: "Stream")
        info.year?.let { append(" (").append(it).append(")") }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = titleLine,
            modifier = Modifier.weight(1f),
            style = titleStyle,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        CacheStatusPill(info.cacheStatus)
    }

    if (isCurrentStream && !currentLabel.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        CurrentStreamBadge(label = currentLabel)
    }

    // Quality: [Hd] S05 E16 · 1080p · BluRay
    val seText = buildString {
        info.season?.let { append("S").append(it.toString().padStart(2, '0')) }
        info.episode?.let {
            if (isNotEmpty()) append(" ")
            append("E").append(it.toString().padStart(2, '0'))
        }
    }
    val qualityText = listOfNotNull(
        seText.takeIf { it.isNotBlank() },
        info.resolution?.takeIf { it.isNotBlank() },
        info.source?.takeIf { it.isNotBlank() }
    ).joinToString(dot)
    if (qualityText.isNotBlank()) {
        InfoRow {
            InfoSegment(Icons.Rounded.Hd, qualityText, secondary, lineStyle, Modifier.weight(1f, fill = false))
        }
    }

    // Video + audio on one line: [Movie] HEVC · 10bit · HDR10   [Speaker] DTS-HD MA 5.1
    val videoText = (
        listOfNotNull(
            info.videoCodec?.takeIf { it.isNotBlank() },
            info.bitDepth?.takeIf { it.isNotBlank() }
        ) + info.dynamicRange.filter { it.isNotBlank() }
        ).joinToString(dot)
    val audioText = listOfNotNull(
        info.audioCodec?.takeIf { it.isNotBlank() },
        info.audioChannels?.takeIf { it.isNotBlank() }
    ).joinToString(" ")
    if (videoText.isNotBlank() || audioText.isNotBlank()) {
        InfoRow {
            if (videoText.isNotBlank()) {
                InfoSegment(Icons.Rounded.Movie, videoText, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
            if (audioText.isNotBlank()) {
                InfoSegment(Icons.AutoMirrored.Rounded.VolumeUp, audioText, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
        }
    }

    // Size: [Storage] 9 GB · 56m · 18 Mbps
    val sizeText = listOfNotNull(
        info.sizeLabel?.takeIf { it.isNotBlank() },
        info.runtimeLabel?.takeIf { it.isNotBlank() },
        info.bitrateLabel?.takeIf { it.isNotBlank() }
    ).joinToString(dot)
    if (sizeText.isNotBlank()) {
        InfoRow {
            InfoSegment(Icons.Rounded.Storage, sizeText, secondary, lineStyle, Modifier.weight(1f, fill = false))
        }
    }

    // Languages: [Language] EN · ES    [Subtitles] EN · ES
    val audioLangs = info.audioLanguages.toLangTags()
    val subLangs = info.subtitleLanguages.toLangTags()
    if (audioLangs.isNotBlank() || subLangs.isNotBlank()) {
        InfoRow {
            if (audioLangs.isNotBlank()) {
                InfoSegment(Icons.Rounded.Language, audioLangs, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
            if (subLangs.isNotBlank()) {
                InfoSegment(Icons.Rounded.Subtitles, subLangs, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun InfoRow(content: @Composable RowScope.() -> Unit) {
    Spacer(modifier = Modifier.height(3.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun InfoSegment(
    icon: ImageVector,
    text: String,
    tint: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = style,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Readiness chip — the ONLY colour in the row. Instant=gold, Cached=green, Not Cached=grey.
 */
@Composable
private fun CacheStatusPill(status: StreamCacheStatus) {
    val (icon: ImageVector, tint: Color, label: String) = when (status) {
        StreamCacheStatus.INSTANT -> Triple(Icons.Rounded.Bolt, Color(0xFFE0A800), "Instant")
        StreamCacheStatus.CACHED -> Triple(Icons.Rounded.CloudDone, Color(0xFF43A047), "Cached")
        StreamCacheStatus.NOT_CACHED -> Triple(
            Icons.Rounded.CloudDownload,
            NuvioTheme.extendedColors.textSecondary,
            "Not Cached"
        )
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** TV-only "Playing" pill, shown under the title for the active stream. */
@Composable
private fun CurrentStreamBadge(label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(NuvioTheme.colors.Primary.copy(alpha = 0.2f))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.Primary
        )
    }
}

/**
 * Renders a list of ISO-639-1 language codes as plain uppercase tags ("EN · ES") for the
 * stream-info rows. Kept as text (not flag emoji) so it renders identically cross-platform.
 * Empty when none.
 */
private fun List<String>.toLangTags(): String =
    asSequence()
        .map { it.trim().substringBefore('-') }
        .filter { it.isNotBlank() }
        .map { it.uppercase() }
        .distinct()
        .joinToString(" · ")
