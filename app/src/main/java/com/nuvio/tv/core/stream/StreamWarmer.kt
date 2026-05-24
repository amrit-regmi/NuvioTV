package com.nuvio.tv.core.stream

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.core.player.PlayerPreWarmer
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.core.subtitle.SubtitleWarmer
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamWarmer"
private const val MAX_CACHE_SIZE = 50
private const val CACHE_TTL_MS = 15 * 60 * 1000L
private const val PROBE_TIMEOUT_MS = 2_000L
private const val DEFAULT_PROBE_COUNT = 10
// Stub/error videos served by Comet and similar proxies for uncached content are tiny (< 1 MB).
// Real video files are always larger. Any Content-Range total below this threshold is a stub.
private const val MIN_REAL_VIDEO_BYTES = 1L * 1024 * 1024

@Singleton
class StreamWarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val api: AddonApi,
    private val subtitleWarmer: SubtitleWarmer,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val playerPreWarmer: PlayerPreWarmer,
    okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Short-timeout client for probing — separate from the main client to avoid affecting it
    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class CachedStreams(val streams: List<Stream>, val cachedAtMs: Long)

    private val cache: MutableMap<String, CachedStreams> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedStreams>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedStreams>) =
                size > MAX_CACHE_SIZE
        }
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<List<Stream>?>>()

    // URLs confirmed to be proxy stub/error videos (too small to be real content).
    // Capped at 500 entries via LRU eviction — stubs are a temporary state (content gets cached
    // in TorBox eventually) so we don't want to blacklist a URL indefinitely across app restarts.
    // The set is synchronizedMap-backed so concurrent probe threads don't corrupt it.
    private val stubUrls: MutableSet<String> = Collections.newSetFromMap(
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > 500
            }
        )
    )

    // Called from DirectDebridStreamSource.preloadStreams() — fire and forget.
    // Warms all stream addons in parallel so the best available source (TorBox or RealDebrid)
    // populates PlayerPreWarmer before the user taps Play.
    fun warm(type: String, videoId: String) {
        scope.launch {
            val addons = findAllStreamAddons()
            for (addon in addons) {
                val key = cacheKey(addon.baseUrl, type, videoId)
                synchronized(lock) {
                    if (isCached(key) || inFlight.containsKey(key)) return@synchronized
                    val deferred = scope.async { fetch(addon.baseUrl, addon.displayName, addon.logo, type, videoId, key) }
                    inFlight[key] = deferred
                    deferred.invokeOnCompletion { synchronized(lock) { inFlight.remove(key) } }
                }
            }
        }
    }

    // Called from StreamRepositoryImpl.getStreamsFromAddon() — awaits in-flight or returns cached
    suspend fun awaitWarm(baseUrl: String, type: String, videoId: String): List<Stream>? {
        val key = cacheKey(baseUrl, type, videoId)
        val raw = getCached(key) ?: run {
            val deferred = synchronized(lock) { inFlight[key] } ?: return null
            Log.d(TAG, "Awaiting in-flight stream warm key=$key")
            try { deferred.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        } ?: return null
        // Filter out any URLs the probe identified as stub/error videos.
        // If all streams are stubs (e.g. new release not yet cached in TorBox),
        // return null so the caller does a fresh live fetch.
        val filtered = if (stubUrls.isEmpty()) raw else raw.filter { s ->
            val url = s.getStreamUrl()
            url == null || !stubUrls.contains(url)
        }
        return filtered.takeIf { it.isNotEmpty() }
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

                    // Register session immediately so auto-advance and AFR cache-check work even
                    // if the user taps play before probing finishes. Probing will overwrite this
                    // with the reordered list + detectedFps once it completes.
                    playerPreWarmer.update(type, videoId, streams, null)

                    // Warm subtitles for top 3 streams — user may pick stream 2 or 3.
                    // SubtitleWarmer deduplicates by filename+videoSize so redundant calls are free.
                    streams.take(3).forEach { s ->
                        subtitleWarmer.warm(
                            type = type,
                            videoId = videoId,
                            filename = s.behaviorHints?.filename,
                            videoSize = s.behaviorHints?.videoSize
                        )
                    }

                    // Probe in background — does not block awaitWarm(); updates cache + session when done
                    scope.launch { probeAndReorder(streams, key, type, videoId) }

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

    // Probes URLs sequentially up to maxProbe, stops at first valid.
    // AIOStreams pre-sorts by preference so stream #0 is the best match.
    //   - Updates PlayerPreWarmer session for auto-advance fallback
    //   - Pre-connects via the player's own HTTP client to warm its connection pool
    private suspend fun probeAndReorder(
        streams: List<Stream>,
        cacheKey: String,
        type: String,
        videoId: String
    ): List<Stream> {
        if (streams.isEmpty()) return streams
        val settings = debridSettingsDataStore.settings.first()
        val maxProbe = if (settings.streamMaxResults > 0) settings.streamMaxResults else DEFAULT_PROBE_COUNT
        val startMs = System.currentTimeMillis()

        // Start frame rate detection concurrently with probing — all versions of the same
        // title share the same FPS, so detecting stream #0 is sufficient.
        val firstStreamUrl = streams.firstOrNull()?.getStreamUrl()
        val fpsJob = firstStreamUrl?.let { url ->
            scope.async(Dispatchers.IO) {
                runCatching { FrameRateUtils.detectFrameRateFromNextLib(context, url, emptyMap()) }.getOrNull()
            }.also { playerPreWarmer.setFpsJob(type, videoId, it) }
        }

        // Launch all probes in parallel — total time = slowest probe, not sum of all.
        // Await results in AIOStreams-sort order; cancel survivors the moment first valid found.
        val count = minOf(maxProbe, streams.size)
        val probeJobs = (0 until count).map { index ->
            val url = streams[index].getStreamUrl()
            scope.async(Dispatchers.IO) {
                if (url == null) return@async index to false
                val ok = try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Range", "bytes=0-65535")
                        .get()
                        .build()
                    probeClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            false
                        } else {
                            // Detect Comet/proxy stub videos (<1 MB) served for uncached content.
                            val contentRange = resp.header("Content-Range")
                            val totalBytes = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            if (totalBytes != null && totalBytes < MIN_REAL_VIDEO_BYTES) {
                                Log.w(TAG, "Probe: stub content index=$index total=${totalBytes}B (<1MB) url=$url")
                                stubUrls.add(url)
                                false
                            } else {
                                // Consume the body so we verify TorBox can actually deliver
                                // the bytes — not just that the first byte exists.
                                // A partially-cached file will timeout or deliver < 64KB.
                                val bytesRead = resp.body?.bytes()?.size ?: 0
                                bytesRead >= 1024
                            }
                        }
                    }
                } catch (_: Exception) {
                    false
                }
                val elapsedMs = System.currentTimeMillis() - startMs
                if (ok) Log.d(TAG, "Probe valid at index=$index in ${elapsedMs}ms")
                else Log.d(TAG, "Probe failed at index=$index in ${elapsedMs}ms url=$url")
                index to ok
            }
        }

        var firstValidIndex = -1
        for (job in probeJobs) {
            val (index, ok) = job.await()
            if (ok) {
                firstValidIndex = index
                probeJobs.forEach { it.cancel() }
                break
            }
        }

        val reordered = when {
            firstValidIndex <= 0 -> streams // index 0 or not found — no reorder needed
            else -> {
                val winner = streams[firstValidIndex]
                listOf(winner) + streams.subList(0, firstValidIndex) + streams.subList(firstValidIndex + 1, streams.size)
            }
        }

        if (firstValidIndex >= 0) {
            // Update cache with reordered list
            val existing = cache[cacheKey]
            if (existing != null) cache[cacheKey] = existing.copy(streams = reordered)

            // Open the fast-play gate immediately — stream[0] is confirmed live.
            // FPS detection runs concurrently and will update the session once ready.
            playerPreWarmer.update(type, videoId, reordered, null, isProbed = true)

            // If the list reordered, warm subtitles for the new top 3 — cache deduplicates.
            if (firstValidIndex > 0) {
                reordered.take(3).forEach { s ->
                    subtitleWarmer.warm(type, videoId, s.behaviorHints?.filename, s.behaviorHints?.videoSize)
                }
            }

            // Await FPS in the background — AFR preflight will pick it up via awaitFps().
            val detectedFps = try {
                fpsJob?.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (detectedFps != null) {
                Log.d(TAG, "Pre-detected frame rate: ${detectedFps.raw}fps")
                // Update session with FPS now that it's available.
                playerPreWarmer.update(type, videoId, reordered, detectedFps, isProbed = true)
            }

            // Pre-connect via the player's own HTTP client to warm its TCP/TLS connection pool
            val topUrl = reordered.firstOrNull()?.getStreamUrl()
            if (topUrl != null) {
                try {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder().url(topUrl).head().build()
                        PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().close()
                    }
                    Log.d(TAG, "Pre-connected to top stream via player HTTP client")
                } catch (_: Exception) {
                    // Non-critical — player will establish its own connection
                }
            }
        } else {
            fpsJob?.cancel()
            // All probed streams are stubs or unreachable. Clear the warm session so the
            // player doesn't auto-advance into a stub URL. awaitWarm() will filter them
            // out via stubUrls and return null, causing a fresh live fetch.
            Log.w(TAG, "All ${ minOf(maxProbe, streams.size) } probed streams invalid for type=$type videoId=$videoId — clearing warm session")
            playerPreWarmer.clearSession(type, videoId)
        }

        return reordered
    }

    private suspend fun findAllStreamAddons() =
        addonRepository.getInstalledAddons().first()
            .filter { addon -> addon.resources.any { it.name.equals("stream", ignoreCase = true) } }

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

    // Returns true if this URL was confirmed to serve a stub/error video during probing.
    // Used by NuvioNavHost to guard the two-phase fast-path against known-bad streams.
    fun isKnownStub(url: String?) = url != null && stubUrls.contains(url)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface StreamWarmerEntryPoint {
    fun streamWarmer(): StreamWarmer
}
