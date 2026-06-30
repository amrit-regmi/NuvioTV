package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SubtitleResponseDto(
    @Json(name = "subtitles") val subtitles: List<SubtitleItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class SubtitleItemDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "url") val url: String,
    @Json(name = "lang") val lang: String
)

// #88 (api_bridge "best-subtitle-per-language"): the backend's `/subtitles/best/{type}/{id}.json`
// returns the single BEST subtitle per language (≤3, already moviehash/release-matched and
// SERVER-ORDERED primary→secondary→en). `lang` is 3-letter ISO-639-2 (eng/swe/fin).
@JsonClass(generateAdapter = true)
data class BestSubtitleResponseDto(
    @Json(name = "best") val best: List<BestSubtitleItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class BestSubtitleItemDto(
    @Json(name = "url") val url: String,
    @Json(name = "lang") val lang: String
)
