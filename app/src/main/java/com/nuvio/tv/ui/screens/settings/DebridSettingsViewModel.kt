package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridDeviceAuthorization
import com.nuvio.tv.core.debrid.DebridDeviceAuthorizationTokenResult
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DebridFormatterConfigServer
import com.nuvio.tv.core.server.DebridFormatterSettings
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.DeviceProfileDataStore
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.data.remote.api.DeviceProfileDto
import com.nuvio.tv.data.remote.api.DeviceProfileUpdateDto
import com.nuvio.tv.data.remote.dto.PremiumizeDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.domain.model.DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamSortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebridSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi,
    private val deviceProfileDataStore: DeviceProfileDataStore,
    private val catalogAddonApi: CatalogAddonApi
) : ViewModel() {
    private var formatterServer: DebridFormatterConfigServer? = null
    private var logoBytes: ByteArray? = null

    private val _uiState = MutableStateFlow(DebridSettingsUiState())
    val uiState: StateFlow<DebridSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val validationError: SharedFlow<String> = _validationError.asSharedFlow()

    private val _profileSaveResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val profileSaveResult: SharedFlow<Boolean> = _profileSaveResult.asSharedFlow()

    init {
        loadLogoBytes()
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
        viewModelScope.launch {
            deviceProfileDataStore.isStreamEngineEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(streamEngineEnabled = enabled) }
                if (enabled) {
                    try {
                        val deviceId = deviceProfileDataStore.getOrCreateDeviceId()
                        val response = catalogAddonApi.getDeviceProfile(deviceId)
                        if (response.isSuccessful) {
                            val profile = response.body()
                            _uiState.update { it.copy(deviceProfile = profile).withDeviceProfile(profile) }
                        } else {
                            // Backend has no profile yet — pre-fill with best-known detected defaults
                            // so the user can review and tap Save to register.
                            _uiState.update { it.withDetectedDefaults() }
                        }
                    } catch (_: Exception) {
                        _uiState.update { it.withDetectedDefaults() }
                    }
                }
            }
        }
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    fun onEvent(event: DebridSettingsEvent) {
        when (event) {
            is DebridSettingsEvent.ToggleEnabled -> {
                if (event.enabled && !_uiState.value.hasResolverProvider) return
                update { dataStore.setEnabled(event.enabled) }
            }
            is DebridSettingsEvent.SetProfileResolution -> {
                _uiState.update { it.copy(editMaxResolution = event.resolution) }
            }
            is DebridSettingsEvent.ToggleProfileHdrType -> {
                _uiState.update { state ->
                    val updated = if (event.type in state.editHdrTypes) {
                        state.editHdrTypes - event.type
                    } else {
                        state.editHdrTypes + event.type
                    }
                    state.copy(editHdrTypes = updated)
                }
            }
            is DebridSettingsEvent.SetProfileHdrTypes -> {
                _uiState.update { it.copy(editHdrTypes = event.types) }
            }
            is DebridSettingsEvent.ToggleProfileCodec -> {
                _uiState.update { state ->
                    val updated = if (event.codec in state.editCodecs) {
                        state.editCodecs - event.codec
                    } else {
                        state.editCodecs + event.codec
                    }
                    state.copy(editCodecs = updated)
                }
            }
            is DebridSettingsEvent.SetProfileCodecs -> {
                _uiState.update { it.copy(editCodecs = event.codecs) }
            }
            is DebridSettingsEvent.ToggleProfileAudioFormat -> {
                _uiState.update { state ->
                    val updated = if (event.format in state.editAudioFormats) {
                        state.editAudioFormats - event.format
                    } else {
                        state.editAudioFormats + event.format
                    }
                    state.copy(editAudioFormats = updated)
                }
            }
            is DebridSettingsEvent.SetProfileAudioFormats -> {
                _uiState.update { it.copy(editAudioFormats = event.formats) }
            }
            is DebridSettingsEvent.SetProfileAudioChannels -> {
                _uiState.update { it.copy(editMaxAudioChannels = event.channels) }
            }
            is DebridSettingsEvent.ToggleStreamEngine -> {
                viewModelScope.launch {
                    deviceProfileDataStore.setStreamEngineEnabled(event.enabled)
                }
            }
            is DebridSettingsEvent.SaveDeviceProfile -> {
                viewModelScope.launch {
                    try {
                        val deviceId = deviceProfileDataStore.getOrCreateDeviceId()
                        val state = _uiState.value
                        val body = DeviceProfileUpdateDto(
                            deviceId = deviceId,
                            deviceName = android.os.Build.MODEL,
                            maxResolution = state.editMaxResolution,
                            hdrTypesSupported = state.editHdrTypes.toList(),
                            supportedCodecs = state.editCodecs.toList(),
                            preferredAudioFormats = state.editAudioFormats.toList(),
                            maxAudioChannels = state.editMaxAudioChannels,
                            downloadSpeedMbps = state.deviceProfile?.downloadSpeedMbps ?: 0.0,
                            maxSizeGb = 0
                        )
                        val response = catalogAddonApi.updateDeviceProfile(body)
                        if (response.isSuccessful) {
                            // Refresh profile from backend
                            val refreshed = catalogAddonApi.getDeviceProfile(deviceId)
                            if (refreshed.isSuccessful) {
                                val profile = refreshed.body()
                                _uiState.update { it.copy(deviceProfile = profile).withDeviceProfile(profile) }
                            }
                            _profileSaveResult.tryEmit(true)
                        } else {
                            _profileSaveResult.tryEmit(false)
                        }
                    } catch (_: Exception) {
                        _profileSaveResult.tryEmit(false)
                    }
                }
            }
        }
    }

    fun setCloudLibraryEnabled(enabled: Boolean) {
        update { dataStore.setCloudLibraryEnabled(enabled) }
    }

    fun setPreferredResolverProviderId(providerId: String) {
        update { dataStore.setPreferredResolverProviderId(providerId) }
    }

    fun startFormatterQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        stopFormatterServer()
        formatterServer = DebridFormatterConfigServer.startOnAvailablePort(
            currentSettingsProvider = {
                val state = _uiState.value
                DebridFormatterSettings(
                    nameTemplate = state.streamNameTemplate,
                    descriptionTemplate = state.streamDescriptionTemplate,
                    streamPreferences = state.streamPreferences
                )
            },
            onSettingsChanged = { settings ->
                _uiState.update { it.copy(
                    streamNameTemplate = settings.nameTemplate,
                    streamDescriptionTemplate = settings.descriptionTemplate,
                    streamPreferences = settings.streamPreferences
                ) }
                viewModelScope.launch {
                    dataStore.setStreamTemplates(
                        nameTemplate = settings.nameTemplate,
                        descriptionTemplate = settings.descriptionTemplate
                    )
                    dataStore.setStreamPreferences(settings.streamPreferences)
                }
            },
            context = context,
            logoProvider = { logoBytes }
        )
        val server = formatterServer
        if (server == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        val url = "http://$ip:${server.listeningPort}"
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = true,
                formatterQrCodeBitmap = QrCodeGenerator.generate(url, 512),
                formatterServerUrl = url,
                serverError = null
            )
        }
    }

    fun stopFormatterQrMode() {
        stopFormatterServer()
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = false,
                formatterQrCodeBitmap = null,
                formatterServerUrl = null
            )
        }
    }

    fun resetFormatterTemplates() {
        update { dataStore.resetStreamTemplates() }
    }

    fun setInstantPlaybackPreparationEnabled(enabled: Boolean) {
        val nextLimit = if (enabled) {
            DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT
        } else {
            0
        }
        update { dataStore.setInstantPlaybackPreparationLimit(nextLimit) }
    }

    fun setInstantPlaybackPreparationLimit(limit: Int) {
        update { dataStore.setInstantPlaybackPreparationLimit(limit) }
    }

    fun setStreamMaxResults(maxResults: Int) {
        update { dataStore.setStreamMaxResults(maxResults) }
    }

    fun setStreamSortMode(mode: DebridStreamSortMode) {
        update { dataStore.setStreamSortMode(mode) }
    }

    fun setStreamMinimumQuality(quality: DebridStreamMinimumQuality) {
        update { dataStore.setStreamMinimumQuality(quality) }
    }

    fun setStreamDolbyVisionFilter(filter: DebridStreamFeatureFilter) {
        update { dataStore.setStreamDolbyVisionFilter(filter) }
    }

    fun setStreamHdrFilter(filter: DebridStreamFeatureFilter) {
        update { dataStore.setStreamHdrFilter(filter) }
    }

    fun setStreamCodecFilter(filter: DebridStreamCodecFilter) {
        update { dataStore.setStreamCodecFilter(filter) }
    }

    fun setStreamPreferences(preferences: DebridStreamPreferences) {
        update { dataStore.setStreamPreferences(preferences) }
    }

    fun saveProviderCredential(providerId: String, value: String) {
        update { dataStore.setProviderApiKey(providerId, value.trim()) }
    }

    fun validateAndSaveTorboxApiKey(value: String, onSuccess: () -> Unit) {
        validateAndSaveProviderApiKey(DebridProviders.TORBOX_ID, value, onSuccess)
    }

    fun validateAndSaveProviderApiKey(providerId: String, value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setProviderApiKey(providerId, "") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = validateProviderApiKey(providerId, trimmed)
            _validating.value = false
            if (valid) {
                dataStore.setProviderApiKey(providerId, trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(context.getString(R.string.debrid_key_invalid))
            }
        }
    }

    private suspend fun validateProviderApiKey(providerId: String, apiKey: String): Boolean {
        return try {
            when (DebridProviders.byId(providerId)?.id) {
                DebridProviders.TORBOX_ID -> {
                    val response = torboxApi.getUser("Bearer $apiKey")
                    response.body()?.close()
                    response.errorBody()?.close()
                    response.isSuccessful
                }
                DebridProviders.PREMIUMIZE_ID -> {
                    val response = premiumizeApi.accountInfo("Bearer $apiKey")
                    response.errorBody()?.close()
                    response.isSuccessful && !response.body()?.status.equals("error", ignoreCase = true)
                }
                else -> false
            }
        } catch (error: Exception) {
            false
        }
    }

    suspend fun startDeviceAuthorization(providerId: String): DebridDeviceAuthorization? {
        return when (DebridProviders.byId(providerId)?.id) {
            DebridProviders.TORBOX_ID -> {
                val response = torboxApi.startDeviceAuthorization("Nuvio")
                val data = response.body()?.takeIf { response.isSuccessful && it.success != false }?.data
                    ?: return null
                val deviceCode = data.deviceCode?.takeIf { it.isNotBlank() } ?: return null
                val userCode = data.code?.takeIf { it.isNotBlank() } ?: return null
                val verificationUrl = data.verificationUrl?.takeIf { it.isNotBlank() } ?: return null
                DebridDeviceAuthorization(
                    providerId = DebridProviders.TORBOX_ID,
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = verificationUrl,
                    friendlyVerificationUrl = data.friendlyVerificationUrl?.takeIf { it.isNotBlank() } ?: verificationUrl,
                    intervalSeconds = data.interval?.coerceAtLeast(1) ?: 5,
                    expiresAt = data.expiresAt?.takeIf { it.isNotBlank() }
                )
            }
            DebridProviders.PREMIUMIZE_ID -> {
                val clientId = premiumizeClientIdOrThrow()
                val response = premiumizeApi.startDeviceAuthorization(clientId = clientId)
                val data = response.body()?.takeIf { response.isSuccessful } ?: return null
                val deviceCode = data.deviceCode?.takeIf { it.isNotBlank() } ?: return null
                val userCode = data.userCode?.takeIf { it.isNotBlank() } ?: return null
                val verificationUrl = data.verificationUri?.takeIf { it.isNotBlank() } ?: return null
                DebridDeviceAuthorization(
                    providerId = DebridProviders.PREMIUMIZE_ID,
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = verificationUrl,
                    friendlyVerificationUrl = data.verificationUriComplete?.takeIf { it.isNotBlank() } ?: verificationUrl,
                    intervalSeconds = data.interval?.coerceAtLeast(1) ?: 5,
                    expiresAt = data.expiresIn?.takeIf { it > 0 }?.let { "${it}s" }
                )
            }
            else -> null
        }
    }

    suspend fun redeemDeviceAuthorization(providerId: String, deviceCode: String): DebridDeviceAuthorizationTokenResult {
        val normalized = deviceCode.trim()
        if (normalized.isBlank()) return DebridDeviceAuthorizationTokenResult.Failed(null)
        return when (DebridProviders.byId(providerId)?.id) {
            DebridProviders.TORBOX_ID -> {
                val response = torboxApi.redeemDeviceAuthorization(TorboxDeviceTokenRequestDto(normalized))
                torboxDeviceAuthorizationTokenResult(response)
            }
            DebridProviders.PREMIUMIZE_ID -> {
                val clientId = premiumizeClientIdOrThrow()
                val response = premiumizeApi.redeemDeviceAuthorization(
                    deviceCode = normalized,
                    clientId = clientId
                )
                premiumizeDeviceAuthorizationTokenResult(response)
            }
            else -> DebridDeviceAuthorizationTokenResult.Unsupported
        }
    }

    private fun premiumizeClientIdOrThrow(): String =
        BuildConfig.PREMIUMIZE_CLIENT_ID.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Premiumize sign-in is missing PREMIUMIZE_CLIENT_ID.")

    private fun torboxDeviceAuthorizationTokenResult(
        response: retrofit2.Response<TorboxEnvelopeDto<TorboxDeviceTokenDto>>
    ): DebridDeviceAuthorizationTokenResult {
        val envelope = response.body()
        val accessToken = envelope
            ?.takeIf { response.isSuccessful && it.success != false }
            ?.data
            ?.accessToken
            ?.takeIf { it.isNotBlank() }
        if (accessToken != null) {
            return DebridDeviceAuthorizationTokenResult.Authorized(accessToken)
        }
        val message = listOfNotNull(envelope?.error, envelope?.detail, response.errorBody()?.string())
            .joinToString(" ")
            .lowercase()
        return when {
            message.contains("pending") ||
                message.contains("not authorized") ||
                message.contains("not been used") ||
                message.contains("not used yet") ||
                message.contains("scan the code") ->
                DebridDeviceAuthorizationTokenResult.Pending
            message.contains("expired") ->
                DebridDeviceAuthorizationTokenResult.Expired
            response.code() == 404 || response.code() == 409 || response.code() == 425 ->
                DebridDeviceAuthorizationTokenResult.Pending
            response.code() == 410 ->
                DebridDeviceAuthorizationTokenResult.Expired
            else ->
                DebridDeviceAuthorizationTokenResult.Failed(envelope?.detail ?: envelope?.error)
        }
    }

    private fun premiumizeDeviceAuthorizationTokenResult(
        response: retrofit2.Response<PremiumizeDeviceTokenDto>
    ): DebridDeviceAuthorizationTokenResult {
        val body = response.body()
        body?.accessToken?.takeIf { response.isSuccessful && it.isNotBlank() }?.let { accessToken ->
            return DebridDeviceAuthorizationTokenResult.Authorized(accessToken)
        }
        return when (body?.error?.lowercase()) {
            "authorization_pending", "slow_down" -> DebridDeviceAuthorizationTokenResult.Pending
            "invalid_grant", "expired_token" -> DebridDeviceAuthorizationTokenResult.Expired
            "access_denied" -> DebridDeviceAuthorizationTokenResult.Failed(body.errorDescription)
            else -> {
                if (response.code() == 400 && body?.error.isNullOrBlank()) {
                    DebridDeviceAuthorizationTokenResult.Pending
                } else {
                    DebridDeviceAuthorizationTokenResult.Failed(body?.errorDescription ?: body?.error ?: response.errorBody()?.string())
                }
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun stopFormatterServer() {
        formatterServer?.stop()
        formatterServer = null
    }

    override fun onCleared() {
        stopFormatterServer()
        super.onCleared()
    }
}

data class DebridSettingsUiState(
    val enabled: Boolean = false,
    val cloudLibraryEnabled: Boolean = true,
    val torboxApiKey: String = "",
    val premiumizeApiKey: String = "",
    val realDebridApiKey: String = "",
    val preferredResolverProviderId: String = "",
    val instantPlaybackPreparationLimit: Int = 0,
    val streamMaxResults: Int = 0,
    val streamSortMode: DebridStreamSortMode = DebridStreamSortMode.DEFAULT,
    val streamMinimumQuality: DebridStreamMinimumQuality = DebridStreamMinimumQuality.ANY,
    val streamDolbyVisionFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamHdrFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamCodecFilter: DebridStreamCodecFilter = DebridStreamCodecFilter.ANY,
    val streamPreferences: DebridStreamPreferences = DebridStreamPreferences(),
    val streamNameTemplate: String = "",
    val streamDescriptionTemplate: String = "",
    val isFormatterQrModeActive: Boolean = false,
    val formatterQrCodeBitmap: Bitmap? = null,
    val formatterServerUrl: String? = null,
    val serverError: String? = null,
    val streamEngineEnabled: Boolean = false,
    val deviceProfile: DeviceProfileDto? = null,
    val editMaxResolution: String = "2160p",
    val editHdrTypes: Set<String> = emptySet(),
    val editCodecs: Set<String> = emptySet(),
    val editAudioFormats: Set<String> = emptySet(),
    val editMaxAudioChannels: String = "7.1"
) {
    fun withDeviceProfile(profile: DeviceProfileDto?): DebridSettingsUiState {
        if (profile == null) return this
        return copy(
            editMaxResolution = profile.maxResolution ?: editMaxResolution,
            editHdrTypes = profile.hdrTypesSupported?.toSet() ?: editHdrTypes,
            editCodecs = profile.supportedCodecs?.toSet() ?: editCodecs,
            editAudioFormats = profile.preferredAudioFormats?.toSet() ?: editAudioFormats,
            editMaxAudioChannels = profile.maxAudioChannels ?: editMaxAudioChannels
        )
    }

    // Pre-fill with best-guess detected defaults when no backend profile exists yet.
    // Assumes a modern 4K Android TV — the user can correct and Save.
    fun withDetectedDefaults(): DebridSettingsUiState = copy(
        editMaxResolution = "2160p",
        editHdrTypes = setOf("HDR10", "DolbyVision", "HLG"),
        editCodecs = setOf("H.265", "AV1", "H.264"),
        editAudioFormats = setOf("Dolby Atmos", "DTS:X", "AAC"),
        editMaxAudioChannels = "7.1"
    )

    val providerApiKeys: Map<String, String>
        get() = mapOf(
            DebridProviders.TORBOX_ID to torboxApiKey,
            DebridProviders.PREMIUMIZE_ID to premiumizeApiKey,
            DebridProviders.REAL_DEBRID_ID to realDebridApiKey
        )

    val hasAnyApiKey: Boolean
        get() = DebridProviders.visible().any { provider -> apiKeyFor(provider.id).isNotBlank() }

    val resolverProviders: List<com.nuvio.tv.core.debrid.DebridProvider>
        get() = DebridProviders.visible()
            .filter { provider ->
                apiKeyFor(provider.id).isNotBlank() &&
                    (provider.supports(DebridProviderCapability.ClientResolve) ||
                        provider.supports(DebridProviderCapability.LocalTorrentResolve))
            }

    val activeResolverProvider: com.nuvio.tv.core.debrid.DebridProvider?
        get() = resolverProviders.firstOrNull { it.id == preferredResolverProviderId }
            ?: resolverProviders.firstOrNull()

    val hasResolverProvider: Boolean
        get() = activeResolverProvider != null

    val cloudLibraryProviders: List<com.nuvio.tv.core.debrid.DebridProvider>
        get() = DebridProviders.visible()
            .filter { provider ->
                apiKeyFor(provider.id).isNotBlank() &&
                    provider.supports(DebridProviderCapability.CloudLibrary)
            }

    val hasCloudLibraryProvider: Boolean
        get() = cloudLibraryProviders.isNotEmpty()

    val canResolvePlayableLinks: Boolean
        get() = enabled && hasResolverProvider

    val canUseCloudLibrary: Boolean
        get() = cloudLibraryEnabled && hasCloudLibraryProvider

    fun apiKeyFor(providerId: String): String =
        providerApiKeys[providerId].orEmpty()

    fun fromSettings(settings: DebridSettings): DebridSettingsUiState = copy(
        enabled = settings.enabled,
        cloudLibraryEnabled = settings.cloudLibraryEnabled,
        torboxApiKey = settings.torboxApiKey,
        premiumizeApiKey = settings.premiumizeApiKey,
        realDebridApiKey = settings.realDebridApiKey,
        preferredResolverProviderId = settings.preferredResolverProviderId,
        instantPlaybackPreparationLimit = settings.instantPlaybackPreparationLimit,
        streamMaxResults = settings.streamMaxResults,
        streamSortMode = settings.streamSortMode,
        streamMinimumQuality = settings.streamMinimumQuality,
        streamDolbyVisionFilter = settings.streamDolbyVisionFilter,
        streamHdrFilter = settings.streamHdrFilter,
        streamCodecFilter = settings.streamCodecFilter,
        streamPreferences = settings.streamPreferences,
        streamNameTemplate = settings.streamNameTemplate,
        streamDescriptionTemplate = settings.streamDescriptionTemplate
    )
}

sealed class DebridSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : DebridSettingsEvent()
    data class SetProfileResolution(val resolution: String) : DebridSettingsEvent()
    data class ToggleProfileHdrType(val type: String) : DebridSettingsEvent()
    data class SetProfileHdrTypes(val types: Set<String>) : DebridSettingsEvent()
    data class ToggleProfileCodec(val codec: String) : DebridSettingsEvent()
    data class SetProfileCodecs(val codecs: Set<String>) : DebridSettingsEvent()
    data class ToggleProfileAudioFormat(val format: String) : DebridSettingsEvent()
    data class SetProfileAudioFormats(val formats: Set<String>) : DebridSettingsEvent()
    data class SetProfileAudioChannels(val channels: String) : DebridSettingsEvent()
    data object SaveDeviceProfile : DebridSettingsEvent()
    data class ToggleStreamEngine(val enabled: Boolean) : DebridSettingsEvent()
}
