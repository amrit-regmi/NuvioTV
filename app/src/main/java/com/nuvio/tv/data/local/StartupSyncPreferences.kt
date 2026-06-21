package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class StartupSyncState(
    val lastFullPullUserId: String?,
    val lastFullPullAtMs: Long,
    val lastFullPullIncludedProfileSettings: Boolean
)

@Singleton
class StartupSyncPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "startup_sync_state"
    }

    private val lastFullPullUserIdKey = stringPreferencesKey("last_full_pull_user_id")
    private val lastFullPullAtMsKey = longPreferencesKey("last_full_pull_at_ms")
    private val lastFullPullIncludedProfileSettingsKey = booleanPreferencesKey("last_full_pull_included_profile_settings")

    /**
     * Bump this when a new one-time local-cache migration must run on next launch.
     * Stored globally (profileId=1 → un-suffixed file) so it runs once per install, not per profile.
     */
    private val cwCachePurgeMigrationVersionKey = intPreferencesKey("cw_cache_purge_migration_version")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    /** Global (install-wide) store for cross-profile one-time migrations. */
    private fun globalStore() = factory.get(1, FEATURE)

    /**
     * Returns true exactly once per install for the given [version]: when the stored migration
     * version is below [version]. Marks it applied so subsequent launches skip the purge.
     */
    suspend fun shouldRunCwCachePurge(version: Int): Boolean {
        val prefs = globalStore().data.first()
        val current = prefs[cwCachePurgeMigrationVersionKey] ?: 0
        return current < version
    }

    suspend fun markCwCachePurgeApplied(version: Int) {
        globalStore().edit { prefs ->
            prefs[cwCachePurgeMigrationVersionKey] = version
        }
    }

    suspend fun getState(profileId: Int): StartupSyncState {
        val prefs = store(profileId).data.first()
        return StartupSyncState(
            lastFullPullUserId = prefs[lastFullPullUserIdKey],
            lastFullPullAtMs = prefs[lastFullPullAtMsKey] ?: 0L,
            lastFullPullIncludedProfileSettings = prefs[lastFullPullIncludedProfileSettingsKey] ?: false
        )
    }

    suspend fun markFullPull(
        profileId: Int,
        userId: String,
        includeProfileSettings: Boolean
    ) {
        store(profileId).edit { prefs ->
            prefs[lastFullPullUserIdKey] = userId
            prefs[lastFullPullAtMsKey] = System.currentTimeMillis()
            prefs[lastFullPullIncludedProfileSettingsKey] = includeProfileSettings
        }
    }
}
