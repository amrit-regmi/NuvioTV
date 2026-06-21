package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.feature.FeatureAvailabilityManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    featureAvailabilityManager: FeatureAvailabilityManager
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    /**
     * Per-feature AVAILABILITY map from the super admin (via `/api/me`). Empty = all
     * available (fail-open). Used to hide/lock UI for features made unavailable, layered
     * over the existing primary/secondary profile gating.
     */
    val featureAvailability: StateFlow<Map<String, Boolean>> = featureAvailabilityManager.features

    // Seed with the ACTUAL current primary state (not an optimistic `true`). The Settings rail
    // gates the primary-only ACCOUNT (device management) and PROFILES sections on this flow via
    // remember(...); an optimistic `true` seed briefly (or, if activeProfileId never re-emits,
    // persistently) exposes the device/profile management UI under a secondary profile. Mirrors
    // AccountViewModel.observeActiveProfile, which deliberately seeds from the real value so a
    // secondary profile NEVER sees the primary-only sections. (FIX: device UI primary-only.)
    val isPrimaryProfileActive: StateFlow<Boolean> = profileManager.activeProfileId
        .map { it == 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, profileManager.isPrimaryProfileActive)

    val canAddProfile: Boolean
        get() = profileManager.canCreateProfile

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean,
        usesPrimaryPlugins: Boolean,
        avatarId: String? = null
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            val existingIds = profileManager.profiles.value.map { it.id }.toSet()
            val success = profileManager.createProfile(
                name = name,
                avatarColorHex = avatarColorHex,
                avatarId = avatarId
            )
            if (success) {
                val profiles = profileManager.profiles.value
                val newProfile = profiles.firstOrNull { it.id !in existingIds }
                if (newProfile != null && (usesPrimaryAddons || usesPrimaryPlugins)) {
                    profileManager.updateProfile(
                        newProfile.copy(
                            usesPrimaryAddons = usesPrimaryAddons,
                            usesPrimaryPlugins = usesPrimaryPlugins
                        )
                    )
                }
                profileSyncService.pushToRemote()
            }
            _isCreating.value = false
        }
    }

    fun updateProfile(profile: UserProfile) {
        viewModelScope.launch {
            profileManager.updateProfile(profile)
            profileSyncService.pushToRemote()
        }
    }

    fun deleteProfile(id: Int) {
        viewModelScope.launch {
            profileManager.deleteProfile(id)
            profileSyncService.deleteProfileData(id)
            profileSyncService.pushToRemote()
        }
    }
}
