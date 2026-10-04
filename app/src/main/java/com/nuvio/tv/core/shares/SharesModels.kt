package com.nuvio.tv.core.shares

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the "Recommend to…" share-to-watchlist feature (backend `api/routes/shares.py`).
 * All `*_id` fields are profile-level `te_user_id` strings — opaque to this client; we never
 * resolve or construct them locally, only forward whatever the backend returns.
 */

// GET /shares/roster — full known-account roster minus caller + caller's own siblings.
// Used ONLY by the Settings "add someone to receive from" picker.
@Serializable
data class RosterEntryDto(
    @SerialName("user_id") val userId: String,
    val name: String? = null
)

// GET /shares/permissions/incoming — pending requests where caller is source_id
// ("people who want to receive from you"). Rendered as permission_request rows in the inbox,
// but also fetched standalone here if ever needed outside the merged inbox list.
@Serializable
data class IncomingPermissionRequestDto(
    val id: String,
    @SerialName("requester_id") val requesterId: String,
    @SerialName("requester_name") val requesterName: String? = null,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null
)

// GET /shares/permissions/granted — populates the SENDER's "Recommend to…" picker: sibling
// profiles (always) UNION cross-account profiles that have allowed the caller to send to them.
@Serializable
data class GrantedRecipientDto(
    @SerialName("user_id") val userId: String,
    val name: String? = null
)

// GET /shares/permissions/mine (requested of the backend — see
// ~/.claude/projects/memory/refinements/api_bridge.md "NEEDED: GET /shares/permissions/mine" —
// not yet confirmed live). Rows where the CALLER is requester_id, any status: populates the
// Settings "Receive recommendations from" allowed-list + Pending sub-list in one call.
@Serializable
data class MyPermissionDto(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_name") val sourceName: String? = null,
    val alias: String? = null,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null
)

// GET /shares/inbox — merged list of pending content-shares (for the recipient) AND pending
// incoming permission-requests (for the source). `type` discriminates the two shapes.
@Serializable
data class InboxItemDto(
    val type: String, // "share" | "permission_request"
    val id: String,
    @SerialName("created_at") val createdAt: String? = null,
    // --- "share" fields ---
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    // --- "permission_request" fields ---
    @SerialName("requester_id") val requesterId: String? = null,
    @SerialName("requester_name") val requesterName: String? = null
)

/** Body for `POST /shares`. */
@Serializable
data class ShareRequestBody(
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null
)

/** Action values accepted by `POST /shares/{id}/respond`. */
object ShareRespondAction {
    const val ADDED = "added"
    const val DISMISSED = "dismissed"
}

/** Action values accepted by `POST /shares/permissions/{id}/respond`. */
object PermissionRespondAction {
    const val ALLOW = "allow"
    const val DENY = "deny"
}
