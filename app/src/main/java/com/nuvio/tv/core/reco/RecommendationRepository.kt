package com.nuvio.tv.core.reco

import android.util.Log
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RecoItem(
    val tmdb_id: Int,
    val kind: String,
    val title: String,
    val year: Int? = null,
    val poster_path: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo_path: String? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val vote_average: Double? = null,
    val score: Double = 0.0,
    val imdb_id: String? = null,
    val certification: String? = null,
    val status: String? = null,
)

@Serializable
data class RecoRow(
    val label: String,
    val reason_type: String,
    /**
     * Backend-declared content type for the row ("movie" | "series"). When present this is
     * authoritative for labeling and type-matching — preferred over the dominant-kind
     * heuristic over [items]. Absent on older backends; callers fall back to dominant kind.
     */
    val content_type: String? = null,
    val items: List<RecoItem>,
)

@Serializable
data class RecoResponse(val rows: List<RecoRow>)

@Serializable
data class FeatureStatus(
    /** Whether the section is available to this account (super-admin kill switch). */
    val available: Boolean = true,
    /** Whether the section is locked by the admin for this specific user. */
    val locked_by_admin: Boolean = false,
)

/**
 * The user's saved subtitle language preference, from GET /configure/subtitle-langs.
 * Codes are 2-letter (en/sv/fi); [secondary] is null when not set.
 */
data class SubtitleLangPref(
    val primary: String?,
    val secondary: String?
)

@Serializable
data class MeInfo(
    val is_super_admin: Boolean = false,
    /**
     * Per-feature AVAILABILITY map resolved from the super admin's kill switches.
     * A feature reported `false` is UNAVAILABLE — the TV hides/locks its UI.
     * Empty/missing map means everything is available (fail-open). Known keys:
     * `personalization`, `stream_providers`, `catalogs`, `connected_devices`.
     */
    val features: Map<String, Boolean> = emptyMap(),
    /**
     * Richer per-feature status: `{key: {available, locked_by_admin}}`. When present this is
     * authoritative — a section is effectively available only when `available && !locked_by_admin`.
     * Absent on older backends; callers fall back to [features]. Fail-open on missing keys.
     */
    val feature_status: Map<String, FeatureStatus> = emptyMap(),
    /**
     * When true, the shared/admin TorBox key is NOT available to this account; the user must
     * supply their own TorBox key for stream resolution.
     */
    val require_own_torbox_key: Boolean = false,
) {
    val isSuperAdmin: Boolean get() = is_super_admin
    val requireOwnTorboxKey: Boolean get() = require_own_torbox_key

    /**
     * Effective AVAILABILITY map combining [features] and [feature_status].
     * A section is available only when its boolean is not false AND it is not locked_by_admin.
     * Fail-open: keys absent from both maps are available (resolved at call sites via `?: true`).
     */
    val effectiveFeatures: Map<String, Boolean>
        get() {
            val keys = features.keys + feature_status.keys
            return keys.associateWith { key ->
                val flag = features[key] ?: true
                val status = feature_status[key]
                val statusAvailable = status?.let { it.available && !it.locked_by_admin } ?: true
                flag && statusAvailable
            }
        }

    /** Fail-open availability check: missing/unknown key → available. */
    fun isFeatureAvailable(key: String): Boolean = effectiveFeatures[key] ?: true
}

