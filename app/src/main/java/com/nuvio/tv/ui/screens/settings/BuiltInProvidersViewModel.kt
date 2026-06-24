package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.device.DeviceCapabilityDetector
import com.nuvio.tv.core.reco.RecoBackend
import com.nuvio.tv.data.local.DeviceProfileDataStore
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.data.remote.api.DeviceProfileDto
import com.nuvio.tv.data.remote.api.DeviceProfileUpdateDto
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BuiltInProvidersUiState(
    val isCatalogEnabled: Boolean = true,
    val useRecommendations: Boolean = true,
    val personalizationAvailable: Boolean = true,
    // Device-profile (hardware capability) management is per-DEVICE config, not per-profile —
    // restricted to the primary/admin profile (F28). Seeded with the REAL current value so a
    // secondary profile never momentarily sees the device-profile section.
    val isPrimaryProfileActive: Boolean = false,
    val streamEngineEnabled: Boolean = false,
    val useBuiltinSubtitles: Boolean = true,
    val useAutoDetectedProfile: Boolean = true,
    val deviceProfile: DeviceProfileDto? = null,
    val editMaxResolution: String = "2160p",
    val editHdrTypes: Set<String> = setOf("HDR10", "DolbyVision", "HLG"),
    val editCodecs: Set<String> = setOf("H.265", "AV1", "H.264"),
    val editAudioFormats: Set<String> = setOf("Dolby Atmos", "DTS:X", "AAC"),
    val editMaxAudioChannels: String = "7.1",
)

