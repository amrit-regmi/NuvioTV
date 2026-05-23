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
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerPreWarmer"
private const val TTL_MS = 5 * 60 * 1000L

@Singleton
class PlayerPreWarmer @Inject constructor() {

    data class WarmSession(
        val type: String,
        val videoId: String,
        val streams: List<Stream>,
        val detectedFps: FrameRateUtils.FrameRateDetection? = null,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @Volatile private var session: WarmSession? = null
    @Volatile private var fpsJob: Deferred<FrameRateUtils.FrameRateDetection?>? = null
    @Volatile private var fpsJobKey: String? = null

    // Called by StreamWarmer the moment fps detection starts, so AFR preflight can await it.
    fun setFpsJob(type: String, videoId: String, job: Deferred<FrameRateUtils.FrameRateDetection?>) {
        fpsJobKey = "$type|$videoId"
        fpsJob = job
    }

    // AFR preflight calls this instead of launching its own nextlib probe.
    // Awaits the in-flight warm fps detection if one is running for this video.
    suspend fun awaitFps(type: String, videoId: String, timeoutMs: Long): FrameRateUtils.FrameRateDetection? {
        getSession(type, videoId)?.detectedFps?.let { return it }
        if (fpsJobKey != "$type|$videoId") return null
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
        detectedFps: FrameRateUtils.FrameRateDetection? = null
    ) {
        if (streams.isEmpty()) return
        session = WarmSession(type, videoId, streams, detectedFps)
        Log.d(TAG, "Session updated type=$type videoId=$videoId streams=${streams.size} fps=${detectedFps?.raw}")
    }

    fun getSession(type: String, videoId: String): WarmSession? =
        session?.takeIf {
            it.type == type &&
            it.videoId == videoId &&
            System.currentTimeMillis() - it.createdAtMs <= TTL_MS
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

    fun clear() {
        session = null
        fpsJob = null
        fpsJobKey = null
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerPreWarmerEntryPoint {
    fun playerPreWarmer(): PlayerPreWarmer
}
