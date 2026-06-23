package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val libraryPreferences: LibraryPreferences,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val librarySyncService: LibrarySyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository,
) : LibraryRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydratedLogoIds = mutableSetOf<String>()
    private var syncJob: Job? = null
    private val _isSyncingFromRemote = MutableStateFlow(false)
    var isSyncingFromRemote: Boolean
        get() = _isSyncingFromRemote.value
        set(value) { _isSyncingFromRemote.value = value }
    var hasCompletedInitialPull = false

    private fun triggerRemoteSync() {
        // Skip if already syncing from remote, initial pull not complete, or not authenticated
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            librarySyncService.pushToRemote()
        }
    }

    // Trakt integration removed; library source is always local (Nuvio sync).
    override val sourceMode: Flow<LibrarySourceMode> = flowOf(LibrarySourceMode.LOCAL)
        .distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = _isSyncingFromRemote

    override val libraryItems: Flow<List<LibraryEntry>> =
        libraryPreferences.libraryItems.map { items ->
            items.map { saved ->
                LibraryEntry(
                    id = saved.id,
                    type = saved.type,
                    name = saved.name,
                    // Re-home any poster/background/logo persisted against an old reco
                    // host (e.g. pre-cutover recoengine.regmig.com, now 410 Gone) onto the
                    // live RecoBackend host so the authed Coil loader can fetch it. Stored
                    // entries go stale on host changes; fresh catalog/CW images don't.
                    poster = com.nuvio.tv.core.reco.RecoBackend.rehomeImageUrl(saved.poster),
                    posterShape = saved.posterShape,
                    background = com.nuvio.tv.core.reco.RecoBackend.rehomeImageUrl(saved.background),
                    logo = com.nuvio.tv.core.reco.RecoBackend.rehomeImageUrl(saved.logo),
                    description = saved.description,
                    releaseInfo = saved.releaseInfo,
                    imdbRating = saved.imdbRating,
                    genres = saved.genres,
                    addonBaseUrl = saved.addonBaseUrl,
                    listedAt = saved.addedAt
                )
            }
        }
        .distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> = flowOf(emptyList<LibraryListTab>())
        .distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            .distinctUntilChanged()
    }

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> {
        return libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            .distinctUntilChanged()
    }

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        // Save to local Nuvio library (syncs to Supabase)
        val isInLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        if (isInLocal) {
            libraryPreferences.removeItem(itemId = item.itemId, itemType = item.itemType)
        } else {
            libraryPreferences.addItem(item.toSavedLibraryItem())
        }
        triggerRemoteSync()
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val inLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        return ListMembershipSnapshot(listMembership = mapOf(LOCAL_LIST_KEY to inLocal))
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {
        val desired = changes.desiredMembership

        // Handle local (Nuvio) library - syncs to Supabase
        val localDesired = desired[LOCAL_LIST_KEY] == true
        val currentlyInLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        if (localDesired != currentlyInLocal) {
            if (localDesired) {
                libraryPreferences.addItem(item.toSavedLibraryItem())
            } else {
                libraryPreferences.removeItem(itemId = item.itemId, itemType = item.itemType)
            }
            triggerRemoteSync()
        }
    }

    // Personal-list management was Trakt-only and has been removed; these are no-ops.
    override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) {}

    override suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {}

    override suspend fun deletePersonalList(listId: String) {}

    override suspend fun reorderPersonalLists(orderedListIds: List<String>) {}

    override suspend fun refreshNow() {}

    private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem {
        return SavedLibraryItem(
            id = itemId,
            type = itemType,
            name = title,
            poster = poster,
            posterShape = posterShape,
            background = background,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl,
            logo = logo
        )
    }

    private suspend fun hydrateLibraryLogos(items: List<LibraryEntry>) {
        items.take(20).forEach { entry ->
            hydratedLogoIds.add(entry.id)
            runCatching {
                val result = metaRepository.getMetaFromPrimaryAddon(entry.type, entry.id)
                    .firstOrNull { it is NetworkResult.Success }
                val logo = (result as? NetworkResult.Success)?.data?.logo
                if (logo != null) {
                    libraryPreferences.updateLogo(entry.id, entry.type, logo)
                }
            }.onFailure { Log.w("LibraryRepo", "Logo hydration failed for ${entry.id}", it) }
        }
    }

    companion object {
        private const val LOCAL_LIST_KEY = "local"
    }
}
