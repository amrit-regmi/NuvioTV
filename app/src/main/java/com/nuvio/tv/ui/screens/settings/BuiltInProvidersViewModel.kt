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
    val streamEngineEnabled: Boolean = false,
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        // F32: single source of truth — derives from BuildConfig.RECO_API_BASE_URL via RecoBackend.
        private val CATALOG_HOST = RecoBackend.host
    }

    private val _uiState = MutableStateFlow(BuiltInProvidersUiState())
    val uiState: StateFlow<BuiltInProvidersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                deviceProfileDataStore.isStreamEngineEnabled,
                deviceProfileDataStore.isUserOverridden
            ) { addons, streamEngineEnabled, isUserOverridden ->
                val catalogAddon = addons.firstOrNull {
                    it.baseUrl.contains(CATALOG_HOST, ignoreCase = true) ||
                    (BuildConfig.CATALOG_ADDON_BASE_URL.isNotBlank() &&
                     it.baseUrl.contains(BuildConfig.CATALOG_ADDON_BASE_URL.trim(), ignoreCase = true))
                }
                val catalogEnabled = catalogAddon?.enabled ?: true
                Triple(streamEngineEnabled, catalogEnabled, !isUserOverridden)
            }.collectLatest { (streamEngineEnabled, catalogEnabled, useAutoDetect) ->
                _uiState.update {
                    it.copy(
                        isCatalogEnabled = catalogEnabled,
                        streamEngineEnabled = streamEngineEnabled,
                        useAutoDetectedProfile = useAutoDetect
                    )
                }
            }
        }
        viewModelScope.launch {
            loadDeviceProfile()
        }
    }

    fun toggleCatalog(enabled: Boolean) {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first()
            val catalogAddon = addons.firstOrNull {
                it.baseUrl.contains(CATALOG_HOST, ignoreCase = true)
            } ?: return@launch
            addonRepository.setAddonEnabled(catalogAddon.baseUrl, enabled)
        }
    }

    fun toggleStreamEngine(enabled: Boolean) {
        viewModelScope.launch {
            deviceProfileDataStore.setStreamEngineEnabled(enabled)
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
