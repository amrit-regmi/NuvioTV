package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.shares.MyPermissionDto
import com.nuvio.tv.core.shares.RosterEntryDto
import com.nuvio.tv.core.shares.SharesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiveRecommendationsUiState(
    val isLoading: Boolean = true,
    val mine: List<MyPermissionDto> = emptyList(),
    val showRosterPicker: Boolean = false,
    val isRosterLoading: Boolean = false,
    val roster: List<RosterEntryDto> = emptyList(),
    val requestedIds: Set<String> = emptySet(),
)

@HiltViewModel
class ReceiveRecommendationsViewModel @Inject constructor(
    private val sharesRepository: SharesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiveRecommendationsUiState())
    val uiState: StateFlow<ReceiveRecommendationsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val mine = sharesRepository.getMyPermissions()
            _uiState.update { it.copy(isLoading = false, mine = mine) }
        }
    }

    fun openRosterPicker() {
        _uiState.update { it.copy(showRosterPicker = true, isRosterLoading = true, roster = emptyList()) }
        viewModelScope.launch {
            val roster = sharesRepository.getRoster()
            _uiState.update { it.copy(isRosterLoading = false, roster = roster) }
        }
    }

    fun dismissRosterPicker() {
        _uiState.update { it.copy(showRosterPicker = false) }
    }

    fun requestPermission(sourceId: String) {
        if (sourceId in _uiState.value.requestedIds) return
        _uiState.update { it.copy(requestedIds = it.requestedIds + sourceId) }
        viewModelScope.launch {
            if (sharesRepository.requestPermission(sourceId)) {
                refresh()
            }
        }
    }

    fun updateAlias(id: String, alias: String) {
        viewModelScope.launch {
            if (sharesRepository.updatePermissionAlias(id, alias)) {
                _uiState.update { state ->
                    state.copy(mine = state.mine.map { if (it.id == id) it.copy(alias = alias) else it })
                }
            }
        }
    }
}
