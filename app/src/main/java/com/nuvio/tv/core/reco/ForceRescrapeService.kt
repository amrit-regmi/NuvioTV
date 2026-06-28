package com.nuvio.tv.core.reco

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ForceRescrapeService"

/**
 * Calls the backend force re-scrape ("Force fetch") endpoint:
 *   POST {catalogAddonUrl}/stream/{type}/{video_id}/rescrape
 *
 * Same host/prefix/auth as the catalog-addon GET /stream/{type}/{video_id}.json that
 * the stream screen already calls (see api_bridge.md "Force Re-scrape"). The backend
 * re-scrapes ONE title/episode and upserts hashes; the app should re-fetch the stream
 * list afterwards so newly-found streams appear.
 *
 * Rate limited 1 request / 60s per (user, title) → 429 with a Retry-After header.
 */
@Singleton
class ForceRescrapeService @Inject constructor(
    private val authManager: AuthManager,
    private val httpClient: OkHttpClient,
) {
    sealed class Result {
        /** 200 — `ok` reflects whether the scrape actually produced something. */
        data class Success(
            val ok: Boolean,
            val added: Int,
            val valid: Int,
            val cached: Int
        ) : Result()

        /** 429 — per-(user,title) cooldown hit; [retryAfterSeconds] from the header if present. */
        data class RateLimited(val retryAfterSeconds: Int?) : Result()

        /** Any other failure (401/400/404/5xx/network). */
        data class Failure(val code: Int?) : Result()
    }

    /**
     * @param type "movie" | "series"
     * @param videoId the IDENTICAL id the stream screen holds (e.g. "tt1375666" or "tt0903747:1:2")
     * @param profileId optional active profile id → sent as X-Profile-Id
     */
    suspend fun rescrape(type: String, videoId: String, profileId: String?): Result =
        withContext(Dispatchers.IO) {
            val token = authManager.currentAccessToken()
                ?: return@withContext Result.Failure(401)
            // Same base + prefix as the GET stream-list fetch, only the suffix differs.
            val url = "${RecoBackend.catalogAddonUrl}/stream/" +
                "${encode(type)}/${encode(videoId)}/rescrape"
            try {
                val builder = Request.Builder()
                    .url(url)
                    .post(ByteArray(0).toRequestBody(null))
                    .addHeader("Authorization", "Bearer $token")
                if (!profileId.isNullOrBlank()) {
                    builder.addHeader("X-Profile-Id", profileId)
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    when (response.code) {
                        in 200..299 -> {
                            val body = response.body?.string().orEmpty()
                            Result.Success(
                                ok = readBool(body, "ok"),
                                added = readInt(body, "added"),
                                valid = readInt(body, "valid"),
                                cached = readInt(body, "cached")
                            )
                        }
                        429 -> Result.RateLimited(
                            response.header("Retry-After")?.trim()?.toIntOrNull()
                        )
                        else -> {
                            Log.w(TAG, "rescrape failed code=${response.code} url=$url")
                            Result.Failure(response.code)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "rescrape threw for url=$url", e)
                Result.Failure(null)
            }
        }

    private fun readInt(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun readBool(json: String, key: String): Boolean =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.getOrNull(1) == "true"

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
