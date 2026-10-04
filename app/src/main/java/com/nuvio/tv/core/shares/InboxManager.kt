package com.nuvio.tv.core.shares

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide "Recommend to…" inbox holder: poll `GET /shares/inbox` while the app is foregrounded
 * (every [POLL_INTERVAL_MS]) plus once immediately on every foreground transition (app resume),
 * and expose the merged list + its size as the sidebar badge count.
 *
 * Mirrors [com.nuvio.tv.core.feature.FeatureAvailabilityManager]'s Hilt-Singleton-with-its-own-
 * CoroutineScope shape (gates on [AuthManager.authState], resets to empty when signed out) and
 * [com.nuvio.tv.core.stream.StreamWarmer]'s `ProcessLifecycleOwner` usage for foreground/resume
 * detection — this app has no existing periodic-while-foregrounded poll to copy verbatim, so this
 * combines those two precedents.
 */
@Singleton
class InboxManager @Inject constructor(
    private val authManager: AuthManager,
    private val sharesRepository: SharesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val _items = MutableStateFlow<List<InboxItemDto>>(emptyList())
    val items: StateFlow<List<InboxItemDto>> = _items.asStateFlow()

    /** Unread badge count for the sidebar nav entry — simply the inbox's current item count. */
    val badgeCount: StateFlow<Int>
        get() = _badgeCount.asStateFlow()
    private val _badgeCount = MutableStateFlow(0)

    private var pollJob: Job? = null
    @Volatile private var isForegrounded = false
    @Volatile private var isAuthenticated = false

    init {
        scope.launch {
            authManager.authState.collectLatest { state ->
                isAuthenticated = state is AuthState.FullAccount
                if (isAuthenticated) {
                    refresh()
                    restartPollingIfNeeded()
                } else {
                    _items.value = emptyList()
                    _badgeCount.value = 0
                    pollJob?.cancel()
                    pollJob = null
                }
            }
        }

        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForegrounded = true
                if (isAuthenticated) {
                    refresh()
                    restartPollingIfNeeded()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                isForegrounded = false
                pollJob?.cancel()
                pollJob = null
            }
        }
        // ProcessLifecycleOwner requires main-thread registration.
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        }
    }

    private fun restartPollingIfNeeded() {
        if (!isForegrounded || !isAuthenticated) return
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    /** Explicit refresh — called on login, foreground/resume, and periodically while visible. */
    fun refresh() {
        scope.launch {
            runCatching { sharesRepository.getInbox() }
                .onSuccess { list ->
                    _items.value = list
                    _badgeCount.value = list.size
                }
                .onFailure { e ->
                    Log.w(TAG, "Inbox refresh failed", e)
                    // Keep prior state on a transient failure — don't flash the badge to 0.
                }
        }
    }

    /** Optimistically removes an item from the local inbox list once it's been handled. */
    fun removeLocally(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        _badgeCount.value = _items.value.size
    }

    companion object {
        private const val TAG = "InboxManager"
        private const val POLL_INTERVAL_MS = 3 * 60 * 1000L // 3 minutes, within the plan's 2-5 min window
    }
}
