package com.nuvio.tv.core.reco

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecoRatingService"

@Singleton
class RecoRatingService @Inject constructor(
    private val authManager: AuthManager,
    private val httpClient: OkHttpClient,
) {
    suspend fun submitRating(tmdbId: Int, kind: String, stars: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = authManager.currentAccessToken()
                ?: return@withContext Result.failure(Exception("Not logged in"))
            val userId = authManager.currentUserId
                ?: return@withContext Result.failure(Exception("No user ID"))
            runCatching {
                val body = """{"tmdb_id":$tmdbId,"kind":"$kind","stars":$stars,"source":"nuvio"}"""
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/ratings/$userId")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val code = httpClient.newCall(request).execute().use { it.code }
                if (code in 200..299) Unit
                else throw Exception("Rating failed: $code")
            }.onFailure { Log.w(TAG, "submitRating failed", it) }
        }
}
