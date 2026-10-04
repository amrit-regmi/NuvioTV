package com.nuvio.tv.core.shares

import android.util.Log
import com.nuvio.tv.core.reco.RecoBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the "Recommend to…" share-to-watchlist backend (`api/routes/shares.py`).
 * Mirrors [com.nuvio.tv.core.reco.RecommendationRepository]'s raw OkHttp + kotlinx.serialization
 * convention rather than Retrofit, since these new routes are bare-host
 * (`RecoBackend.baseUrl/shares/...`), not catalog-addon-prefixed. Delivery is foreground-poll
 * only (`GET /shares/inbox` via `InboxManager`) — no push-token registration.
 *
 * Auth: every call targets [RecoBackend.host], so the shared OkHttpClient's host-scoped
 * interceptors (RecoAuthInterceptor + NetworkModule's X-Profile-Id/X-Device-Id block) attach
 * `Authorization: Bearer <token>`, `X-Profile-Id`, and `X-Device-Id` automatically — no manual
 * header plumbing needed here, and no need to resolve/pass te_user_id client-side; the backend
 * resolves the caller via resolve_user(request).
 *
 * Response-shape defensiveness: the plan sketches some endpoints as bare JSON arrays and others
 * ambiguously. [extractArray] accepts either a bare array or an object wrapping the array under
 * one of the given candidate keys (or, failing that, the first array-valued field), so this
 * repository works regardless of which the backend actually returns.
 */
@Singleton
class SharesRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    private fun url(path: String) = "${RecoBackend.baseUrl}$path"

    private fun extractArray(body: String, vararg wrapperKeys: String): JsonArray {
        return runCatching {
            when (val element: JsonElement = json.parseToJsonElement(body)) {
                is JsonArray -> element
                is JsonObject -> {
                    wrapperKeys.firstNotNullOfOrNull { key -> element[key] as? JsonArray }
                        ?: element.values.filterIsInstance<JsonArray>().firstOrNull()
                        ?: JsonArray(emptyList())
                }
                else -> JsonArray(emptyList())
            }
        }.getOrElse { JsonArray(emptyList()) }
    }

    // --- GET /shares/roster ---
    suspend fun getRoster(): List<RosterEntryDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/shares/roster")).build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            extractArray(body, "roster", "users").map { json.decodeFromJsonElement<RosterEntryDto>(it) }
        }.getOrElse {
            Log.w(TAG, "getRoster failed", it)
            emptyList()
        }
    }

    // --- POST /shares/permissions/request ---
    suspend fun requestPermission(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bodyJson = buildJsonObject { put("source_id", sourceId) }
            val request = Request.Builder()
                .url(url("/shares/permissions/request"))
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "requestPermission failed", it)
            false
        }
    }

    // --- GET /shares/permissions/incoming ---
    suspend fun getIncomingPermissionRequests(): List<IncomingPermissionRequestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/shares/permissions/incoming")).build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            extractArray(body, "requests", "incoming").map { json.decodeFromJsonElement<IncomingPermissionRequestDto>(it) }
        }.getOrElse {
            Log.w(TAG, "getIncomingPermissionRequests failed", it)
            emptyList()
        }
    }

    // --- POST /shares/permissions/{id}/respond ---
    suspend fun respondToPermissionRequest(id: String, allow: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val action = if (allow) PermissionRespondAction.ALLOW else PermissionRespondAction.DENY
            val bodyJson = buildJsonObject { put("action", action) }
            val request = Request.Builder()
                .url(url("/shares/permissions/${id}/respond"))
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "respondToPermissionRequest failed", it)
            false
        }
    }

    // --- GET /shares/permissions/granted (sender's "Recommend to…" picker target list) ---
    suspend fun getGrantedRecipients(): List<GrantedRecipientDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/shares/permissions/granted")).build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            extractArray(body, "recipients", "granted").map { json.decodeFromJsonElement<GrantedRecipientDto>(it) }
        }.getOrElse {
            Log.w(TAG, "getGrantedRecipients failed", it)
            emptyList()
        }
    }

    // --- GET /shares/permissions/mine (requested; see api_bridge.md) ---
    // Settings page: the caller's own outgoing "receive from" requests, any status — allowed
    // rows render as the main list, pending rows as the Pending sub-list.
    suspend fun getMyPermissions(): List<MyPermissionDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/shares/permissions/mine")).build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            extractArray(body, "permissions", "mine").map { json.decodeFromJsonElement<MyPermissionDto>(it) }
        }.getOrElse {
            Log.w(TAG, "getMyPermissions failed", it)
            emptyList()
        }
    }

    // --- PUT /shares/permissions/{id}/alias ---
    suspend fun updatePermissionAlias(id: String, alias: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bodyJson = buildJsonObject { put("alias", alias) }
            val request = Request.Builder()
                .url(url("/shares/permissions/${id}/alias"))
                .put(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "updatePermissionAlias failed", it)
            false
        }
    }

    // --- POST /shares ---
    suspend fun sendShare(body: ShareRequestBody): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bodyJson = buildJsonObject {
                put("recipient_user_id", body.recipientUserId)
                put("content_id", body.contentId)
                put("content_type", body.contentType)
                body.name?.let { put("name", it) }
                body.poster?.let { put("poster", it) }
                body.background?.let { put("background", it) }
                body.logo?.let { put("logo", it) }
                body.description?.let { put("description", it) }
                body.releaseInfo?.let { put("release_info", it) }
                body.imdbRating?.let { put("imdb_rating", it) }
                putJsonArray("genres") { body.genres.forEach { g -> add(g) } }
                body.addonBaseUrl?.let { put("addon_base_url", it) }
            }
            val request = Request.Builder()
                .url(url("/shares"))
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "sendShare failed", it)
            false
        }
    }

    // --- GET /shares/inbox ---
    suspend fun getInbox(): List<InboxItemDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/shares/inbox")).build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            extractArray(body, "items", "inbox").map { json.decodeFromJsonElement<InboxItemDto>(it) }
        }.getOrElse {
            Log.w(TAG, "getInbox failed", it)
            emptyList()
        }
    }

    // --- POST /shares/{id}/respond ---
    suspend fun respondToShare(id: String, added: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val action = if (added) ShareRespondAction.ADDED else ShareRespondAction.DISMISSED
            val bodyJson = buildJsonObject { put("action", action) }
            val request = Request.Builder()
                .url(url("/shares/${id}/respond"))
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "respondToShare failed", it)
            false
        }
    }

    companion object {
        private const val TAG = "SharesRepo"
    }
}
