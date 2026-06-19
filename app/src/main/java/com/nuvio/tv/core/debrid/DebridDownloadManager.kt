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
    val infoHash: String? = null,
    val progressPct: Float? = null,
    val seeds: Int? = null,
    val speedMbps: Double? = null,
    val failureReason: String? = null
)

/**
 * Tracks an in-progress catalog-addon stream prepare operation across ViewModels.
 * @param imdbId The IMDB ID being prepared (e.g. "tt1234567")
 * @param title Human-readable title shown in the cancel-confirmation dialog
 * @param type Content type ("movie" or "series") — used to build the cancel DELETE URL
 */
data class ActivePrepare(
    val imdbId: String,
    val title: String,
    val type: String
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

    // Tracks which content item is currently being prepared by a MetaDetailsViewModel.
    // Used to enforce single-download-at-a-time across detail screens.
    private val _activePrepare = MutableStateFlow<ActivePrepare?>(null)
    val activePrepare: StateFlow<ActivePrepare?> = _activePrepare.asStateFlow()

    private var pollJob: Job? = null

    /** Register a prepare operation as the current active one. Replaces any prior entry. */
    fun setPrepareActive(imdbId: String, title: String, type: String) {
        _activePrepare.value = ActivePrepare(imdbId = imdbId, title = title, type = type)
    }

    /**
     * Clear the active prepare registration without sending a cancel to the backend.
     * Use this on successful completion so the cached torrent is not deleted from TorBox.
     */
    fun clearPrepare() {
        _activePrepare.value = null
    }

    /**
     * Cancel the active prepare: clears the registration AND fires a fire-and-forget
     * DELETE /stream/{type}/{videoId}/prepare to remove the torrent from TorBox.
     * Call this on explicit user cancellation or when navigating away mid-download.
     */
    fun cancelAndClearPrepare() {
        val current = _activePrepare.value
        _activePrepare.value = null
        if (current != null) {
            val secret = BuildConfig.CATALOG_SECRET.trim()
            val auth = if (secret.isNotBlank()) "Bearer $secret" else null
            scope.launch {
                try {
                    catalogAddonApi.cancelPrepare(current.type, current.imdbId, auth)
                } catch (e: Exception) {
                    Log.w(TAG, "cancelPrepare failed: ${e.message}")
                }
            }
        }
    }

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

        // Register at app scope so cancel() fires the DELETE to TorBox
        val imdbId = videoId.substringBefore(':')
        setPrepareActive(imdbId, streamName ?: videoId, apiType)

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
                        clearPrepare()
                        return@launch
                    }
                    "no_valid_hash" -> {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.NO_VALID_SOURCE
                        )
                        clearPrepare()
                        return@launch
                    }
                    "no_seeders" -> {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.NO_VALID_SOURCE
                        )
                        clearPrepare()
                        return@launch
                    }
                    "slots_full" -> {
                        _activeDownload.value = _activeDownload.value?.copy(
                            status = DebridDownloadStatus.FAILED,
                            failureReason = "slots_full"
                        )
                        clearPrepare()
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
                            etaMinutes = 0,
                            progressPct = 100f,
                            seeds = null,
                            speedMbps = null
                        )
                        clearPrepare()
                        return@launch
                    }
                    // Update live progress from TorBox via status response
                    _activeDownload.value = _activeDownload.value?.copy(
                        progressPct = item?.progressPct?.toFloat(),
                        seeds = item?.seeds,
                        speedMbps = item?.downloadSpeedMbps,
                        etaMinutes = item?.etaSeconds?.let { (it / 60).coerceAtLeast(1) }
                            ?: _activeDownload.value?.etaMinutes
                    )
                }

                // Timeout — mark as failed
                Log.w(TAG, "Download polling timed out for $streamKey")
                _activeDownload.value = _activeDownload.value?.copy(
                    status = DebridDownloadStatus.FAILED
                )
                clearPrepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download error for $streamKey: ${e.message}")
                _activeDownload.value = _activeDownload.value?.copy(
                    status = DebridDownloadStatus.FAILED
                )
                clearPrepare()
            }
        }
    }

    /**
     * Cancel the active download and clear state.
     * Also fires the backend DELETE to remove the torrent from TorBox.
     */
    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _activeDownload.value = null
        cancelAndClearPrepare()
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
