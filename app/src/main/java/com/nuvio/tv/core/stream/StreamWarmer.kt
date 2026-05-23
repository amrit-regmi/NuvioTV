package com.nuvio.tv.core.stream

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.core.subtitle.SubtitleWarmer
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Stream
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

private const val TAG = "StreamWarmer"
private const val MAX_CACHE_SIZE = 50
private const val CACHE_TTL_MS = 5 * 60 * 1000L

@Singleton
class StreamWarmer @Inject constructor(
    private val addonRepository: AddonRepository,
    private val api: AddonApi,
    private val subtitleWarmer: SubtitleWarmer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CachedStreams(val streams: List<Stream>, val cachedAtMs: Long)

    private val cache: MutableMap<String, CachedStreams> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedStreams>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedStreams>) =
                size > MAX_CACHE_SIZE
        }
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<List<Stream>?>>()

    // Called from DirectDebridStreamSource.preloadStreams() — fire and forget
    fun warm(type: String, videoId: String) {
        scope.async {
            val addon = findFirstStreamAddon() ?: return@async
            val key = cacheKey(addon.baseUrl, type, videoId)
            synchronized(lock) {
                if (isCached(key) || inFlight.containsKey(key)) return@async
                val deferred = scope.async { fetch(addon.baseUrl, addon.displayName, addon.logo, type, videoId, key) }
                inFlight[key] = deferred
                deferred.invokeOnCompletion { synchronized(lock) { inFlight.remove(key) } }
            }
        }
    }

    // Called from StreamRepositoryImpl.getStreamsFromAddon() — awaits in-flight or returns cached
    suspend fun awaitWarm(baseUrl: String, type: String, videoId: String): List<Stream>? {
        val key = cacheKey(baseUrl, type, videoId)
        getCached(key)?.let { return it }
        val deferred = synchronized(lock) { inFlight[key] } ?: return null
        Log.d(TAG, "Awaiting in-flight stream warm key=$key")
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun getCached(key: String): List<Stream>? {
        val entry = cache[key] ?: return null
        return if (System.currentTimeMillis() - entry.cachedAtMs <= CACHE_TTL_MS) entry.streams else null
    }

    private fun isCached(key: String): Boolean = getCached(key) != null

    private suspend fun fetch(
        baseUrl: String,
        addonName: String,
        addonLogo: String?,
        type: String,
        videoId: String,
        key: String
    ): List<Stream>? {
        return try {
            val url = buildStreamUrl(baseUrl, type, videoId)
            Log.d(TAG, "Pre-fetching streams: $url")
            when (val result = safeApiCall { api.getStreams(url) }) {
                is NetworkResult.Success -> {
                    val streams = result.data.streams?.map { it.toDomain(addonName, addonLogo) } ?: emptyList()
                    cache[key] = CachedStreams(streams, System.currentTimeMillis())
                    Log.d(TAG, "Cached ${streams.size} streams for addon=$addonName type=$type videoId=$videoId")
                    val top = streams.firstOrNull()
                    subtitleWarmer.warm(
                        type = type,
                        videoId = videoId,
                        filename = top?.behaviorHints?.filename,
                        videoSize = top?.behaviorHints?.videoSize
                    )
                    streams
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream pre-fetch failed", e)
            null
        }
    }

    private suspend fun findFirstStreamAddon() =
        addonRepository.getInstalledAddons().first()
            .firstOrNull { addon -> addon.resources.any { it.name.equals("stream", ignoreCase = true) } }

    private fun buildStreamUrl(baseUrl: String, type: String, videoId: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        val q = clean.indexOf('?')
        val basePath = if (q >= 0) clean.substring(0, q).trimEnd('/') else clean
        val baseQuery = if (q >= 0) clean.substring(q) else ""
        val encType = URLEncoder.encode(type, "UTF-8").replace("+", "%20")
        val encId = URLEncoder.encode(videoId, "UTF-8").replace("+", "%20")
        return "$basePath/stream/$encType/$encId.json$baseQuery"
    }

    fun cacheKey(baseUrl: String, type: String, videoId: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        val q = clean.indexOf('?')
        val basePath = if (q >= 0) clean.substring(0, q).trimEnd('/') else clean
        return "$basePath|$type|$videoId"
    }
}
