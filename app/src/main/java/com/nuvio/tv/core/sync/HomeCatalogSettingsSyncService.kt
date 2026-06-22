package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseHomeCatalogSettingsBlob
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeCatalogSettingsSyncService"
private const val HOME_CATALOG_SHARED_SYNC_PLATFORM = "home_catalog_shared"
private const val TV_LEGACY_SETTINGS_SYNC_PLATFORM = "tv"
private const val PAYLOAD_SAMPLE_LIMIT = 5
private const val HIDE_UNRELEASED_CONTENT_KEY = "hide_unreleased_content"
private val HOME_CATALOG_LEGACY_SYNC_PLATFORMS = listOf(TV_LEGACY_SETTINGS_SYNC_PLATFORM, "mobile")

/**
 * One entry of the unified home row order written by the management dashboard into
 * Supabase `nuvio_home_catalog_settings.settings_json.rowOrder`.
 * Shape: `{id, kind, type, enabled}` where:
 * - `kind` ∈ "builtin" | "reco" | "addon"
 * - `id`   = catalog id (trending/popular/new_releases/top_rated for builtin), reco
 *            reason_type (personal/because_watched/…) for reco, or addon catalog id.
 * - `type` ∈ "movie" | "series" | "both"
 */
@Serializable
data class HomeRowOrderEntry(
    val id: String = "",
    val kind: String = "",
    val type: String = "",
    val enabled: Boolean = true,
)

/**
 * The authoritative per-profile home-catalog intent pulled from the dashboard blob
 * (`nuvio_home_catalog_settings.settings_json`). [configPresent] is true when a saved
 * blob exists at all — even when it contains an empty / all-disabled [rowOrder]. An
 * empty-but-present config is a VALID explicit state ("show nothing"); the app must NOT
 * fall back to a default full home when [configPresent] is true. Only a profile with NO
 * saved blob gets the app's default ordering.
 *
 * [useBuiltinCatalog] / [useRecommendations] are the dashboard master toggles. When false,
 * the app suppresses ALL built-in / recommendation rows wholesale (belt-and-suspenders with
 * the per-row `enabled` flag, which the dashboard also writes false). Absent → default true.
 */
data class HomeRowOrderConfig(
    val configPresent: Boolean,
    /** True when the blob explicitly carried a `rowOrder` key (even an empty array). When false
     *  the blob only carried master flags, so the app keeps its DEFAULT ordering and merely
     *  applies the flags — it does NOT blank the home. */
    val hasRowOrderKey: Boolean,
    val rowOrder: List<HomeRowOrderEntry>,
    val useBuiltinCatalog: Boolean,
    val useRecommendations: Boolean,
)

@Serializable
data class SyncCatalogItem(
    @SerialName("addon_id") val addonId: String,
    val type: String,
    @SerialName("catalog_id") val catalogId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("custom_title") val customTitle: String = "",
    @SerialName("is_collection") val isCollection: Boolean = false,
    @SerialName("collection_id") val collectionId: String = "",
)

@Serializable
data class SyncHomeCatalogPayload(
    @SerialName("hide_unreleased_content") val hideUnreleasedContent: Boolean = false,
    val items: List<SyncCatalogItem> = emptyList(),
)

private data class RemoteHomeCatalogSettings(
    val payload: SyncHomeCatalogPayload,
    val updatedAt: String?,
    val hasHideUnreleasedContent: Boolean
)

