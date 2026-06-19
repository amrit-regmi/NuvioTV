package com.nuvio.tv.core.reco

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    suspend fun fetchExistingRating(tmdbId: Int, kind: String): Int? =
        withContext(Dispatchers.IO) {
            val token = authManager.currentAccessToken() ?: return@withContext null
            val userId = authManager.currentUserId ?: return@withContext null
            try {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/ratings/$userId/$kind/$tmdbId")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                Json.parseToJsonElement(body).jsonObject["stars"]?.jsonPrimitive?.intOrNull
            } catch (e: Exception) {
                Log.w(TAG, "fetchExistingRating failed", e)
                null
            }
        }

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
