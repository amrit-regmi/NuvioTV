package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.BackendBaseUrlDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackendUrlUiState(
    /** The persisted override (empty = using the built-in default). */
    val overrideUrl: String = "",
    /** The built-in default URL (placeholder + reset target). */
    val defaultUrl: String = "",
    /** True once a change has been saved this session and a re-login is required to apply it. */
    val changePendingRelogin: Boolean = false,
    /** Non-null = a validation error to surface to the user. */
    val error: String? = null,
)

/**
 * Backs the Advanced > "Backend URL" setting. Persists a device-wide override of the reco /
 * taste-engine backend base URL (default = the built-in hamrocinema URL). The override only takes
 * effect on the next app launch (RecoBackend.init re-reads it), so saving a change marks the
 * session as needing a log out + log back in, which the UI prompts for and can trigger.
 */
@HiltViewModel
class BackendUrlViewModel @Inject constructor(
    private val backendBaseUrlDataStore: BackendBaseUrlDataStore,
    private val authManager: AuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BackendUrlUiState(defaultUrl = backendBaseUrlDataStore.defaultBaseUrl)
    )
    val uiState: StateFlow<BackendUrlUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            backendBaseUrlDataStore.overrideUrl.collect { override ->
                _uiState.update { it.copy(overrideUrl = override) }
            }
        }
    }

    /** The URL currently shown in the row (override if set, else the built-in default). */
    fun effectiveDisplayUrl(): String =
        _uiState.value.overrideUrl.ifBlank { _uiState.value.defaultUrl }

    /**
     * Validates + persists [raw]. Blank reverts to the built-in default. Returns true on success
     * (caller closes the dialog), false on validation failure (error surfaced in state).
     */
    fun save(raw: String, onSaved: () -> Unit) {
        val trimmed = raw.trim()
        // Blank → clear the override (use the built-in default).
        if (trimmed.isBlank()) {
            persist("", onSaved)
            return
        }
        val normalized = BackendBaseUrlDataStore.normalizeOrNull(trimmed)
        if (normalized == null) {
            _uiState.update { it.copy(error = "Enter a valid https:// URL (e.g. https://example.com)") }
            return
        }
        // No-op if unchanged from the current effective value.
        val current = _uiState.value.overrideUrl.ifBlank { _uiState.value.defaultUrl }
        if (normalized == current) {
            _uiState.update { it.copy(error = null) }
            onSaved()
            return
        }
        // Store empty (clear) when the user typed exactly the built-in default.
        val toStore = if (normalized == _uiState.value.defaultUrl.trimEnd('/')) "" else normalized
        persist(toStore, onSaved)
    }

    private fun persist(value: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            backendBaseUrlDataStore.setOverrideUrl(value)
            _uiState.update { it.copy(error = null, changePendingRelogin = true) }
            onSaved()
        }
    }

    fun resetToDefault(onSaved: () -> Unit) = persist("", onSaved)

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Trigger the logout so the new backend takes effect on the next session. */
    fun signOutToApply() {
        viewModelScope.launch {
            authManager.signOut()
        }
    }
}
