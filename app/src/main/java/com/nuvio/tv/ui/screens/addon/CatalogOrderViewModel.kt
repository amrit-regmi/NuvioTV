package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.HomeRowOrderEntry
import com.nuvio.tv.core.sync.canonicalAddonUrl
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeLegacyDisabledCatalogKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.RecoRowDescriptor
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()

    /**
     * Unified saved home row order pulled from Supabase (settings_json.rowOrder) — the SAME
     * single source of truth the home screen renders from. When present, this UI both reflects
     * its enabled/order AND writes changes back to it, so a toggle/reorder here matches the home
     * exactly and persists. Null = no saved config (fall back to local items model).
     */
    @Volatile
    private var savedRowOrder: List<HomeRowOrderEntry>? = null
    /** Refresh trigger so the catalog flow re-emits after rowOrder is (re)loaded. */
    private val rowOrderRefresh = MutableStateFlow(0)
    /** Snapshot of installed addons for resolving rowOrder entries -> item keys when pushing. */
    @Volatile
    private var addonsSnapshot: List<Addon> = emptyList()

    init {
        loadSavedRowOrder()
        observeCatalogs()
    }

    private fun loadSavedRowOrder() {
        viewModelScope.launch {
            savedRowOrder = runCatching { homeCatalogSettingsSyncService.pullRowOrderFromRemote() }
                .getOrNull()
                ?.rowOrder
                ?.takeIf { it.isNotEmpty() }
            rowOrderRefresh.update { it + 1 }
        }
    }

    fun moveUp(key: String) {
        if (_uiState.value.followAddonsOrder && !key.startsWith("reco_engine_")) {
            moveCollectionBetweenAddons(key, -1)
        } else {
            moveCatalog(key, -1)
        }
    }

    fun moveDown(key: String) {
        if (_uiState.value.followAddonsOrder && !key.startsWith("reco_engine_")) {
            moveCollectionBetweenAddons(key, 1)
        } else {
            moveCatalog(key, 1)
        }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
        }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        // Optimistically reflect the toggle in the unified rowOrder so the push below carries the
        // new enabled state (the combine flow updates _uiState.items asynchronously).
        val nowDisabled = disableKey in updatedDisabled
        val optimisticItems = _uiState.value.items.map { item ->
            if (item.disableKey == disableKey) item.copy(isDisabled = nowDisabled) else item
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
            // Push BOTH models: the legacy items blob (triggerPush) and the unified rowOrder the
            // home actually renders from, so the change is reflected and persists.
            homeCatalogSettingsSyncService.triggerPush()
            pushRowOrderFor(optimisticItems)
        }
    }

    /** Builds + pushes the unified rowOrder for [items], updating the in-memory snapshot too. */
    private suspend fun pushRowOrderFor(items: List<CatalogOrderItem>) {
        if (items.isEmpty()) return
        val rowOrder = buildRowOrderForPush(items)
        if (rowOrder.isEmpty()) return
        savedRowOrder = rowOrder
        homeCatalogSettingsSyncService.pushRowOrderToRemote(rowOrder)
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }
        val reorderedItems = reorderItemsByKeys(reordered)

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
            pushRowOrderFor(reorderedItems)
        }
    }

    /** Reorders the current UI items to match [orderedKeys] (for optimistic rowOrder push). */
    private fun reorderItemsByKeys(orderedKeys: List<String>): List<CatalogOrderItem> {
        val byKey = _uiState.value.items.associateBy { it.key }
        return orderedKeys.mapNotNull { byKey[it] }
    }

    /**
     * When followAddonsOrder is enabled, collections jump between addon boundaries.
     * Moving up means jumping above the previous addon block (all catalogs from one addon).
     * Moving down means jumping below the next addon block.
     */
    private fun moveCollectionBetweenAddons(key: String, direction: Int) {
        if (!key.startsWith("collection_")) return

        val items = _uiState.value.items
        val currentIndex = items.indexOfFirst { it.key == key }
        if (currentIndex == -1) return

        val currentKeys = items.map { it.key }

        val newIndex: Int
        if (direction < 0) {
            // Moving up: find the start of the previous addon block
            // Skip any adjacent collections above
            var scanIdx = currentIndex - 1
            while (scanIdx >= 0 && currentKeys[scanIdx].startsWith("collection_")) {
                scanIdx--
            }
            if (scanIdx < 0) return // already at top

            // scanIdx is now pointing at an addon catalog. Find the start of its addon block.
            val targetAddonName = items[scanIdx].addonName
            while (scanIdx > 0 && !currentKeys[scanIdx - 1].startsWith("collection_") &&
                items[scanIdx - 1].addonName == targetAddonName) {
                scanIdx--
            }
            newIndex = scanIdx
        } else {
            // Moving down: find the end of the next addon block
            var scanIdx = currentIndex + 1
            while (scanIdx < currentKeys.size && currentKeys[scanIdx].startsWith("collection_")) {
                scanIdx++
            }
            if (scanIdx >= currentKeys.size) return // already at bottom

            // scanIdx is now pointing at an addon catalog. Find the end of its addon block.
            val targetAddonName = items[scanIdx].addonName
            while (scanIdx < currentKeys.lastIndex && !currentKeys[scanIdx + 1].startsWith("collection_") &&
                items[scanIdx + 1].addonName == targetAddonName) {
                scanIdx++
            }
            newIndex = scanIdx
        }

        if (newIndex == currentIndex) return

        val reordered = currentKeys.toMutableList().apply {
            removeAt(currentIndex)
            // After removal, if target is after current position, shift index by -1
            val insertAt = if (direction < 0) {
                newIndex
            } else {
                newIndex // currentIndex < newIndex, after removal target shifts by -1, but we want AFTER the block
            }
            add(insertAt, key)
        }
        val reorderedItems = reorderItemsByKeys(reordered)

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
            pushRowOrderFor(reorderedItems)
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                collectionsDataStore.collections,
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles,
                layoutPreferenceDataStore.followAddonsOrder,
                layoutPreferenceDataStore.recoRowDescriptors,
                rowOrderRefresh
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[2] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[3] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val customTitles = values[4] as Map<String, String>
                val followAddons = values[5] as Boolean
                @Suppress("UNCHECKED_CAST")
                val recoDescriptors = values[6] as List<RecoRowDescriptor>

                val enabledAddons = addons.enabledAddons()
                addonsSnapshot = enabledAddons

                // Single source of truth: when a saved rowOrder exists (written by the
                // dashboard / this UI), derive enabled + order from it so this screen and the
                // home render identically. Reco/collection entries that aren't represented in
                // rowOrder fall back to the local items model.
                val rowOrder = savedRowOrder
                val (effectiveOrderKeys, effectiveDisabledKeys) = if (!rowOrder.isNullOrEmpty()) {
                    projectRowOrderToLocalKeys(
                        addons = enabledAddons,
                        recoDescriptors = recoDescriptors,
                        rowOrder = rowOrder,
                        fallbackOrderKeys = savedOrderKeys,
                        fallbackDisabledKeys = disabledKeys
                    )
                } else {
                    savedOrderKeys to disabledKeys
                }

                val items = buildOrderedCatalogItems(
                    addons = enabledAddons,
                    collections = collections,
                    savedOrderKeys = effectiveOrderKeys,
                    disabledKeys = effectiveDisabledKeys,
                    customTitles = customTitles,
                    followAddonsOrder = followAddons,
                    recoDescriptors = recoDescriptors
                )
                Pair(items, followAddons)
            }.collectLatest { (orderedItems, followAddons) ->
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        followAddonsOrder = followAddons
                    )
                }
            }
        }
    }

    /**
     * Projects the unified [rowOrder] (dashboard / home model: {id, kind, type, enabled}) onto
     * the reorder UI's local key space ({addonId}_{type}_{catalogId} for catalogs, reco_engine_*
     * for reco). Returns (orderKeys, disabledKeys) so the UI mirrors the home exactly.
     *
     * Builtin/addon entries resolve by matching catalogId(entry.id)+type against installed addon
     * catalogs (same logic the home uses). Reco entries resolve via the recoRowDescriptor keys.
     * Keys not present in rowOrder keep their fallback (local) order/enabled so nothing vanishes.
     */
    private fun projectRowOrderToLocalKeys(
        addons: List<Addon>,
        recoDescriptors: List<RecoRowDescriptor>,
        rowOrder: List<HomeRowOrderEntry>,
        fallbackOrderKeys: List<String>,
        fallbackDisabledKeys: Set<String>
    ): Pair<List<String>, Set<String>> {
        val orderKeys = mutableListOf<String>()
        val disabledKeys = fallbackDisabledKeys.toMutableSet()
        val seen = mutableSetOf<String>()
        val recoKeySet = recoDescriptors.map { it.key }.toSet()

        fun emit(key: String, enabled: Boolean) {
            if (seen.add(key)) orderKeys.add(key)
            if (enabled) disabledKeys.remove(key) else disabledKeys.add(key)
        }

        rowOrder.forEach { entry ->
            rowEntryToLocalKeys(addons, recoKeySet, entry).forEach { key ->
                emit(key, entry.enabled)
            }
        }

        // Append any local keys not represented in rowOrder (e.g. collections, newly installed
        // addon catalogs) in their existing fallback order so they still surface.
        fallbackOrderKeys.forEach { key ->
            if (seen.add(key)) orderKeys.add(key)
        }
        return orderKeys to disabledKeys
    }

    /** Resolves a single rowOrder entry to the local catalog/reco key(s) it represents. */
    private fun rowEntryToLocalKeys(
        addons: List<Addon>,
        recoKeySet: Set<String>,
        entry: HomeRowOrderEntry
    ): List<String> {
        val types = when (entry.type.trim().lowercase()) {
            "movie", "movies" -> listOf("movie")
            "series", "tv", "show", "shows" -> listOf("series")
            "both", "", "all" -> listOf("movie", "series")
            else -> listOf(entry.type.trim().lowercase())
        }
        return when (entry.kind.trim().lowercase()) {
            "reco" -> types.mapNotNull { t ->
                val rawType = if (t == "series") "series" else "movie"
                recoKeySet.firstOrNull {
                    it.startsWith("reco_engine_${rawType}_${entry.id}")
                }
            }
            "addon" -> {
                // Dashboard addon entries are keyed by addon URL (resolved from the
                // nuvio_addons UUID at pull time) — match the addon by canonical baseUrl
                // and project ALL its home catalogs of the requested type(s). Fall back to
                // the legacy per-catalog interpretation when no addon URL matches.
                val matchedAddon = addons.firstOrNull { addon ->
                    canonicalAddonUrl(addon.baseUrl) == canonicalAddonUrl(entry.id) ||
                        addon.id == entry.id
                }
                if (matchedAddon != null) {
                    matchedAddon.catalogs
                        .filter { it.apiType in types }
                        .map { homeCatalogKey(matchedAddon.id, it.apiType, it.id) }
                } else {
                    types.mapNotNull { t ->
                        addons.firstNotNullOfOrNull { addon ->
                            addon.catalogs.firstOrNull { it.id == entry.id && it.apiType == t }
                                ?.let { homeCatalogKey(addon.id, t, it.id) }
                        }
                    }
                }
            }
            "builtin" -> types.mapNotNull { t ->
                addons.firstNotNullOfOrNull { addon ->
                    addon.catalogs.firstOrNull { it.id == entry.id && it.apiType == t }
                        ?.let { homeCatalogKey(addon.id, t, it.id) }
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Rebuilds a unified rowOrder from the current ordered item list so a toggle/reorder in this
     * UI writes back to the SAME source the home reads. Existing rowOrder entries are transformed
     * (re-sequenced + enabled updated) preserving their id/kind/type; any catalog item not yet in
     * rowOrder is appended as a new entry so nothing is lost.
     */
    private fun buildRowOrderForPush(orderedItems: List<CatalogOrderItem>): List<HomeRowOrderEntry> {
        val addons = addonsSnapshot
        val backendHost = com.nuvio.tv.core.reco.RecoBackend.host
        val result = mutableListOf<HomeRowOrderEntry>()
        orderedItems.forEach { item ->
            val key = item.key
            val enabled = !item.isDisabled
            when {
                key.startsWith("reco_engine_") -> {
                    // key form: reco_engine_{rawType}_{reason_type}_{index}
                    val rest = key.removePrefix("reco_engine_")
                    val rawType = if (rest.startsWith("series_")) "series" else "movie"
                    val id = rest.removePrefix("${rawType}_").substringBeforeLast("_")
                    if (id.isNotBlank()) {
                        result.add(HomeRowOrderEntry(id = id, kind = "reco", type = rawType, enabled = enabled))
                    }
                }
                key.startsWith("collection_") -> {
                    // Collections are not part of the dashboard rowOrder model; skip.
                }
                else -> {
                    // catalog key: {addonId}_{type}_{catalogId}
                    val addon = addons.firstOrNull { key.startsWith("${it.id}_") }
                    val catalog = addon?.let { a ->
                        val remainder = key.removePrefix("${a.id}_")
                        a.catalogs.firstOrNull { remainder == "${it.apiType}_${it.id}" }
                    }
                    if (addon != null && catalog != null) {
                        val isBuiltin = addon.baseUrl.contains(backendHost, ignoreCase = true)
                        result.add(
                            HomeRowOrderEntry(
                                id = catalog.id,
                                kind = if (isBuiltin) "builtin" else "addon",
                                type = catalog.apiType,
                                enabled = enabled
                            )
                        )
                    }
                }
            }
        }
        return result.distinctBy { Triple(it.id, it.kind, it.type) }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        collections: List<Collection> = emptyList(),
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        customTitles: Map<String, String> = emptyMap(),
        followAddonsOrder: Boolean = false,
        recoDescriptors: List<RecoRowDescriptor> = emptyList()
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val recoEntries = recoDescriptors.map { desc ->
            CatalogOrderEntry(
                key = desc.key,
                disableKey = desc.key,
                catalogName = desc.label,
                addonName = "Recommendations",
                typeLabel = "reco"
            )
        }
        val collectionEntries = collections.map { collection ->
            CatalogOrderEntry(
                key = "collection_${collection.id}",
                disableKey = "collection_${collection.id}",
                catalogName = collection.title,
                addonName = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                typeLabel = "collection"
            )
        }
        val allEntries = defaultEntries + recoEntries + collectionEntries
        val availableMap = allEntries.associateBy { it.key }
        val defaultOrderKeys = allEntries.map { it.key }

        val effectiveOrder: List<String>
        if (followAddonsOrder) {
            // In follow mode, addon catalogs stay in manifest order.
            // Reco rows and collections are positioned based on their relative position in savedOrderKeys.
            val addonKeys = defaultEntries.map { it.key }
            val recoKeysInOrder = recoEntries.map { it.key }
            val recoKeysSet = recoKeysInOrder.toSet()
            val collectionKeys = collectionEntries.map { it.key }.toSet()

            val savedValid = savedOrderKeys.filter { it in availableMap }.distinct()

            if (savedValid.isNotEmpty()) {
                // Rebuild order: take saved order but replace addon sequence with manifest order.
                // Strategy: walk through savedValid, output addon keys in manifest order,
                // insert reco/collections at their saved positions relative to addon boundaries.
                val result = mutableListOf<String>()
                var addonPointer = 0 // pointer into addonKeys (manifest order)

                for (savedKey in savedValid) {
                    if (savedKey in collectionKeys || savedKey in recoKeysSet) {
                        result.add(savedKey)
                    } else {
                        // It's an addon catalog key in saved order - advance manifest pointer
                        // to include all addon keys up to and including this one
                        val targetManifestIdx = addonKeys.indexOf(savedKey)
                        if (targetManifestIdx >= 0) {
                            while (addonPointer <= targetManifestIdx) {
                                val ak = addonKeys[addonPointer]
                                if (ak !in result) {
                                    result.add(ak)
                                }
                                addonPointer++
                            }
                        }
                    }
                }
                // Append any remaining addon keys not yet placed
                while (addonPointer < addonKeys.size) {
                    val ak = addonKeys[addonPointer]
                    if (ak !in result) {
                        result.add(ak)
                    }
                    addonPointer++
                }
                // Append any reco keys not in savedValid
                for (rk in recoKeysInOrder) {
                    if (rk !in result) {
                        result.add(rk)
                    }
                }
                // Append any collections not in savedValid
                for (ck in collectionKeys) {
                    if (ck !in result) {
                        result.add(ck)
                    }
                }
                // Normalize: ensure collections sit at addon block boundaries, not mid-block.
                // If a collection is between two catalogs of the same addon, push it after that block.
                effectiveOrder = normalizeCollectionPositions(result, availableMap)
            } else {
                // No saved order - addon manifest order + reco + collections at end
                effectiveOrder = addonKeys + recoKeysInOrder + collectionKeys.toList()
            }
        } else {
            val savedValid = savedOrderKeys
                .asSequence()
                .filter { it in availableMap }
                .distinct()
                .toList()

            val savedKeySet = savedValid.toSet()
            val missing = defaultOrderKeys.filterNot { it in savedKeySet }
            effectiveOrder = savedValid + missing
        }

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val displayName = customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            val isCollection = key.startsWith("collection_")
            val isReco = key.startsWith("reco_engine_")

            val canMoveUp: Boolean
            val canMoveDown: Boolean
            if (followAddonsOrder) {
                if (isCollection || isReco) {
                    canMoveUp = index > 0
                    canMoveDown = index < effectiveOrder.lastIndex
                } else {
                    canMoveUp = false
                    canMoveDown = false
                }
            } else {
                canMoveUp = index > 0
                canMoveDown = index < effectiveOrder.lastIndex
            }

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = displayName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys ||
                    (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown
            )
        }
    }

    /**
     * Ensures collections are positioned at addon block boundaries.
     * If a collection ended up between two catalogs of the same addon,
     * push it to the end of that addon's block.
     */
    private fun normalizeCollectionPositions(
        order: List<String>,
        availableMap: Map<String, CatalogOrderEntry>
    ): List<String> {
        val result = order.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < result.size) {
                val key = result[i]
                if (!key.startsWith("collection_")) {
                    i++
                    continue
                }
                val prevAddon = findAddonNameBefore(result, i, availableMap)
                val nextAddon = findAddonNameAfter(result, i, availableMap)
                if (prevAddon != null && nextAddon != null && prevAddon == nextAddon) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !result[insertPos].startsWith("collection_") &&
                        availableMap[result[insertPos]]?.addonName == prevAddon
                    ) {
                        insertPos++
                    }
                    result.add(insertPos, key)
                    if (insertPos != i) changed = true
                    i++
                } else {
                    i++
                }
            }
        }
        return result
    }

    private fun findAddonNameBefore(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index - 1 downTo 0) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonName
            }
        }
        return null
    }

    private fun findAddonNameAfter(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index + 1 until order.size) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonName
            }
        }
        return null
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = homeCatalogKey(
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                ),
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return homeCatalogKey(addonId, type, catalogId)
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val legacyDisableKey: String? = null,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
