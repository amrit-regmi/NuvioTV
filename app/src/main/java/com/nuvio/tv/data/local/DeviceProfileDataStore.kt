package com.nuvio.tv.data.local

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DeviceProfileDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FEATURE = "device_profile"
        val VALID_PROFILE_IDS = setOf("standard", "1080p", "4k_sdr", "4k_hdr")
        const val DEFAULT_PROFILE_ID = "4k_hdr"
    }

    private val profileIdKey = stringPreferencesKey("device_profile_id")
    private val userOverriddenKey = booleanPreferencesKey("device_profile_user_overridden")
    private val streamEngineEnabledKey = booleanPreferencesKey("stream_engine_enabled")
    // Bug #1 — fast, per-profile LOCAL mirror of the dashboard `useBuiltinCatalog`
    // master flag (authoritative copy lives in the Supabase home-catalog blob). The
    // meta repository reads this synchronously to gate the built-in catalog-addon out
    // of meta/catalog/search when the active profile has the built-in catalog OFF, so
    // detail pages don't silently fall back to built-in meta. Default true.
    private val useBuiltinCatalogKey = booleanPreferencesKey("use_builtin_catalog")
    private val capabilitiesHashKey = stringPreferencesKey("device_capabilities_hash")
    private val downloadSpeedMbpsKey = floatPreferencesKey("download_speed_mbps")
    private val downloadSpeedMeasuredAtKey = longPreferencesKey("download_speed_measured_at")

    val selectedProfileId: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val streamEngineEnabled = prefs[streamEngineEnabledKey] ?: false
            if (streamEngineEnabled) {
                getOrCreateDeviceId()
            } else {
                (prefs[profileIdKey] ?: DEFAULT_PROFILE_ID).let {
                    if (it in VALID_PROFILE_IDS) it else DEFAULT_PROFILE_ID
                }
            }
        }
    }

    val isUserOverridden: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[userOverriddenKey] ?: false }
    }

    val isStreamEngineEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[streamEngineEnabledKey] ?: true }
    }

    /** Per-profile mirror of the dashboard `useBuiltinCatalog` master flag. Default true. */
    val isBuiltinCatalogEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[useBuiltinCatalogKey] ?: true }
    }

    /**
     * Synchronous-friendly read of the active profile's `useBuiltinCatalog` mirror for the
     * meta repository's gate. Suspends only to read the current DataStore snapshot. Default
     * true (built-in catalog on) so a fresh profile / un-synced state never blanks meta.
     */
    suspend fun getBuiltinCatalogEnabled(): Boolean =
        factory.get(profileManager.activeProfileId.value, FEATURE).data.first()[useBuiltinCatalogKey] ?: true

    /** Writes the local `useBuiltinCatalog` mirror after a pull/push so the meta gate is fast. */
    suspend fun setBuiltinCatalogEnabled(enabled: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[useBuiltinCatalogKey] = enabled
        }
    }

    suspend fun setProfileId(profileId: String, userOverride: Boolean = true) {
        val id = if (profileId in VALID_PROFILE_IDS) profileId else DEFAULT_PROFILE_ID
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[profileIdKey] = id
            if (userOverride) prefs[userOverriddenKey] = true
        }
    }

    suspend fun setStreamEngineEnabled(enabled: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[streamEngineEnabledKey] = enabled
        }
    }

    suspend fun setUserOverridden(overridden: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[userOverriddenKey] = overridden
        }
    }

    suspend fun applyDetectedIfNotOverridden(detectedProfileId: String) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            if (prefs[userOverriddenKey] != true) {
                prefs[profileIdKey] = detectedProfileId
            }
        }
    }

    fun getOrCreateDeviceId(): String {
        // Derive a stable device-level UUID from hardware identifiers so all profiles
        // on the same physical device share one device_id (not one per profile).
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val model = android.os.Build.MODEL
        return UUID.nameUUIDFromBytes("$androidId:$model".toByteArray(Charsets.UTF_8)).toString()
    }

    /** Returns true if the hash changed (registration should be sent), false if unchanged. */
    suspend fun updateCapabilitiesHashIfChanged(hash: String): Boolean {
        val prefs = factory.get(profileManager.activeProfileId.value, FEATURE).data.first()
        if (prefs[capabilitiesHashKey] == hash) return false
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[capabilitiesHashKey] = hash }
        return true
    }

    /**
     * Returns the device's estimated download speed in Mbps using the system's
     * [ConnectivityManager.getLinkDownstreamBandwidthKbps] estimate (API 21+, no network cost).
     * Result is cached in DataStore for 6 hours; subsequent calls within that window return the
     * cached value without querying the system again.
     */
    suspend fun estimateDownloadSpeedMbps(): Float {
        val ttlMillis = 6 * 60 * 60 * 1_000L
        val prefs = factory.get(profileManager.activeProfileId.value, FEATURE).data.first()
        val measuredAt = prefs[downloadSpeedMeasuredAtKey] ?: 0L
        val cached = prefs[downloadSpeedMbpsKey]
        if (cached != null && (System.currentTimeMillis() - measuredAt) < ttlMillis) {
            return cached
        }
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val kbps = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.linkDownstreamBandwidthKbps
            ?: 0
        val mbps = kbps / 1000f
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[downloadSpeedMbpsKey] = mbps
            prefs[downloadSpeedMeasuredAtKey] = System.currentTimeMillis()
        }
        return mbps
    }
}
