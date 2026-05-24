package com.nuvio.tv.core.subtitle

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SubtitleWarmer"
private const val MAX_CACHE_SIZE = 100
private const val CACHE_TTL_MS = 15 * 60 * 1000L

@Singleton
class SubtitleWarmer @Inject constructor(
    private val addonRepository: AddonRepository,
    private val api: AddonApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CachedSubtitles(
        val subtitles: List<Subtitle>,
        val cachedAtMs: Long
    )

    private val cache: MutableMap<String, CachedSubtitles> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedSubtitles>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedSubtitles>) =
                size > MAX_CACHE_SIZE
        }
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<List<Subtitle>?>>()

    fun warm(type: String, videoId: String, filename: String?, videoSize: Long?) {
        if (filename == null) return
        val key = cacheKey(filename, videoSize)
        synchronized(lock) {
            if (isCached(key) || inFlight.containsKey(key)) return
            val deferred = scope.async {
                fetchSubtitles(type, videoId, filename, videoSize, key)
            }
            inFlight[key] = deferred
            deferred.invokeOnCompletion { synchronized(lock) { inFlight.remove(key) } }
        }
    }

    // Called by SubtitleRepository instead of doing its own fetch.
    // Returns cached list, awaits in-flight warm, or null (caller fetches normally).
    suspend fun awaitWarm(filename: String, videoSize: Long?): List<Subtitle>? {
        // Exact match
        val key = cacheKey(filename, videoSize)
        getCached(filename, videoSize)?.let { return it }
        val deferred = synchronized(lock) { inFlight[key] }
        if (deferred != null) {
            Log.d(TAG, "Awaiting in-flight subtitle warm key=$key")
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        // Fallback: warm fired before videoSize was known (behaviorHints.videoSize was null),
        // but OpenSubtitlesHasher later computed the actual file size. Try the null-videoSize entry.
        if (videoSize != null) {
            getCached(filename, null)?.let { return it }
            val nullDeferred = synchronized(lock) { inFlight[cacheKey(filename, null)] }
            if (nullDeferred != null) {
                Log.d(TAG, "Awaiting in-flight subtitle warm (null-videoSize fallback) filename=$filename")
                return try {
                    nullDeferred.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    fun getCached(filename: String, videoSize: Long?): List<Subtitle>? {
        val entry = cache[cacheKey(filename, videoSize)] ?: return null
        val age = System.currentTimeMillis() - entry.cachedAtMs
        return if (age in 0..CACHE_TTL_MS) entry.subtitles else null
    }

    private suspend fun fetchSubtitles(
        type: String,
        videoId: String,
        filename: String,
        videoSize: Long?,
        key: String
    ): List<Subtitle>? {
        return try {
            val addon = findSubtitleAddon() ?: return null
            val url = buildSubtitleUrl(addon.baseUrl, type, videoId, filename, videoSize)
            Log.d(TAG, "Pre-fetching subtitles: $url")
            when (val result = safeApiCall { api.getSubtitles(url) }) {
                is NetworkResult.Success -> {
                    val subtitles = result.data.subtitles?.map { dto ->
                        Subtitle(
                            id = dto.id ?: "${dto.lang}-${dto.url.hashCode()}",
                            url = dto.url,
                            lang = dto.lang,
                            addonName = addon.displayName,
                            addonLogo = addon.logo
                        )
                    } ?: return null
                    cache[key] = CachedSubtitles(subtitles, System.currentTimeMillis())
                    Log.d(TAG, "Cached ${subtitles.size} subtitles key=$key")
                    subtitles
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle pre-fetch failed", e)
            null
        }
    }

    private fun isCached(key: String): Boolean =
        cache[key]?.let { System.currentTimeMillis() - it.cachedAtMs <= CACHE_TTL_MS } == true

    private suspend fun findSubtitleAddon() =
        addonRepository.getInstalledAddons().first()
            .firstOrNull { addon ->
                addon.resources.any { r ->
                    r.name.equals("subtitles", ignoreCase = true) || r.name.equals("subtitle", ignoreCase = true)
                }
            }

    private fun buildSubtitleUrl(
        rawBaseUrl: String,
        type: String,
        videoId: String,
        filename: String,
        videoSize: Long?
    ): String {
        val clean = rawBaseUrl.trim().trimEnd('/')
        val q = clean.indexOf('?')
        val basePath = if (q >= 0) clean.substring(0, q).trimEnd('/') else clean
        val baseQuery = if (q >= 0) clean.substring(q) else ""
        val normalizedType = if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
        val encodedFilename = URLEncoder.encode(filename, "UTF-8")
        val extras = if (videoSize != null) "filename=$encodedFilename&videoSize=$videoSize"
        else "filename=$encodedFilename"
        return "$basePath/subtitles/$normalizedType/$videoId/$extras.json$baseQuery"
    }

    fun cacheKey(filename: String, videoSize: Long?): String = "$filename|$videoSize"
}
