package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.domain.model.Stream
// FrameRateUtils is in the same package — no import needed
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerPreWarmer"
private const val TTL_MS = 15 * 60 * 1000L
private const val MAX_SESSIONS = 10

@Singleton
class PlayerPreWarmer @Inject constructor() {

    data class WarmSession(
        val type: String,
        val videoId: String,
        val streams: List<Stream>,
        val detectedFps: FrameRateUtils.FrameRateDetection? = null,
        val createdAtMs: Long = System.currentTimeMillis(),
        // true only after probeAndReorder confirms stream[0] is a live, non-stub URL
        val isProbed: Boolean = false
    )

    // LRU cache — up to 5 sessions so browsing multiple detail screens doesn't
    // evict the one the user is most likely to play next.
    private val sessions: MutableMap<String, WarmSession> = Collections.synchronizedMap(
        object : LinkedHashMap<String, WarmSession>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, WarmSession>) = size > MAX_SESSIONS
        }
    )

    @Volatile private var fpsJob: Deferred<FrameRateUtils.FrameRateDetection?>? = null
    @Volatile private var fpsJobKey: String? = null

    private fun key(type: String, videoId: String) = "$type|$videoId"

    // Called by StreamWarmer the moment fps detection starts, so AFR preflight can await it.
    fun setFpsJob(type: String, videoId: String, job: Deferred<FrameRateUtils.FrameRateDetection?>) {
        fpsJobKey = key(type, videoId)
        fpsJob = job
    }

    // AFR preflight calls this instead of launching its own nextlib probe.
    // Awaits the in-flight warm fps detection if one is running for this video.
    suspend fun awaitFps(type: String, videoId: String, timeoutMs: Long): FrameRateUtils.FrameRateDetection? {
        getSession(type, videoId)?.detectedFps?.let { return it }
        if (fpsJobKey != key(type, videoId)) return null
        val job = fpsJob ?: return null
        return try {
            withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun update(
        type: String,
        videoId: String,
        streams: List<Stream>,
        detectedFps: FrameRateUtils.FrameRateDetection? = null,
        isProbed: Boolean = false
    ) {
        if (streams.isEmpty()) return
        sessions[key(type, videoId)] = WarmSession(type, videoId, streams, detectedFps, isProbed = isProbed)
        Log.d(TAG, "Session updated type=$type videoId=$videoId streams=${streams.size} fps=${detectedFps?.raw} probed=$isProbed")
    }

    fun getSession(type: String, videoId: String): WarmSession? {
        val k = key(type, videoId)
        val s = sessions[k] ?: return null
        if (System.currentTimeMillis() - s.createdAtMs > TTL_MS) {
            sessions.remove(k)
            return null
        }
        return s
    }

    // Returns the next stream to try after currentUrl fails.
    // Only valid when isAutoPlay=true (user did not explicitly choose a stream).
    fun getNextStream(type: String, videoId: String, currentUrl: String?): Stream? {
        val s = getSession(type, videoId) ?: return null
        val idx = s.streams.indexOfFirst { it.getStreamUrl() == currentUrl }
        val next = s.streams.getOrNull(idx + 1)
        if (next != null) Log.d(TAG, "Auto-advance: index=${idx+1} name=${next.name}")
        return next
    }

    // Clears the session for a specific item — called when all probes are invalid.
    fun clearSession(type: String, videoId: String) {
        sessions.remove(key(type, videoId))
        if (fpsJobKey == key(type, videoId)) {
            fpsJob = null
            fpsJobKey = null
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerPreWarmerEntryPoint {
    fun playerPreWarmer(): PlayerPreWarmer
}
