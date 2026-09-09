package com.nuvio.tv.core.stream

import android.util.Log
import com.nuvio.tv.domain.model.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide, session-scoped registry of the LIVE stream availability the app has
 * observed for a title, keyed by its BASE content id (imdb/tmdb id with any trailing
 * :season:episode stripped).
 *
 * `streamStatus` on catalog/meta rows is DYNAMIC — the backend recomputes it on every
 * catalog/meta fetch and marks it `no-store`. But once a catalog row is loaded into the
 * in-memory home rows it is never re-derived, so a title that gained cached streams AFTER
 * the row was fetched keeps showing a stale "No streams" pill. This registry lets the
 * stream-resolve paths (StreamWarmer prewarm, the stream picker) publish the ground truth
 * they just observed so the home tiles, focus hero and details pill can clear the stale pill
 * live without a hard refresh.
 *
 * Availability only ever PROMOTES here: a session mark of INSTANT is never downgraded, so a
 * transient miss on one addon can't reintroduce a false "No streams".
 */
@Singleton
class StreamAvailabilityRegistry @Inject constructor() {

    private val _statuses = MutableStateFlow<Map<String, StreamStatus>>(emptyMap())

    /** base content id -> observed StreamStatus. Observers patch UI state from emissions. */
    val statuses: StateFlow<Map<String, StreamStatus>> = _statuses.asStateFlow()

    /** Records that the title behind [videoId] has at least one servable stream (INSTANT). */
    fun markAvailable(videoId: String) = mark(videoId, StreamStatus.INSTANT)

    /**
     * Records that the title behind [videoId] has no known streams. Applied only when the
     * title is not already known-available this session, so a single addon miss never flips
     * a genuinely-available title back to "No streams".
     */
    fun markUnavailable(videoId: String) {
        val id = baseContentId(videoId)
        if (id.isBlank()) return
        if (_statuses.value[id] == StreamStatus.INSTANT) return
        mark(id, StreamStatus.UNAVAILABLE)
    }

    private fun mark(videoId: String, status: StreamStatus) {
        val id = baseContentId(videoId)
        if (id.isBlank()) return
        _statuses.update { current ->
            val existing = current[id]
            // Never downgrade a known-available title.
            if (existing == StreamStatus.INSTANT && status != StreamStatus.INSTANT) return@update current
            if (existing == status) return@update current
            Log.d(TAG, "streamStatus $id: ${existing ?: "?"} -> $status")
            current + (id to status)
        }
    }

    /** Latest observed status for the base id of [videoId], or null if unseen this session. */
    fun statusFor(videoId: String): StreamStatus? = _statuses.value[baseContentId(videoId)]

    fun clear() {
        _statuses.value = emptyMap()
    }

    companion object {
        private const val TAG = "StreamAvailability"

        /**
         * Strips a trailing `:<season>:<episode>` from a series episode video id so a
         * per-episode resolve maps to the SERIES tile. Leaves movie ids (`tt…`, `tmdb:123`)
         * and bare ids untouched. Handles `tmdb:` prefixes (whose id itself contains a colon).
         */
        fun baseContentId(videoId: String): String {
            val trimmed = videoId.trim()
            if (trimmed.isEmpty()) return trimmed
            val parts = trimmed.split(":")
            if (parts.size >= 2) {
                val last = parts.last().toIntOrNull()
                val secondLast = parts[parts.size - 2].toIntOrNull()
                if (last != null && secondLast != null) {
                    return parts.dropLast(2).joinToString(":")
                }
            }
            return trimmed
        }
    }
}
