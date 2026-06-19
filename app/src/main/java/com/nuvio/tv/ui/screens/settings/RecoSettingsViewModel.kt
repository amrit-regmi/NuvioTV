package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.reco.RecommendationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val KEY_EXPIRY_SECONDS = 900

data class RecoSettingsUiState(
    val isLoading: Boolean = false,
    val url: String? = null,
    val qrBitmap: Bitmap? = null,
    val countdownSeconds: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class RecoSettingsViewModel @Inject constructor(
    private val recommendationRepository: RecommendationRepository,
    private val authManager: AuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoSettingsUiState())
    val uiState: StateFlow<RecoSettingsUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    fun issueWatchlyKey() {
        val token = authManager.currentAccessToken() ?: return
        viewModelScope.launch {
            _uiState.value = RecoSettingsUiState(isLoading = true)
            val url = recommendationRepository.issueWatchlyKey(token)
            if (url == null) {
                _uiState.value = RecoSettingsUiState(error = "Failed to generate key. Check your connection.")
                return@launch
            }
            val qrBitmap = runCatching { QrCodeGenerator.generate(url, 420) }.getOrNull()
            _uiState.value = RecoSettingsUiState(url = url, qrBitmap = qrBitmap, countdownSeconds = KEY_EXPIRY_SECONDS)
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = KEY_EXPIRY_SECONDS
            while (remaining > 0) {
                delay(1_000L)
                remaining--
                _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
            }
            _uiState.value = RecoSettingsUiState()
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
