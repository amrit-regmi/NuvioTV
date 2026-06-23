package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backendBaseUrlDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "backend_base_url_store"
)

/**
 * Device-wide override for the reco / taste-engine backend base URL (migration enabler).
 *
 * Persistence model:
 * - DataStore is the canonical store the settings UI reads/writes (reactive [overrideUrl]).
 * - A plain SharedPreferences mirror ([SYNC_PREFS] / [SYNC_KEY]) holds the same value so
 *   [com.nuvio.tv.core.reco.RecoBackend] can read it SYNCHRONOUSLY at app startup, before any
 *   network call or DI graph construction (DataStore is async-only; mirroring matches the existing
 *   locale-cache pattern in NuvioApplication).
 *
 * The override is applied at startup only — changing it prompts the user to log out + back in
 * rather than hot-swapping mid-session (see [com.nuvio.tv.core.reco.RecoBackend]).
 */
@Singleton
class BackendBaseUrlDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val overrideUrlKey = stringPreferencesKey("backend_base_url_override")

    /** The built-in default backend, baked at build time. Used as the reset value + placeholder. */
    val defaultBaseUrl: String = BuildConfig.RECO_API_BASE_URL

    /** The persisted override URL (empty = use the built-in default). */
    val overrideUrl: Flow<String> = context.backendBaseUrlDataStore.data.map { prefs ->
        prefs[overrideUrlKey] ?: ""
    }

    /**
     * Persist [url] (already validated + normalized by [normalizeOrNull]). Empty/blank clears the
     * override (revert to the built-in default). Mirrors to the synchronous SharedPreferences cache
     * so the next app launch picks it up before [com.nuvio.tv.core.reco.RecoBackend] is read.
     */
    suspend fun setOverrideUrl(url: String) {
        val cleaned = url.trim()
        context.backendBaseUrlDataStore.edit { prefs ->
            if (cleaned.isBlank()) prefs.remove(overrideUrlKey) else prefs[overrideUrlKey] = cleaned
        }
        // Mirror for synchronous startup read.
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply { if (cleaned.isBlank()) remove(SYNC_KEY) else putString(SYNC_KEY, cleaned) }
            .apply()
    }

    companion object {
        const val SYNC_PREFS = "backend_base_url_sync"
        const val SYNC_KEY = "override_url"

        /**
         * Synchronous read of the persisted override (SharedPreferences mirror). Safe to call on
         * the main thread at startup. Returns null when no override is set.
         */
        fun readOverrideSync(context: Context): String? =
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .getString(SYNC_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        /**
         * Validates a user-entered backend URL and returns the normalized form (https, well-formed,
         * trailing slash trimmed), or null if invalid. Blank input is invalid here — callers that
         * want "clear the override" should special-case blank before calling.
         */
        fun normalizeOrNull(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return null
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            // Must have a host segment after the scheme.
            val host = trimmed.removePrefix("https://").removePrefix("HTTPS://")
                .substringBefore('/')
                .substringBefore(':')
            if (host.isBlank() || !host.contains('.') || host.contains(' ')) return null
            return trimmed
        }
    }
}
