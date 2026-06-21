package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedTorboxKeyService"
private const val CACHE_TTL_MS = 30L * 60L * 1000L  // 30 minutes

/**
 * Fetches and caches the shared Torbox API key from the catalog-addon backend.
 * This key allows the app to resolve streams even when the user has no personal Torbox key.
 * The user's own key always takes priority over the shared key.
 */
@Singleton
class SharedTorboxKeyService @Inject constructor(
    private val catalogAddonApi: CatalogAddonApi
) {
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cachedAtMs: Long = 0L

    /**
     * Returns the shared Torbox key, or null if unavailable or not configured.
     * Results are cached for 30 minutes.
     */
    suspend fun getKey(): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        val age = now - cachedAtMs
        if (cachedKey != null && age in 0..CACHE_TTL_MS) {
            return@withLock cachedKey
        }

        return@withLock try {
            // F72: pass null so RecoAuthInterceptor attaches the user's Supabase token.
            val response = catalogAddonApi.getTorboxKey(null)
            if (response.isSuccessful) {
                val key = response.body()?.key?.trim()?.takeIf { it.isNotBlank() }
                if (key != null) {
                    cachedKey = key
                    cachedAtMs = now
                    Log.d(TAG, "Successfully fetched shared Torbox key")
                } else {
                    Log.w(TAG, "Torbox key response was empty")
                }
                key
            } else {
                Log.w(TAG, "Failed to fetch shared Torbox key: HTTP ${response.code()}")
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Exception fetching shared Torbox key: ${e.message}")
            null
        }
    }

    /**
     * Returns true if a shared Torbox key is potentially available (i.e., the
     * catalog-addon backend is configured and a key can be fetched with the user token).
     */
    fun isConfigured(): Boolean = BuildConfig.CATALOG_ADDON_BASE_URL.trim().isNotBlank()

    /** Invalidate the cached key (e.g., on auth failure). */
    fun invalidate() {
        cachedAtMs = 0L
        cachedKey = null
    }
}
