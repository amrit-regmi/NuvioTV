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
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val vote_average: Double? = null,
    val score: Double = 0.0,
)

@Serializable
data class RecoRow(
    val label: String,
    val reason_type: String,
    val items: List<RecoItem>,
)

@Serializable
data class RecoResponse(val rows: List<RecoRow>)

@Singleton
class RecommendationRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchRows(userId: String, bearerToken: String): List<RecoRow> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/reco/for/$userId")
                    .header("Authorization", "Bearer $bearerToken")
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
}
