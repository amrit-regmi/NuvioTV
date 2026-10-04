package com.nuvio.tv.ui.screens.inbox

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.shares.InboxItemDto
import com.nuvio.tv.core.shares.InboxManager
import com.nuvio.tv.core.shares.SharesRepository
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    val items: List<InboxItemDto> = emptyList(),
    val pendingIds: Set<String> = emptySet(),
    val error: String? = null
)

/**
 * Backs both the sidebar badge-count read (via [badgeCount], mirrored straight from the
 * app-wide [InboxManager] singleton) and the full inbox screen (via [uiState]).
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val inboxManager: InboxManager,
    private val sharesRepository: SharesRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    val badgeCount: StateFlow<Int> = inboxManager.badgeCount

    private val _uiState = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            inboxManager.items.collect { items ->
                _uiState.update { it.copy(items = items) }
            }
        }
    }

    /** Called when the inbox screen is opened — forces a fresh fetch beyond the ambient poll. */
    fun refreshNow() {
        inboxManager.refresh()
    }

    /**
     * Content share → Add to Watchlist. [LibraryRepository.toggleDefault] is a TRUE TOGGLE, not
     * add-only (see `LibraryRepositoryImpl.toggleDefault`), so this guards with an
     * [LibraryRepository.isInLibrary] check first and only toggles when the item is NOT already
     * present — toggling an already-present item would incorrectly REMOVE it. If it's already
     * there, this just marks the share handled without re-toggling.
     */
    fun addToWatchlist(item: InboxItemDto) {
        if (item.type != "share") return
        val contentId = item.contentId ?: return
        val contentType = item.contentType ?: return
        if (_uiState.value.pendingIds.contains(item.id)) return

        setPending(item.id, true)
        viewModelScope.launch {
            runCatching {
                val alreadyInLibrary = libraryRepository.isInLibrary(contentId, contentType).first()
                if (!alreadyInLibrary) {
                    libraryRepository.toggleDefault(item.toLibraryEntryInput())
                }
                sharesRepository.respondToShare(item.id, added = true)
            }.onFailure { e ->
                Log.w(TAG, "addToWatchlist failed for share ${item.id}: ${e.message}")
            }
            inboxManager.removeLocally(item.id)
            setPending(item.id, false)
        }
    }

    fun dismissShare(item: InboxItemDto) {
        if (item.type != "share") return
        if (_uiState.value.pendingIds.contains(item.id)) return
        setPending(item.id, true)
        viewModelScope.launch {
            runCatching { sharesRepository.respondToShare(item.id, added = false) }
                .onFailure { e -> Log.w(TAG, "dismissShare failed for ${item.id}: ${e.message}") }
            inboxManager.removeLocally(item.id)
            setPending(item.id, false)
        }
    }

    fun allowPermissionRequest(item: InboxItemDto) {
        respondToPermission(item, allow = true)
    }

    fun denyPermissionRequest(item: InboxItemDto) {
        respondToPermission(item, allow = false)
    }

    private fun respondToPermission(item: InboxItemDto, allow: Boolean) {
        if (item.type != "permission_request") return
        if (_uiState.value.pendingIds.contains(item.id)) return
        setPending(item.id, true)
        viewModelScope.launch {
            runCatching { sharesRepository.respondToPermissionRequest(item.id, allow) }
                .onFailure { e -> Log.w(TAG, "respondToPermissionRequest failed for ${item.id}: ${e.message}") }
            inboxManager.removeLocally(item.id)
            setPending(item.id, false)
        }
    }

    private fun setPending(id: String, pending: Boolean) {
        _uiState.update { current ->
            current.copy(
                pendingIds = if (pending) current.pendingIds + id else current.pendingIds - id
            )
        }
    }

    companion object {
        private const val TAG = "InboxViewModel"
    }
}

/** Maps a "share"-type [InboxItemDto] onto the same shape [LibraryRepository] expects. */
private fun InboxItemDto.toLibraryEntryInput(): LibraryEntryInput {
    val year = Regex("(\\d{4})").find(releaseInfo ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
    return LibraryEntryInput(
        itemId = contentId.orEmpty(),
        itemType = contentType.orEmpty(),
        title = name ?: contentId.orEmpty(),
        year = year,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}
