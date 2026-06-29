package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface CatalogAddonApi {
    @GET("torbox-key")
    suspend fun getTorboxKey(
        // Nullable + null in callers: RecoAuthInterceptor attaches the user's Supabase
        // Bearer token (F72). Do NOT pass `Bearer <CATALOG_SECRET>` here.
        @Header("Authorization") authorization: String? = null
    ): Response<CatalogTorboxKeyDto>

    @GET("stream/{type}/{videoId}/status")
    suspend fun getStreamStatus(
        @Path("type") type: String,
        @Path(value = "videoId", encoded = true) videoId: String
    ): Response<CatalogStreamStatusDto>

    // Details-open prewarm (parity with mobile CatalogPrewarmService). Best-effort:
    // pre-resolves the top genuinely-cached candidate(s) to a direct CDN url + bootstraps
    // subtitles so a subsequent /stream returns instantly-playable streams. Backend NEVER
    // auto-starts a download for uncached titles, so this is safe to fire on every open.
    // type = "movie"|"series"; videoId = "tt123" or "tt123:S:E". RecoAuthInterceptor attaches
    // the user's Supabase Bearer token (pass null here, same as the other catalog-addon calls).
    @POST("prewarm/{type}/{videoId}")
    suspend fun prewarm(
        @Path("type") type: String,
        @Path(value = "videoId", encoded = true) videoId: String,
        @Header("Authorization") authorization: String? = null
    ): Response<Unit>

    @POST("stream/{type}/{videoId}/prepare")
    suspend fun prepareStream(
        @Path("type") type: String,
        @Path(value = "videoId", encoded = true) videoId: String,
        @Header("Authorization") authorization: String?
    ): Response<CatalogStreamPrepareDto>

    @DELETE("stream/{type}/{videoId}/prepare")
    suspend fun cancelPrepare(
        @Path("type") type: String,
        @Path(value = "videoId", encoded = true) videoId: String,
        @Header("Authorization") authorization: String?
    ): Response<Unit>

    @GET("device-profile/{deviceId}")
    suspend fun getDeviceProfile(
        @Path("deviceId") deviceId: String
    ): Response<DeviceProfileDto>

    @PUT("device-profile")
    suspend fun updateDeviceProfile(
        @Body body: DeviceProfileUpdateDto
    ): Response<Unit>

    // Skip-intro timestamps served by OUR backend (DB-only, Tor-scraped nightly, auth-gated).
    // id is `tt…:S:E`. RecoAuthInterceptor attaches the user Bearer token (same host).
    // Empty `{}` body on a miss → no skip button (graceful).
    @GET("skip/{type}/{id}.json")
    suspend fun getSkip(
        @Path("type") type: String,
        @Path(value = "id", encoded = true) id: String
    ): Response<CatalogSkipDto>

    // MDBList-style ratings served by OUR backend (auth-gated). id is the imdb id.
    // `{ratings:[]}` on a miss → degrade gracefully (no extra ratings).
    @GET("ratings/{id}.json")
    suspend fun getRatings(
        @Path(value = "id", encoded = true) id: String
    ): Response<CatalogRatingsDto>
}

// --- Skip-intro DTOs (normalized {intro,recap,outro,source} shape from our backend) ---

@JsonClass(generateAdapter = true)
data class CatalogSkipDto(
    @Json(name = "intro") val intro: CatalogSkipSegmentDto? = null,
    @Json(name = "recap") val recap: CatalogSkipSegmentDto? = null,
    @Json(name = "outro") val outro: CatalogSkipSegmentDto? = null,
    @Json(name = "source") val source: String? = null
)

@JsonClass(generateAdapter = true)
data class CatalogSkipSegmentDto(
    @Json(name = "start") val start: Double? = null,
    @Json(name = "end") val end: Double? = null
)

// --- Ratings DTOs ({ratings:[{source,value,votes}]} from our backend) ---

@JsonClass(generateAdapter = true)
data class CatalogRatingsDto(
    @Json(name = "ratings") val ratings: List<CatalogRatingItemDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CatalogRatingItemDto(
    @Json(name = "source") val source: String? = null,
    @Json(name = "value") val value: Double? = null,
    @Json(name = "votes") val votes: Long? = null
)

@JsonClass(generateAdapter = true)
data class CatalogTorboxKeyDto(
    @Json(name = "service") val service: String? = null,
    @Json(name = "key") val key: String? = null
)

@JsonClass(generateAdapter = true)
data class CatalogStreamStatusDto(
    @Json(name = "items") val items: List<CatalogStreamStatusItemDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CatalogStreamStatusItemDto(
    @Json(name = "info_hash") val infoHash: String? = null,
    @Json(name = "cached") val cached: Boolean? = null,
    @Json(name = "has_url") val hasUrl: Boolean? = null,
    @Json(name = "warm_expires_at") val warmExpiresAt: String? = null,
    @Json(name = "warmed_at") val warmedAt: String? = null,
    @Json(name = "progress_pct") val progressPct: Double? = null,
    @Json(name = "seeds") val seeds: Int? = null,
    @Json(name = "download_speed_mbps") val downloadSpeedMbps: Double? = null,
    @Json(name = "download_speed_bps") val downloadSpeedBps: Double? = null,
    @Json(name = "eta_seconds") val etaSeconds: Int? = null,
    // download_state ∈ "queued" | "downloading" | … ("queued" = waiting for a free TorBox slot).
    @Json(name = "download_state") val downloadState: String? = null,
    // 1-based position in the download queue when download_state == "queued". Absent = unknown.
    @Json(name = "queue_position") val queuePosition: Int? = null
)

@JsonClass(generateAdapter = true)
data class CatalogStreamPrepareDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "eta_minutes") val etaMinutes: Int? = null,
    @Json(name = "info_hash") val infoHash: String? = null,
    @Json(name = "torrent_id") val torrentId: String? = null
)

@JsonClass(generateAdapter = true)
data class DeviceProfileDto(
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "device_name") val deviceName: String? = null,
    @Json(name = "max_resolution") val maxResolution: String? = null,
    @Json(name = "download_speed_mbps") val downloadSpeedMbps: Double? = null,
    @Json(name = "hdr_types_supported") val hdrTypesSupported: List<String>? = null,
    @Json(name = "supported_codecs") val supportedCodecs: List<String>? = null,
    @Json(name = "preferred_audio_formats") val preferredAudioFormats: List<String>? = null,
    @Json(name = "max_audio_channels") val maxAudioChannels: String? = null
)

@JsonClass(generateAdapter = true)
data class DeviceProfileUpdateDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_name") val deviceName: String,
    @Json(name = "max_resolution") val maxResolution: String,
    @Json(name = "hdr_types_supported") val hdrTypesSupported: List<String>,
    @Json(name = "supported_codecs") val supportedCodecs: List<String>,
    @Json(name = "preferred_audio_formats") val preferredAudioFormats: List<String>,
    @Json(name = "max_audio_channels") val maxAudioChannels: String,
    @Json(name = "download_speed_mbps") val downloadSpeedMbps: Double,
    @Json(name = "max_size_gb") val maxSizeGb: Int = 0
)
