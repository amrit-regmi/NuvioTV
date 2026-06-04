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
// Background warms probe fewer streams to limit CDN burst on startup.
// User-initiated stream loading still uses the full streamMaxResults setting.
private const val BACKGROUND_PROBE_COUNT = 3
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
    fun warm(type: String, videoId: String, resumePositionMs: Long? = null, durationMs: Long? = null) {
        scope.launch {
            val addons = findAllStreamAddons()
            for (addon in addons) {
                val key = cacheKey(addon.baseUrl, type, videoId)
                synchronized(lock) {
                    if (isCached(key) || inFlight.containsKey(key)) return@synchronized
                    val deferred = scope.async { fetch(addon.baseUrl, addon.displayName, addon.logo, type, videoId, key, resumePositionMs, durationMs) }
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
        key: String,
        resumePositionMs: Long? = null,
        durationMs: Long? = null
    ): List<Stream>? {
        return try {
            val url = buildStreamUrl(baseUrl, type, videoId)
            Log.d(TAG, "Pre-fetching streams: $url")
            when (val result = safeApiCall(context) { api.getStreams(url) }) {
                is NetworkResult.Success -> {
                    val streams = result.data.streams?.map { it.toDomain(addonName, addonLogo) } ?: emptyList()
                    Log.d(TAG, "Fetched ${streams.size} streams for addon=$addonName type=$type videoId=$videoId")

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

                    // Probe inline — cache is only populated after probing validates streams.
                    // awaitWarm() awaits this deferred, so it never serves unprobed streams.
                    probeAndReorder(streams, key, type, videoId, resumePositionMs, durationMs)
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
        videoId: String,
        resumePositionMs: Long? = null,
        durationMs: Long? = null
    ): List<Stream>? {
        if (streams.isEmpty()) return streams
        val settings = debridSettingsDataStore.settings.first()
        val maxProbe = if (settings.streamMaxResults > 0) minOf(settings.streamMaxResults, BACKGROUND_PROBE_COUNT) else BACKGROUND_PROBE_COUNT
        val startMs = System.currentTimeMillis()

        // Start frame rate detection concurrently with probing — all versions of the same
        // title share the same FPS, so detecting stream #0 is sufficient.
        val firstStreamUrl = streams.firstOrNull()?.getStreamUrl()
        val fpsJob = firstStreamUrl?.let { url ->
            scope.async(Dispatchers.IO) {
                runCatching { FrameRateUtils.detectFrameRateFromNextLib(context, url, emptyMap()) }.getOrNull()
            }.also { playerPreWarmer.setFpsJob(type, videoId, it) }
        }

        // Probe sequentially — stop at the first valid stream or on 429 (rate limit).
        // AIOStreams pre-sorts by quality so stream[0] is almost always valid for cached content.
        // Sequential probing eliminates the burst of parallel Range requests that triggers
        // TorBox/Comet rate limits when multiple CW items warm concurrently on startup.
        var firstValidIndex = -1
        var firstValidContentType: String? = null
        var firstValidTotalBytes: Long? = null
        val count = minOf(maxProbe, streams.size)
        for (index in 0 until count) {
            val url = streams[index].getStreamUrl()
            if (url == null) {
                Log.d(TAG, "Probe skipped at index=$index (no URL)")
                continue
            }
            var rawContentType: String? = null
            var capturedTotalBytes: Long? = null
            var rateLimited = false
            val ok = try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=0-65535")
                    .get()
                    .build()
                probeClient.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 429 -> {
                            Log.w(TAG, "Probe: rate-limited (429) at index=$index — stopping probe for type=$type videoId=$videoId")
                            rateLimited = true
                            false
                        }
                        !resp.isSuccessful -> false
                        else -> {
                            rawContentType = resp.header("Content-Type")
                            // Reject HTML responses — Comet/proxy returns an error/login page
                            // for uncached content, which can pass the byte-count check below.
                            val ct = rawContentType?.substringBefore(';')?.trim()?.lowercase()
                            if (ct != null && (ct == "text/html" || ct == "text/plain" || ct.startsWith("application/json"))) {
                                Log.w(TAG, "Probe: non-video content type index=$index ct=$rawContentType url=$url")
                                rawContentType = null
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
                                    capturedTotalBytes = totalBytes
                                    // Consume the body so we verify TorBox can actually deliver
                                    // the bytes — not just that the first byte exists.
                                    // A partially-cached file will timeout or deliver < 64KB.
                                    val bytesRead = resp.body?.bytes()?.size ?: 0
                                    bytesRead >= 1024
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                false
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            if (ok) {
                Log.d(TAG, "Probe valid at index=$index in ${elapsedMs}ms contentType=$rawContentType")
                firstValidIndex = index
                firstValidContentType = rawContentType
                firstValidTotalBytes = capturedTotalBytes
                break
            } else {
                Log.d(TAG, "Probe failed at index=$index in ${elapsedMs}ms url=$url")
                if (rateLimited) break
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
            // Populate cache now that streams are validated — never caches unprobed results
            cache[cacheKey] = CachedStreams(reordered, System.currentTimeMillis())
            Log.d(TAG, "Cached ${reordered.size} probed streams for type=$type videoId=$videoId")

            // Resolve MIME type from probe Content-Type header, falling back to filename.
            // Stored in WarmSession so the player's init block can skip its own probe.
            val detectedMimeType = normalizeMimeType(firstValidContentType)
                ?: normalizeMimeType(reordered.firstOrNull()?.behaviorHints?.filename
                    ?.substringAfterLast('.'))

            // Open the fast-play gate immediately — stream[0] is confirmed live.
            // FPS detection runs concurrently and will update the session once ready.
            playerPreWarmer.update(type, videoId, reordered, null, isProbed = true, detectedMimeType = detectedMimeType)

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
                // Update session with FPS — preserve the detectedMimeType from probing.
                playerPreWarmer.update(type, videoId, reordered, detectedFps, isProbed = true, detectedMimeType = detectedMimeType)
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

            // For InProgress CW items: fire a Range request at the resume byte offset to prime
            // TorBox's CDN cache at that position, eliminating the seek stall on resume.
            // Byte offset = (position - 5s) / duration * fileSize — approximation is sufficient.
            val fileSize = firstValidTotalBytes
            if (resumePositionMs != null && durationMs != null && durationMs > 0
                && fileSize != null && fileSize > 0 && topUrl != null) {
                val safePositionMs = (resumePositionMs - 5_000L).coerceAtLeast(0L)
                val byteOffset = (safePositionMs.toDouble() / durationMs * fileSize).toLong()
                val rangeEnd = (byteOffset + 65535L).coerceAtMost(fileSize - 1)
                if (byteOffset in 0 until fileSize) {
                    try {
                        withContext(Dispatchers.IO) {
                            val resumeRequest = Request.Builder()
                                .url(topUrl)
                                .addHeader("Range", "bytes=$byteOffset-$rangeEnd")
                                .get()
                                .build()
                            probeClient.newCall(resumeRequest).execute().close()
                        }
                        Log.d(TAG, "Resume pre-fetch: offset=${byteOffset}B positionMs=${safePositionMs} durationMs=$durationMs fileSize=${fileSize}B")
                    } catch (_: Exception) {
                        // Non-critical — player will seek normally
                    }
                }
            }
        } else {
            fpsJob?.cancel()
            // All probed streams are stubs or unreachable. Evict both caches so the next
            // awaitWarm() triggers a fresh AIOStreams fetch rather than looping on the same
            // dead URLs. Without this, the player retries and gets the same stale stream list.
            Log.w(TAG, "All ${ minOf(maxProbe, streams.size) } probed streams invalid for type=$type videoId=$videoId — clearing warm session and cache")
            playerPreWarmer.clearSession(type, videoId)
            cache.remove(cacheKey)
            return null
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

    // Maps a raw Content-Type header value or file extension to a canonical MIME string.
    // Covers the formats TorBox and AIOStreams actually serve — not exhaustive.
    private fun normalizeMimeType(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            s == "video/x-matroska" || s == "mkv" -> "video/x-matroska"
            s == "video/mp4" || s == "mp4" || s == "m4v" -> "video/mp4"
            s == "video/webm" || s == "webm" -> "video/webm"
            s == "video/mp2t" || s == "video/mpeg" || s == "ts" || s == "m2ts" -> "video/mp2t"
            s == "video/avi" || s == "avi" -> "video/avi"
            s == "application/x-mpegurl" || s == "application/vnd.apple.mpegurl" || s == "m3u8" ->
                "application/x-mpegURL"
            s == "application/dash+xml" || s == "mpd" -> "application/dash+xml"
            s.startsWith("video/") -> s
            else -> null
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface StreamWarmerEntryPoint {
    fun streamWarmer(): StreamWarmer
}
