package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
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
class AnimeSkipSettingsViewModel @Inject constructor(
    private val dataStore: AnimeSkipSettingsDataStore
) : ViewModel() {

    private val _clientId = MutableStateFlow("")
    val clientId: StateFlow<String> = _clientId.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val validationError: SharedFlow<Unit> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.clientId.collectLatest { _clientId.update { _ -> it } }
        }
        viewModelScope.launch {
            dataStore.enabled.collectLatest { _enabled.update { _ -> it } }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(value) }
    }

    fun validateAndSave(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setClientId("") }
            onSuccess()
            return
        }
        // Skip-intro now comes from OUR backend (no api.anime-skip.com validation call).
        // The client id is stored locally for back-compat but is no longer used in the
        // request path. Save without a third-party round-trip.
        viewModelScope.launch {
            dataStore.setClientId(trimmed)
            onSuccess()
        }
    }
}