@HiltViewModel
class BuiltInProvidersViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val deviceProfileDataStore: DeviceProfileDataStore,
    private val catalogAddonApi: CatalogAddonApi,
    private val deviceCapabilityDetector: DeviceCapabilityDetector,
    private val homeCatalogSettingsSyncService: com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService,
    private val featureAvailabilityManager: com.nuvio.tv.core.feature.FeatureAvailabilityManager,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        // F32: single source of truth — derives from BuildConfig.RECO_API_BASE_URL via RecoBackend.
        private val CATALOG_HOST = RecoBackend.host
    }

    // Fail-safe seed (false): never momentarily expose the primary-only Device Profile section to a
    // secondary profile before the real identity resolves. Seeding false (rather than from
    // profileManager.isPrimaryProfileActive, whose backing StateFlow seeds 1) ensures a secondary
    // profile is never read as primary before the datastore delivers the real active id.
    private val _uiState = MutableStateFlow(BuiltInProvidersUiState(isPrimaryProfileActive = false))
    val uiState: StateFlow<BuiltInProvidersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Gate on the REAL profile identity (active profile's own isPrimary), NOT an
            // addon-effective id: a secondary profile that uses primary addons is remapped to id 1
            // for addon storage only and must still hide the per-DEVICE Device Profile section.
            profileManager.isPrimaryProfileActiveFlow.collectLatest { isPrimary ->
                android.util.Log.d(
                    "BuiltInProvidersVM",
                    "device-profile gate: activeId=${profileManager.activeProfileId.value} " +
                        "activeProfile.isPrimary=${profileManager.activeProfile?.isPrimary} resolved=$isPrimary"
                )
                _uiState.update { it.copy(isPrimaryProfileActive = isPrimary) }
            }
        }
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                deviceProfileDataStore.isStreamEngineEnabled,
                deviceProfileDataStore.isUserOverridden
            ) { _, streamEngineEnabled, isUserOverridden ->
                // isCatalogEnabled is driven by the Supabase blob (useBuiltinCatalog), NOT the
                // local addon-enabled flag, so the toggle reflects + persists the same value the
                // web /configure reads. We intentionally do not surface catalogAddon.enabled here.
                Pair(streamEngineEnabled, !isUserOverridden)
            }.collectLatest { (streamEngineEnabled, useAutoDetect) ->
                _uiState.update {
                    it.copy(
                        streamEngineEnabled = streamEngineEnabled,
                        useAutoDetectedProfile = useAutoDetect
                    )
                }
            }
        }
        viewModelScope.launch {
            loadDeviceProfile()
        }
        viewModelScope.launch {
            // Per-profile "use recommendation provider" master flag, mirrored from/to the same
            // Supabase home-catalog blob the web dashboard writes (settings_json.useRecommendations).
            val useReco = homeCatalogSettingsSyncService.pullUseRecommendations()
            _uiState.update { it.copy(useRecommendations = useReco) }
        }
        viewModelScope.launch {
            // Per-profile "use built-in catalog" master flag, mirrored from/to the same Supabase
            // home-catalog blob the web /configure reads (settings_json.useBuiltinCatalog). This is
            // the source of truth for the Catalog provider toggle so it always reflects + persists,
            // even when the built-in catalog addon isn't installed/matched locally yet.
            val useBuiltin = homeCatalogSettingsSyncService.pullUseBuiltinCatalog()
            _uiState.update { it.copy(isCatalogEnabled = useBuiltin) }
            // Bug #1 — mirror locally so MetaRepository can gate built-in meta synchronously.
            deviceProfileDataStore.setBuiltinCatalogEnabled(useBuiltin)
        }
        viewModelScope.launch {
            // Bug #2 — pull the web-set stream-provider selection (nuvio_profile_settings
            // .streamProvider) and reconcile the app-local stream-engine flag so values set on
            // /configure reach the app. Null = pull failed → keep the local value untouched.
            val webStream = homeCatalogSettingsSyncService.pullStreamProviderEnabled()
            if (webStream != null) {
                deviceProfileDataStore.setStreamEngineEnabled(webStream)
                _uiState.update { it.copy(streamEngineEnabled = webStream) }
            }
        }
        viewModelScope.launch {
            // Pull the subtitle-provider toggle (nuvio_profile_settings.useBuiltinSubtitles)
            // written by the mobile app so the TV shows the same on/off state across devices.
            // Null = pull failed → keep the default (true = subtitles on).
            val subtitlesEnabled = homeCatalogSettingsSyncService.pullSubtitleProviderEnabled()
            if (subtitlesEnabled != null) {
                _uiState.update { it.copy(useBuiltinSubtitles = subtitlesEnabled) }
            }
        }
        viewModelScope.launch {
            // Hide/lock the toggle when the super admin has made personalization unavailable.
            featureAvailabilityManager.features.collectLatest { features ->
                val available = features[com.nuvio.tv.core.feature.FeatureKeys.PERSONALIZATION] ?: true
                _uiState.update { it.copy(personalizationAvailable = available) }
            }
        }
    }

    fun toggleRecommendations(enabled: Boolean) {
        // Optimistic UI; persist to the shared blob so the home suppresses/restores reco rows
        // and the web dashboard reflects the same useRecommendations value.
        _uiState.update { it.copy(useRecommendations = enabled) }
        viewModelScope.launch {
            homeCatalogSettingsSyncService.pushUseRecommendations(enabled)
        }
    }

    fun toggleCatalog(enabled: Boolean) {
        // Optimistic UI so the toggle responds immediately even when no local catalog addon
        // matches the reco host and the launch returns early.
        _uiState.update { it.copy(isCatalogEnabled = enabled) }
        viewModelScope.launch {
            // Bug #1 — mirror locally first so the meta gate flips immediately for this profile.
            deviceProfileDataStore.setBuiltinCatalogEnabled(enabled)
            // Persist to the shared blob (settings_json.useBuiltinCatalog) so the home suppresses/
            // restores built-in rows AND the web /configure reflects the same value.
            homeCatalogSettingsSyncService.pushUseBuiltinCatalog(enabled)
            // Also mirror onto the local catalog addon's enabled flag when it's installed, so the
            // catalog repository / addon manager stay consistent with the master switch.
            val addons = addonRepository.getInstalledAddons().first()
            val catalogAddon = addons.firstOrNull {
                it.baseUrl.contains(CATALOG_HOST, ignoreCase = true) ||
                (BuildConfig.CATALOG_ADDON_BASE_URL.isNotBlank() &&
                 it.baseUrl.contains(BuildConfig.CATALOG_ADDON_BASE_URL.trim(), ignoreCase = true))
            }
            if (catalogAddon != null) {
                addonRepository.setAddonEnabled(catalogAddon.baseUrl, enabled)
            }
        }
    }

    fun toggleStreamEngine(enabled: Boolean) {
        // Optimistic UI so the switch responds immediately.
        _uiState.update { it.copy(streamEngineEnabled = enabled) }
        viewModelScope.launch {
            deviceProfileDataStore.setStreamEngineEnabled(enabled)
            // Bug #2 — also mirror to the shared per-profile store the web /configure Stream
            // Providers section reads (nuvio_profile_settings.streamProvider), so app↔web agree.
            homeCatalogSettingsSyncService.pushStreamProviderEnabled(enabled)
        }
    }

    fun toggleSubtitles(enabled: Boolean) {
        // Optimistic UI; persist to nuvio_profile_settings.useBuiltinSubtitles (the same key the
        // mobile app writes) so the subtitle-provider choice is consistent across TV + mobile.
        _uiState.update { it.copy(useBuiltinSubtitles = enabled) }
        viewModelScope.launch {
            homeCatalogSettingsSyncService.pushSubtitleProviderEnabled(enabled)
        }
    }

    fun toggleAutoDetect(enabled: Boolean) {
        viewModelScope.launch {
            deviceProfileDataStore.setUserOverridden(!enabled)
            if (enabled) {
                val snapshot = deviceCapabilityDetector.detect(getDisplay())
                val resolution = when {
                    snapshot.supports4k -> "2160p"
                    snapshot.supports1080p -> "1080p"
                    else -> "720p"
                }
                val hdrTypes = buildSet {
                    if (snapshot.supportsHdr10) add("HDR10")
                    if (snapshot.supportsDolbyVision) add("DolbyVision")
                    if (snapshot.supportsHlg) add("HLG")
                }
                val codecs = buildSet {
                    if (snapshot.supportsHevc) add("H.265")
                    if (snapshot.supportsAv1) add("AV1")
                    add("H.264")
                }
                _uiState.update { state ->
                    state.copy(
                        editMaxResolution = resolution,
                        editHdrTypes = hdrTypes,
                        editCodecs = codecs,
                    )
                }
                saveDeviceProfile()
            }
        }
    }

    fun setProfileResolution(resolution: String) {
        _uiState.update { it.copy(editMaxResolution = resolution) }
        autoSave()
    }

    fun setProfileHdrTypes(types: Set<String>) {
        _uiState.update { it.copy(editHdrTypes = types) }
        autoSave()
    }

    fun setProfileCodecs(codecs: Set<String>) {
        _uiState.update { it.copy(editCodecs = codecs) }
        autoSave()
    }

    fun setProfileAudioFormats(formats: Set<String>) {
        _uiState.update { it.copy(editAudioFormats = formats) }
        autoSave()
    }

    fun setProfileAudioChannels(channels: String) {
        _uiState.update { it.copy(editMaxAudioChannels = channels) }
        autoSave()
    }

    private fun autoSave() {
        if (!_uiState.value.useAutoDetectedProfile) {
            viewModelScope.launch { saveDeviceProfile() }
        }
    }

    private suspend fun saveDeviceProfile() {
        try {
            val deviceId = deviceProfileDataStore.getOrCreateDeviceId()
            val state = _uiState.value
            val dto = DeviceProfileUpdateDto(
                deviceId = deviceId,
                deviceName = android.os.Build.MODEL,
                maxResolution = state.editMaxResolution,
                hdrTypesSupported = state.editHdrTypes.toList(),
                maxAudioChannels = state.editMaxAudioChannels,
                preferredAudioFormats = state.editAudioFormats.toList(),
                supportedCodecs = state.editCodecs.toList(),
                maxSizeGb = 0,
                downloadSpeedMbps = deviceProfileDataStore.estimateDownloadSpeedMbps().toDouble()
            )
            catalogAddonApi.updateDeviceProfile(dto)
        } catch (e: Exception) {
            android.util.Log.w("BuiltInProvidersVM", "Failed to save device profile", e)
        }
    }

    private suspend fun loadDeviceProfile() {
        try {
            val deviceId = deviceProfileDataStore.getOrCreateDeviceId()
            val response = catalogAddonApi.getDeviceProfile(deviceId)
            if (response.isSuccessful) {
                val profile = response.body()
                _uiState.update { state ->
                    state.copy(
                        deviceProfile = profile,
                        editMaxResolution = profile?.maxResolution ?: state.editMaxResolution,
                        editHdrTypes = profile?.hdrTypesSupported?.toSet() ?: state.editHdrTypes,
                        editCodecs = profile?.supportedCodecs?.toSet() ?: state.editCodecs,
                        editAudioFormats = profile?.preferredAudioFormats?.toSet() ?: state.editAudioFormats,
                        editMaxAudioChannels = profile?.maxAudioChannels ?: state.editMaxAudioChannels,
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BuiltInProvidersVM", "Failed to load device profile", e)
        }
    }

    private fun getDisplay(): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }
}
