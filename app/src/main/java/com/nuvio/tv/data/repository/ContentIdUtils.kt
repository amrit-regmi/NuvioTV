package com.nuvio.tv.data.repository

/**
 * Generic content-id parsing helpers shared across the app.
 *
 * Previously these lived alongside Trakt-specific helpers in TraktIdUtils.kt.
 * The Trakt integration has been removed; only the source-agnostic parsing
 * utilities (IMDB / TMDB / numeric extraction) are retained here.
 */
internal data class ParsedContentIds(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null
)

internal fun parseContentIds(contentId: String?): ParsedContentIds {
    if (contentId.isNullOrBlank()) return ParsedContentIds()
    val raw = contentId.trim()

    if (raw.startsWith("tt")) {
        return ParsedContentIds(imdb = raw.substringBefore(':'))
    }

    if (raw.startsWith("tmdb:", ignoreCase = true)) {
        return ParsedContentIds(tmdb = raw.substringAfter(':').toIntOrNull())
    }

    if (raw.startsWith("trakt:", ignoreCase = true)) {
        return ParsedContentIds(trakt = raw.substringAfter(':').toIntOrNull())
    }

    val numeric = raw.substringBefore(':').toIntOrNull()
    return if (numeric != null) {
        ParsedContentIds(trakt = numeric)
    } else {
        ParsedContentIds()
    }
}

internal fun extractYear(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    return Regex("(\\d{4})").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