@Singleton
class HomeCatalogSettingsSyncService @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore
) {
    private val postgrest
        get() = supabaseProvider.postgrest

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushToRemote(reason: String = "unspecified"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val payload = loadLocalPayload()
            Log.d(TAG, "Push start profile=$profileId reason=$reason ${payload.summary()}")
            pushPayload(profileId, payload)

            Log.d(TAG, "Push success profile=$profileId reason=$reason")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Push failed reason=$reason", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val localState = layoutPreferenceDataStore.getHomeCatalogSettingsState()
            Log.d(TAG, "Pull start profile=$profileId ${localState.summary()}")

            if (localState.disabledKeys.any(::hasLegacyHomeCatalogDisabledKeyFormat)) {
                val localPayload = loadLocalPayload()
                if (localPayload.items.isNotEmpty()) {
                    isSyncingFromRemote = true
                    try {
                        layoutPreferenceDataStore.applyCatalogSettingsFromRemote(localPayload)
                    } finally {
                        isSyncingFromRemote = false
                    }
                    Log.i(TAG, "Migrated legacy local keys profile=$profileId ${localPayload.summary()} (no startup push)")
                    return@withContext Result.success(true)
                }
            }

            val localPayload = loadLocalPayload()
            val remote = fetchBestRemotePayload(profileId, localPayload)
            if (remote == null) {
                Log.d(TAG, "No remote row profile=$profileId; preserving local (startup is pull-only)")
                return@withContext Result.success(false)
            }

            val remotePayload = remote.payload
            Log.d(TAG, "Pull remote payload profile=$profileId ${remotePayload.summary()}")

            if (remotePayload.items.isEmpty()) {
                Log.d(TAG, "Remote payload empty profile=$profileId; preserving local (startup is pull-only)")
                return@withContext Result.success(false)
            }

            isSyncingFromRemote = true
            try {
                layoutPreferenceDataStore.applyCatalogSettingsFromRemote(remotePayload)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Pull apply success profile=$profileId ${remotePayload.summary()}")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull home catalog settings", e)
            Result.failure(e)
        }
    }

    /**
     * Pulls the unified home row order (`settings_json.rowOrder`) for the active profile from
     * the SAME Supabase row the app already syncs (`sync_pull_home_catalog_settings`, shared
     * platform). The dashboard writes this key; the app reads it to render home rows in the
     * exact saved order. Returns null when no saved rowOrder exists (new user → app default).
     */
    suspend fun pullRowOrderFromRemote(): HomeRowOrderConfig? = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val blob = fetchRemoteBlob(profileId, HOME_CATALOG_SHARED_SYNC_PLATFORM)
                ?: HOME_CATALOG_LEGACY_SYNC_PLATFORMS.firstNotNullOfOrNull { fetchRemoteBlob(profileId, it) }
            val settings = blob?.settingsJson ?: return@withContext null

            // A saved blob that carries ANY of the dashboard catalog keys is an explicit
            // config: obey it exactly (including empty / all-disabled). A blob that has NONE
            // of these keys (e.g. only app-owned `items`) is NOT a catalog config → null so
            // the app keeps its default ordering.
            val rowOrderJson = settings["rowOrder"]
            val hasUseBuiltin = settings.containsKey("useBuiltinCatalog")
            val hasUseReco = settings.containsKey("useRecommendations")
            if (rowOrderJson == null && !hasUseBuiltin && !hasUseReco) {
                Log.d(TAG, "Pull rowOrder profile=$profileId: no catalog config keys present → app default")
                return@withContext null
            }

            val parsed = rowOrderJson?.let {
                runCatching {
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(HomeRowOrderEntry.serializer()),
                        it
                    )
                }.getOrNull()
            }
            val cleaned = parsed
                ?.filter { it.id.isNotBlank() && it.kind.isNotBlank() }
                .orEmpty()
            // Retired `rowOrderByType` (F54) is intentionally ignored — use rowOrder only.
            val useBuiltin = (settings["useBuiltinCatalog"] as? JsonPrimitive)?.booleanOrNull ?: true
            val useReco = (settings["useRecommendations"] as? JsonPrimitive)?.booleanOrNull ?: true
            Log.d(
                TAG,
                "Pull rowOrder profile=$profileId entries=${cleaned.size} useBuiltin=$useBuiltin useReco=$useReco (configPresent)"
            )
            HomeRowOrderConfig(
                configPresent = true,
                hasRowOrderKey = rowOrderJson != null,
                rowOrder = cleaned,
                useBuiltinCatalog = useBuiltin,
                useRecommendations = useReco,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull rowOrder", e)
            null
        }
    }

    fun triggerPush() {
        if (isSyncingFromRemote) return
        if (!authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote(reason = "triggerPush")
        }
    }

    /**
     * Writes the unified home [rowOrder] (settings_json.rowOrder) for the active profile back to
     * the SAME shared Supabase row the home renders from. This is the single source of truth for
     * per-profile catalog enable/disable + order: the reorder UI mutates the saved rowOrder and
     * pushes it here so the home reflects the change immediately and the change persists.
     *
     * The push uses `p_profile_id = activeProfileId` and `p_platform = home_catalog_shared`, the
     * exact keys [pullRowOrderFromRemote] reads with — preventing the stale-read platform-mismatch
     * that previously caused toggles not to persist. Other settings keys in the blob are preserved.
     */
    suspend fun pushRowOrderToRemote(rowOrder: List<HomeRowOrderEntry>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val profileId = profileManager.activeProfileId.value
                val cleaned = rowOrder.filter { it.id.isNotBlank() && it.kind.isNotBlank() }
                val rowOrderJson = json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(HomeRowOrderEntry.serializer()),
                    cleaned
                )
                val existing = fetchRemoteBlob(profileId, HOME_CATALOG_SHARED_SYNC_PLATFORM)?.settingsJson
                val merged = buildJsonObject {
                    existing?.forEach { (key, value) -> put(key, value) }
                    put("rowOrder", rowOrderJson)
                }
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_settings_json", merged)
                    put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
                }
                withJwtRefreshRetry {
                    postgrest.rpc("sync_push_home_catalog_settings", params)
                }
                Log.d(TAG, "Push rowOrder success profile=$profileId entries=${cleaned.size}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Push rowOrder failed", e)
                Result.failure(e)
            }
        }

    /**
     * Reads the per-profile "use recommendation provider" master flag
     * (`settings_json.useRecommendations`) from the same shared blob the home renders from.
     * Absent → true (recommendations on by default), mirroring the web dashboard.
     */
    suspend fun pullUseRecommendations(): Boolean = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val blob = fetchRemoteBlob(profileId, HOME_CATALOG_SHARED_SYNC_PLATFORM)
                ?: HOME_CATALOG_LEGACY_SYNC_PLATFORMS.firstNotNullOfOrNull { fetchRemoteBlob(profileId, it) }
            (blob?.settingsJson?.get("useRecommendations") as? JsonPrimitive)?.booleanOrNull ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull useRecommendations", e)
            true
        }
    }

    /**
     * Writes the per-profile "use recommendation provider" master flag into the shared blob,
     * mirroring the web dashboard. When [enabled] is false the home suppresses ALL reco rows
     * (honoured in HomeViewModel.rebuildCatalogOrder). Every other blob key (rowOrder,
     * useBuiltinCatalog, items, …) is preserved by the merge.
     */
    suspend fun pushUseRecommendations(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val existing = fetchRemoteBlob(profileId, HOME_CATALOG_SHARED_SYNC_PLATFORM)?.settingsJson
            val merged = buildJsonObject {
                existing?.forEach { (key, value) -> put(key, value) }
                put("useRecommendations", JsonPrimitive(enabled))
            }
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", merged)
                put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_home_catalog_settings", params)
            }
            Log.d(TAG, "Push useRecommendations=$enabled success profile=$profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Push useRecommendations failed", e)
            Result.failure(e)
        }
    }

    private suspend fun loadLocalPayload(): SyncHomeCatalogPayload {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val collections = collectionsDataStore.getCurrentCollections()
        return layoutPreferenceDataStore.exportCatalogSettingsToSyncPayload(addons, collections)
    }

    private suspend fun pushPayload(profileId: Int, payload: SyncHomeCatalogPayload) {
        val jsonElement = mergedSharedPayloadJson(profileId, payload)

        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_settings_json", jsonElement)
            put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
        }

        withJwtRefreshRetry {
            postgrest.rpc("sync_push_home_catalog_settings", params)
        }
    }

    private suspend fun fetchBestRemotePayload(
        profileId: Int,
        localPayload: SyncHomeCatalogPayload
    ): RemoteHomeCatalogSettings? {
        val shared = fetchRemotePayload(
            profileId = profileId,
            platform = HOME_CATALOG_SHARED_SYNC_PLATFORM,
            localPayload = localPayload
        )
        val legacyRows = HOME_CATALOG_LEGACY_SYNC_PLATFORMS
            .mapNotNull { platform ->
                fetchRemotePayload(
                    profileId = profileId,
                    platform = platform,
                    localPayload = localPayload
                )
            }
        val rows = listOfNotNull(shared) + legacyRows
        val selected = if (shared?.payload?.items?.isNotEmpty() == true) {
            shared
        } else {
            legacyRows
                .filter { it.payload.items.isNotEmpty() }
                .maxByOrNull { it.updatedAt.orEmpty() }
                ?: shared
                ?: legacyRows.maxByOrNull { it.updatedAt.orEmpty() }
        }

        return selected?.withNewestStandaloneSettings(rows)
    }

    private suspend fun fetchRemotePayload(
        profileId: Int,
        platform: String,
        localPayload: SyncHomeCatalogPayload
    ): RemoteHomeCatalogSettings? {
        val blob = fetchRemoteBlob(profileId, platform) ?: return null
        val payload = decodePayloadPreservingLocalDefaults(blob.settingsJson, localPayload)
        if (payload == null) {
            Log.w(TAG, "Pull parse failure profile=$profileId platform=$platform")
            return null
        }
        return RemoteHomeCatalogSettings(
            payload = payload,
            updatedAt = blob.updatedAt,
            hasHideUnreleasedContent = blob.settingsJson.containsKey(HIDE_UNRELEASED_CONTENT_KEY)
        )
    }

    private fun RemoteHomeCatalogSettings.withNewestStandaloneSettings(
        rows: List<RemoteHomeCatalogSettings>
    ): RemoteHomeCatalogSettings {
        val hideUnreleasedSource = rows
            .filter { it.hasHideUnreleasedContent }
            .maxByOrNull { it.updatedAt.orEmpty() }

        return copy(
            payload = payload.copy(
                hideUnreleasedContent = hideUnreleasedSource?.payload?.hideUnreleasedContent
                    ?: payload.hideUnreleasedContent
            )
        )
    }

    private suspend fun fetchRemoteBlob(
        profileId: Int,
        platform: String
    ): SupabaseHomeCatalogSettingsBlob? {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", platform)
        }
        val response = withJwtRefreshRetry {
            postgrest.rpc("sync_pull_home_catalog_settings", params)
        }
        return response.decodeList<SupabaseHomeCatalogSettingsBlob>().firstOrNull()
    }

    private fun decodePayloadPreservingLocalDefaults(
        settingsJson: JsonObject,
        localPayload: SyncHomeCatalogPayload
    ): SyncHomeCatalogPayload? = runCatching {
        val decoded = json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), settingsJson)
        decoded.copy(
            hideUnreleasedContent = if (settingsJson.containsKey(HIDE_UNRELEASED_CONTENT_KEY)) {
                decoded.hideUnreleasedContent
            } else {
                localPayload.hideUnreleasedContent
            }
        )
    }.getOrNull()

    private suspend fun mergedSharedPayloadJson(
        profileId: Int,
        payload: SyncHomeCatalogPayload
    ): JsonObject {
        val localJson = json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload).jsonObject
        val remoteJson = fetchRemoteBlob(profileId, HOME_CATALOG_SHARED_SYNC_PLATFORM)?.settingsJson
        return buildJsonObject {
            remoteJson?.forEach { (key, value) -> put(key, value) }
            localJson.forEach { (key, value) -> put(key, value) }
        }
    }

}

private fun LocalHomeCatalogSettingsState.summary(): String {
    val orderSample = orderKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val disabledSample = disabledKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val titleSample = customTitles.keys.take(PAYLOAD_SAMPLE_LIMIT)
    return "localState(order=${orderKeys.size}, disabled=${disabledKeys.size}, titles=${customTitles.size}, orderSample=$orderSample, disabledSample=$disabledSample, titleSample=$titleSample)"
}

private fun SyncHomeCatalogPayload.summary(): String {
    val disabledCount = items.count { !it.enabled }
    val collectionCount = items.count { it.isCollection }
    val sample = items.take(PAYLOAD_SAMPLE_LIMIT).joinToString(separator = " | ") { item ->
        if (item.isCollection) {
            "collection:${item.collectionId},enabled=${item.enabled},order=${item.order}"
        } else {
            "catalog:${item.addonId}/${item.type}/${item.catalogId},enabled=${item.enabled},order=${item.order}"
        }
    }
    return "payload(items=${items.size}, disabled=$disabledCount, collections=$collectionCount, hideUnreleased=$hideUnreleasedContent, sample=[$sample])"
}
