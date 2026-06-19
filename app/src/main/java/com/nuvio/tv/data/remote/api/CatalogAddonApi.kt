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
        @Header("Authorization") authorization: String
    ): Response<CatalogTorboxKeyDto>

    @GET("stream/{type}/{videoId}/status")
    suspend fun getStreamStatus(
        @Path("type") type: String,
        @Path(value = "videoId", encoded = true) videoId: String
    ): Response<CatalogStreamStatusDto>

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
}

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
    @Json(name = "eta_seconds") val etaSeconds: Int? = null,
    @Json(name = "download_state") val downloadState: String? = null
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
