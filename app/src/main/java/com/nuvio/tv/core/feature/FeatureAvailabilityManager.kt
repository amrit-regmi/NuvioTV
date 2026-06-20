package com.nuvio.tv.core.feature

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.reco.RecommendationRepository
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature availability keys returned by the backend `/api/me` `features` map.
 * The super admin sets AVAILABILITY; when a feature is unavailable the TV hides/locks it.
 */
object FeatureKeys {
    const val PERSONALIZATION = "personalization"
    const val STREAM_PROVIDERS = "stream_providers"
    const val CATALOGS = "catalogs"
    const val CONNECTED_DEVICES = "connected_devices"
}

/**
 * Holds the per-feature AVAILABILITY map resolved from the super admin's kill switches,
 * fetched from `GET /api/me/{user_id}`.
 *
 * Timing: refreshes on app load (when an authenticated session appears) and on profile
 * switch (active profile id change). No periodic polling.
 *
 * Fail-open: an empty/missing map (default state, or a failed/erroring call) means every
 * feature is available. Unknown keys are also treated as available.
 */
@Singleton
class FeatureAvailabilityManager @Inject constructor(
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val recommendationRepository: RecommendationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _features = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Observable availability map. Empty = all available (fail-open). */
    val features: StateFlow<Map<String, Boolean>> = _features.asStateFlow()

    @Volatile private var lastUserId: String? = null

    init {
        // App load: refresh whenever we have an authenticated session.
        scope.launch {
            authManager.authState.collectLatest { state ->
                if (state is AuthState.FullAccount) {
                    lastUserId = state.userId
                    refresh()
                } else {
                    // Signed out / loading → reset to fail-open.
                    lastUserId = null
                    _features.value = emptyMap()
                }
            }
        }
        // Profile switch: re-fetch availability for the newly active profile.
        scope.launch {
            profileManager.activeProfileId.collectLatest {
                if (lastUserId != null) refresh()
            }
        }
    }

    /** Fail-open availability check: missing/unknown key → available. */
    fun isAvailable(key: String): Boolean = _features.value[key] ?: true

    /** Re-fetches `/api/me` and updates the availability map. Safe to call repeatedly. */
    fun refresh() {
        scope.launch {
            val userId = authManager.currentUserId ?: return@launch
            val token = authManager.currentAccessToken() ?: return@launch
            val me = recommendationRepository.fetchMe(userId, token)
            if (me != null) {
                _features.value = me.features
                Log.d("FeatureAvail", "features=${me.features}")
            }
            // On null (call failed) we keep prior state / fail-open default.
        }
    }
}
