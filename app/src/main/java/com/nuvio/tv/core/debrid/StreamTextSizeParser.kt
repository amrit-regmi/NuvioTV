package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream

/**
 * Parses a file size out of free-text stream metadata. Addons like Torrentio
 * embed the size in the stream title (e.g. "👤 12 💾 1.81 GB ⚙️ TPB") instead
 * of providing a structured size field, so this is the last-resort fallback
 * for size display, filtering, and sorting.
 */
object StreamTextSizeParser {

    private val SIZE_REGEX = Regex(
        """(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)\b""",
        RegexOption.IGNORE_CASE
    )

    private const val KILO = 1024.0

    fun sizeBytesFromText(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val match = SIZE_REGEX.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "TB" -> KILO * KILO * KILO * KILO
            "GB" -> KILO * KILO * KILO
            "MB" -> KILO * KILO
            "KB" -> KILO
            else -> return null
        }
        return (value * multiplier).toLong().takeIf { it > 0L }
    }

    fun sizeBytesFromStreamText(stream: Stream): Long? =
        sizeBytesFromText(stream.description)
            ?: sizeBytesFromText(stream.title)
            ?: sizeBytesFromText(stream.name)
}
