package com.nuvio.tv.core.network

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerHealthNotifier"

/**
 * Tracks the reachability of OUR self-hosted backend (the reco / catalog-addon host on
 * [com.nuvio.tv.core.reco.RecoBackend.host]) and surfaces a single, non-blocking
 * "Server is currently experiencing issues" notice when it is down.
 *
 * Server-down is DISTINCT from logout: it never changes auth state, never clears caches,
 * and never forces the login screen. A previously-logged-in user with a valid cached
 * session stays authenticated; only our built-in provider / reco / shared TorBox key /
 * sync degrade until the server returns. Third-party addons (different hosts) are
 * unaffected and keep working underneath.
 *
 * Signalling rules (see [reportOurHostUnreachable] / [reportOurHostHealthy]):
 * - A connection failure / timeout / 5xx on an OUR-host request marks the backend
 *   unreachable and emits the notice AT MOST ONCE per outage (de-bounced; we don't spam
 *   it on every failing row/poster). The next successful our-host response re-arms it so a
 *   later outage notifies again.
 * - 4xx (auth/not-found/etc.) is NOT a server outage and does not trigger the notice.
 */
@Singleton
class ServerHealthNotifier @Inject constructor() {

    // replay = 0 so a notice only reaches collectors that are already listening (the Home
    // screen); we don't want a stale outage popping up much later. DROP_OLDEST keeps the
    // notifier non-blocking on the OkHttp thread.
    private val _serverIssues = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emits once each time our backend transitions into an outage (de-bounced). */
    val serverIssues: SharedFlow<Unit> = _serverIssues.asSharedFlow()

    // false = backend currently believed reachable (notice re-armed for the next outage).
    // Guarded as an atomic so the single transition into "down" emits exactly one notice
    // even under concurrent failing requests.
    private val outageActive = AtomicBoolean(false)

    /**
     * Called when an OUR-host request fails in a way that indicates the backend is down
     * (connection refused / timeout / DNS / 5xx). Emits the notice only on the first such
     * failure of an outage.
     */
    fun reportOurHostUnreachable() {
        if (outageActive.compareAndSet(false, true)) {
            Log.w(TAG, "Our backend appears to be down; surfacing server-issues notice")
            _serverIssues.tryEmit(Unit)
        }
    }

    /** Called when an OUR-host request succeeds: re-arm so a later outage notifies again. */
    fun reportOurHostHealthy() {
        if (outageActive.compareAndSet(true, false)) {
            Log.d(TAG, "Our backend reachable again; re-armed server-issues notice")
        }
    }
}