@Singleton
class RecommendationRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchRows(userId: String, bearerToken: String, profileId: String? = null): List<RecoRow> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/reco/for/$userId?limit_per_row=10")
                    .header("Authorization", "Bearer $bearerToken")
                    .apply { if (profileId != null) addHeader("X-Profile-Id", profileId) }
                    .build()
                val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                json.decodeFromString<RecoResponse>(body).rows
            }.getOrElse {
                Log.w("RecoRepo", "Failed to fetch recommendations", it)
                emptyList()
            }
        }

    suspend fun reportWatched(
        bearerToken: String,
        tmdbId: Int,
        kind: String,
        progress: Float,
        profileId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val bodyJson = buildJsonObject {
                put("tmdb_id", tmdbId)
                put("kind", kind)
                put("progress", progress)
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
            }
            val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.RECO_API_BASE_URL}/events/watched")
                .header("Authorization", "Bearer $bearerToken")
                .apply { if (profileId != null) addHeader("X-Profile-Id", profileId) }
                .post(body)
                .build()
            httpClient.newCall(request).execute().close()
        }.onFailure { Log.w("RecoRepo", "reportWatched failed", it) }
    }

    suspend fun issueWatchlyKey(bearerToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/recoengine/issue-key")
                    .header("Authorization", "Bearer $bearerToken")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

    /**
     * Fetches the logged-in Nuvio user's account flags from the backend.
     * Currently surfaces [MeInfo.isSuperAdmin] so the TV app can decide whether to
     * show the "Manage / Super Admin" entry on the primary profile.
     */
    suspend fun fetchMe(userId: String, bearerToken: String): MeInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/api/me/$userId")
                    .header("Authorization", "Bearer $bearerToken")
                    .build()
                val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                json.decodeFromString<MeInfo>(body)
            }.getOrElse {
                Log.w("RecoRepo", "fetchMe failed", it)
                null
            }
        }

    /**
     * Requests a short-lived (15 min) profile configuration URL for the given profile.
     * Used by the management screen to let the household configure a profile on a phone.
     */
    suspend fun issueProfileToken(bearerToken: String, profileId: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bodyJson = buildJsonObject {
                    put("profile_id", profileId)
                }
                val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/api/profile-token")
                    .header("Authorization", "Bearer $bearerToken")
                    .post(body)
                    .build()
                val responseBody = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                val obj = Json.parseToJsonElement(responseBody).jsonObject
                obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["token"]?.jsonPrimitive?.contentOrNull?.let { token ->
                        "https://hamrocinema.regmig.com/configure/profile/$token"
                    }
            }.getOrElse {
                Log.w("RecoRepo", "issueProfileToken failed for profile $profileId", it)
                null
            }
        }

    /**
     * Fetches the user's saved subtitle language preference from the backend.
     * GET /configure/subtitle-langs (Bearer-gated, reco host). Returns the saved
     * {primary, secondary} 2-letter codes (en/sv/fi); secondary may be null/blank.
     * Returns null on any failure so the caller keeps the local value.
     */
    suspend fun fetchSubtitleLangs(bearerToken: String, profileId: String? = null): SubtitleLangPref? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/configure/subtitle-langs")
                    .header("Authorization", "Bearer $bearerToken")
                    .apply { if (profileId != null) addHeader("X-Profile-Id", profileId) }
                    .get()
                    .build()
                val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                val obj = Json.parseToJsonElement(body).jsonObject
                val primary = obj["primary"]?.jsonPrimitive?.contentOrNull
                val secondary = obj["secondary"]?.jsonPrimitive?.contentOrNull
                SubtitleLangPref(primary = primary, secondary = secondary?.takeIf { it.isNotBlank() })
            }.getOrElse {
                Log.w("RecoRepo", "fetchSubtitleLangs failed", it)
                null
            }
        }

    /**
     * Persists the user's subtitle language preference server-side (used by the backend
     * for prewarm + ordering). POST /configure/subtitle-langs with {primary, secondary}
     * (2-letter codes en/sv/fi). [secondary] null is sent as JSON null. Best-effort.
     */
    suspend fun setSubtitleLangs(
        bearerToken: String,
        primary: String,
        secondary: String?,
        profileId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bodyJson = buildJsonObject {
                put("primary", primary)
                put("secondary", secondary)
            }
            val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.RECO_API_BASE_URL}/configure/subtitle-langs")
                .header("Authorization", "Bearer $bearerToken")
                .apply { if (profileId != null) addHeader("X-Profile-Id", profileId) }
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w("RecoRepo", "setSubtitleLangs failed", it)
            false
        }
    }

    suspend fun deleteProfile(bearerToken: String, profileUuid: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/nuvio/profile/$profileUuid")
                    .header("Authorization", "Bearer $bearerToken")
                    .header("X-Profile-Id", profileUuid)
                    .delete()
                    .build()
                val resp = httpClient.newCall(request).execute()
                resp.isSuccessful.also { resp.close() }
            }.getOrElse {
                Log.w("RecoRepo", "deleteProfile failed for $profileUuid", it)
                false
            }
        }
}
