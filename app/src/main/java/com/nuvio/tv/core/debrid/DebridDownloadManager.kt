package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DebridDownloadManager"
private const val POLL_INTERVAL_MS = 5_000L
private const val MAX_POLL_DURATION_MS = 30L * 60L * 1000L // 30 minutes

enum class DebridDownloadStatus {
    QUEUED,
    DOWNLOADING,
    READY,
    FAILED,
    NO_VALID_SOURCE
}

data class DebridDownloadState(
    val streamKey: String,
    val streamName: String,
    val status: DebridDownloadStatus,
    val etaMinutes: Int? = null,
    val infoHash: String? = null
)

/**
 * Provider-agnostic download manager for non-cached debrid streams.
 * Queues a single active download at a time — starting a new download
 * cancels the previous one.
 *
 * Uses the catalog-addon backend's /prepare and /status endpoints.
 */
@Singleton
class DebridDownloadManager @Inject constructor(
    private val catalogAddonApi: CatalogAddonApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeDownload = MutableStateFlow<DebridDownloadState?>(null)
    val activeDownload: StateFlow<DebridDownloadState?> = _activeDownload.asStateFlow()

    private var pollJob: Job? = null

    /**
     * Queue a stream for download. Cancels any existing active download.
     * Returns immediately after queuing; polls backend in the background.
     *
     * @param stream The stream to queue
     * @param contentType "movie" or "series"
     * @param videoId The Stremio video ID (e.g. "tt1234567" or "tt1234567:1:2")
     */
    fun queue(stream: Stream, contentType: String, videoId: String) {
        val streamKey = streamKey(stream)
        val streamName = stream.name ?: stream.addonName

        // Cancel any existing download
        pollJob?.cancel()
        pollJob = null
        _activeDownload.value = DebridDownloadState(
            streamKey = streamKey,
            streamName = streamName,
            status = DebridDownloadStatus.QUEUED,
            etaMinutes = null
        )

        val apiType = when {
            contentType.equals("series", ignoreCase = true) -> "series"
            else -> "movie"
        }

        pollJob = scope.launch {
            try {
                // Step 1: Call /prepare to queue the download on the backend
                val secret = BuildConfig.CATALOG_SECRET.trim()
                val authorization = if (secret.isNotBlank()) "Bearer $secret" else null

                val prepareResponse = try {
                    catalogAddonApi.prepareStream(apiType, videoId, authorization)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "prepareStream failed: ${e.message}")
                    null
                }

                val prepareBody = prepareResponse?.body()
                val prepareStatus = prepareBody?.status

                when (prepareStatus) {
                    "already_cached" -> {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.READY,
                            etaMinutes = 0
                        )
                        return@launch
                    }
                    "no_valid_hash" -> {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.NO_VALID_SOURCE
                        )
                        return@launch
                    }
                    "slots_full" -> {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.FAILED
                        )
                        return@launch
                    }
                    else -> {
                        // "queued" or network failure — proceed to poll
                        val etaMinutes = prepareBody?.etaMinutes
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.DOWNLOADING,
                            etaMinutes = etaMinutes,
                            infoHash = prepareBody?.infoHash
                        )
                    }
                }

                // Step 2: Poll /status until cached or timeout
                val startMs = System.currentTimeMillis()
                while (System.currentTimeMillis() - startMs < MAX_POLL_DURATION_MS) {
                    delay(POLL_INTERVAL_MS)

                    val statusResponse = try {
                        catalogAddonApi.getStreamStatus(apiType, videoId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "getStreamStatus failed: ${e.message}")
                        continue
                    }

                    val items = statusResponse.body()?.items.orEmpty()
                    val activeHash = _activeDownload.value?.infoHash
                    val item = if (activeHash != null) {
                        items.firstOrNull { it.infoHash.equals(activeHash, ignoreCase = true) }
                            ?: items.firstOrNull()
                    } else {
                        items.firstOrNull { it.cached == true } ?: items.firstOrNull()
                    }

                    if (item?.cached == true || item?.hasUrl == true) {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.READY,
                            etaMinutes = 0
                        )
                        return@launch
                    }
                }

                // Timeout — mark as failed
                Log.w(TAG, "Download polling timed out for $streamKey")
                _activeDownload.value = _activeDownload.value?.copy(
                    status = DebridDownloadStatus.FAILED
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download error for $streamKey: ${e.message}")
                _activeDownload.value = _activeDownload.value?.copy(
                    status = DebridDownloadStatus.FAILED
                )
            }
        }
    }

    /**
     * Cancel the active download and clear state.
     */
    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _activeDownload.value = null
    }

    /**
     * Returns true if the given stream is the currently active download.
     */
    fun isActiveDownload(stream: Stream): Boolean {
        val key = streamKey(stream)
        return _activeDownload.value?.streamKey == key
    }

    companion object {
        fun streamKey(stream: Stream): String {
            val hash = stream.infoHash ?: stream.clientResolve?.infoHash
            if (hash != null) return "hash:${hash.lowercase()}"
            return "url:${stream.url ?: stream.externalUrl ?: stream.name ?: stream.addonName}"
        }
    }
}
