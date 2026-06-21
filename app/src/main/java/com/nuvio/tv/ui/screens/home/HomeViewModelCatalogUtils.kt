package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Job

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(addons: List<Addon>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                "${addon.id}|${addon.baseUrl}|${catalog.apiType}|${catalog.id}|${catalog.name}|${catalog.showInHome}|${catalog.hasExplicitShowInHome}"
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
}

internal fun HomeViewModel.registerCatalogLoadJob(job: Job) {
    synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.add(job)
    }
    job.invokeOnCompletion {
        synchronized(activeCatalogLoadJobs) {
            activeCatalogLoadJobs.remove(job)
        }
    }
}

internal fun HomeViewModel.cancelInFlightCatalogLoads() {
    val jobsToCancel = synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.toList().also { activeCatalogLoadJobs.clear() }
    }
    jobsToCancel.forEach { it.cancel() }
}

private fun HomeViewModel.reindexCatalogRow(
    key: String,
    previousRow: CatalogRow?,
    updatedRow: CatalogRow?
) {
    previousRow?.items?.forEach { item ->
        val keys = catalogItemKeyIndex[item.id] ?: return@forEach
        keys.remove(key)
        if (keys.isEmpty()) {
            catalogItemKeyIndex.remove(item.id)
        }
    }

    updatedRow?.items?.forEach { item ->
        catalogItemKeyIndex.getOrPut(item.id) { LinkedHashSet() }.add(key)
    }
}

internal fun HomeViewModel.hasAnyCatalogRows(): Boolean = synchronized(catalogStateLock) {
    catalogsMap.isNotEmpty()
}

internal fun HomeViewModel.isCatalogOrderEmpty(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isEmpty()
}

internal fun HomeViewModel.hasCatalogOrderEntries(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isNotEmpty()
}

internal fun HomeViewModel.readCatalogRow(key: String): CatalogRow? = synchronized(catalogStateLock) {
    catalogsMap[key]
}

internal fun HomeViewModel.replaceCatalogRow(key: String, row: CatalogRow) {
    synchronized(catalogStateLock) {
        val previousRow = catalogsMap.put(key, row)
        reindexCatalogRow(key, previousRow, row)
    }
}

internal inline fun HomeViewModel.updateCatalogRow(
    key: String,
    transform: (CatalogRow) -> CatalogRow
): CatalogRow? {
    return synchronized(catalogStateLock) {
        val currentRow = catalogsMap[key] ?: return@synchronized null
        val updatedRow = transform(currentRow)
        if (updatedRow != currentRow) {
            catalogsMap[key] = updatedRow
            reindexCatalogRow(key, currentRow, updatedRow)
        }
        updatedRow
    }
}

internal fun HomeViewModel.clearCatalogData() {
    synchronized(catalogStateLock) {
        catalogsMap.clear()
        catalogItemKeyIndex.clear()
        truncatedRowCache.clear()
        pendingLazyCatalogs.clear()
        placeholderDescriptors.clear()
    }
    lazyLoadRequestedKeys.clear()
}

internal fun HomeViewModel.snapshotCatalogKeys(): Set<String> = synchronized(catalogStateLock) {
    catalogsMap.keys.toSet()
}

internal fun HomeViewModel.snapshotCatalogState(): Pair<List<String>, Map<String, CatalogRow>> = synchronized(catalogStateLock) {
    catalogOrder.toList() to catalogsMap.toMap()
}

internal fun HomeViewModel.findCatalogItemById(itemId: String): MetaPreview? = synchronized(catalogStateLock) {
    val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
    rowKeys.firstNotNullOfOrNull { key ->
        catalogsMap[key]?.items?.firstOrNull { it.id == itemId }
    }
}

internal inline fun HomeViewModel.updateIndexedCatalogItem(
    itemId: String,
    transform: (MetaPreview) -> MetaPreview
): Boolean {
    return synchronized(catalogStateLock) {
        val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
        var changed = false

        rowKeys.forEach { key ->
            val row = catalogsMap[key] ?: return@forEach
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) return@forEach

            val updatedItem = transform(row.items[itemIndex])
            if (updatedItem == row.items[itemIndex]) return@forEach

            val mutableItems = row.items.toMutableList()
            mutableItems[itemIndex] = updatedItem
            catalogsMap[key] = row.copy(items = mutableItems)
            truncatedRowCache.remove(key)
            changed = true
        }

        changed
    }
}

internal fun HomeViewModel.getTruncatedRowCacheEntry(key: String): HomeViewModel.TruncatedRowCacheEntry? = synchronized(catalogStateLock) {
    truncatedRowCache[key]
}

internal fun HomeViewModel.putTruncatedRowCacheEntry(key: String, entry: HomeViewModel.TruncatedRowCacheEntry) {
    synchronized(catalogStateLock) {
        truncatedRowCache[key] = entry
    }
}

internal fun HomeViewModel.removeTruncatedRowCacheEntry(key: String) {
    synchronized(catalogStateLock) {
        truncatedRowCache.remove(key)
    }
}

internal fun HomeViewModel.rebuildCatalogOrder(addons: List<Addon>) {
    // Unified saved order from the management dashboard (Supabase rowOrder) takes
    // precedence over the app's own ordering when present. New users (no saved
    // config) fall through to the default behavior below.
    val saved = savedRowOrder
    if (!saved.isNullOrEmpty()) {
        // The dashboard-saved rowOrder is the AUTHORITATIVE per-profile intent: the home
        // shows EXACTLY the rows enabled there and nothing else. We must NOT fall through to
        // the default "render every installed addon catalog" path when [resolved] is empty —
        // doing so leaks the general/addon catalog into profiles that disabled everything
        // (e.g. the Kids profile, whose only enabled row is a reco row that may resolve empty
        // until the reco pipeline has populated recoKeyByReasonAndType, or is gated off).
        // When a saved config exists, an empty resolution means "show only collections",
        // never "show all catalogs".
        val resolved = resolveSavedRowOrderKeys(addons, saved)
        // Collections aren't part of the dashboard rowOrder; append pinned/remaining
        // collections so they still surface (in their saved-or-default relative spot).
        val collectionKeys = collectionsCache.map { "collection_${it.id}" }
        val resolvedSet = resolved.toSet()
        val mergedOrder = resolved + collectionKeys.filterNot { it in resolvedSet }
        synchronized(catalogStateLock) {
            catalogOrder.clear()
            catalogOrder.addAll(mergedOrder)
        }
        return
    }

    val defaultOrder = buildDefaultCatalogOrder(addons)
    val recoKeys = recoRowKeys
    val collectionKeys = collectionsCache.map { "collection_${it.id}" }
    val allAvailable = (defaultOrder + recoKeys + collectionKeys).toSet()

    if (followAddonsOrderEnabled) {
        // In follow addons order mode, addon catalogs always stay in manifest order.
        // Reco rows and collections are positioned based on their relative position in saved order.
        val savedValid = homeCatalogOrderKeys
            .asSequence()
            .filter { it in allAvailable }
            .distinct()
            .toList()

        val recoKeysSet = recoKeys.toSet()
        val collectionKeysSet = collectionKeys.toSet()

        if (savedValid.isNotEmpty()) {
            val result = mutableListOf<String>()
            var addonPointer = 0

            for (savedKey in savedValid) {
                if (savedKey in collectionKeysSet || savedKey in recoKeysSet) {
                    result.add(savedKey)
                } else {
                    // Addon catalog - advance manifest pointer to include all up to this one
                    val targetIdx = defaultOrder.indexOf(savedKey)
                    if (targetIdx >= 0) {
                        while (addonPointer <= targetIdx) {
                            val ak = defaultOrder[addonPointer]
                            if (ak !in result) {
                                result.add(ak)
                            }
                            addonPointer++
                        }
                    }
                }
            }
            // Append remaining addon keys
            while (addonPointer < defaultOrder.size) {
                val ak = defaultOrder[addonPointer]
                if (ak !in result) {
                    result.add(ak)
                }
                addonPointer++
            }
            // Append any reco keys not in saved order
            for (rk in recoKeys) {
                if (rk !in result) {
                    result.add(rk)
                }
            }
            // Append any collections not in saved order
            for (ck in collectionKeys) {
                if (ck !in result) {
                    result.add(ck)
                }
            }

            // Normalize: push collections that ended up mid-addon-block to the block boundary
            val addonKeyToOwner = buildAddonKeyOwnerMap(addons)
            val normalized = normalizeCollectionBoundaries(result, addonKeyToOwner)

            synchronized(catalogStateLock) {
                catalogOrder.clear()
                catalogOrder.addAll(normalized)
            }
        } else {
            // No saved order - manifest order + reco + collections at end
            synchronized(catalogStateLock) {
                catalogOrder.clear()
                catalogOrder.addAll(defaultOrder + recoKeys + collectionKeys)
            }
        }
    } else {
        val savedValid = homeCatalogOrderKeys
            .asSequence()
            .filter { it in allAvailable }
            .distinct()
            .toList()

        val savedSet = savedValid.toSet()
        val unsavedCatalogs = defaultOrder.filterNot { it in savedSet }
        val unsavedReco = recoKeys.filterNot { it in savedSet }
        val unsavedCollections = collectionKeys.filterNot { it in savedSet }
        val mergedOrder = savedValid + unsavedCatalogs + unsavedReco + unsavedCollections

        synchronized(catalogStateLock) {
            catalogOrder.clear()
            catalogOrder.addAll(mergedOrder)
        }
    }
}

/** Type tokens the saved rowOrder may carry for a single entry. */
private fun rowOrderTypes(rawType: String): List<String> {
    return when (rawType.trim().lowercase()) {
        "movie", "movies" -> listOf("movie")
        "series", "tv", "show", "shows" -> listOf("series")
        "both", "", "all" -> listOf("movie", "series")
        else -> listOf(rawType.trim().lowercase())
    }
}

/**
 * Resolves the dashboard-saved [HomeRowOrderEntry] list into concrete catalogOrder keys,
 * in the exact saved order, skipping disabled entries. Builtin rows resolve against the
 * backend catalog-addon (host = RecoBackend.host); addon rows against any installed addon;
 * reco rows against the reason_type+content_type → key map populated by the reco pipeline.
 */
internal fun HomeViewModel.resolveSavedRowOrderKeys(
    addons: List<Addon>,
    saved: List<com.nuvio.tv.core.sync.HomeRowOrderEntry>
): List<String> {
    val backendHost = com.nuvio.tv.core.reco.RecoBackend.host
    // Lookup of available addon catalog keys → so we only emit catalogs that actually exist.
    val availableCatalogKeys = mutableSetOf<String>()
    addons.forEach { addon ->
        addon.catalogs.forEach { catalog ->
            availableCatalogKeys.add(catalogKey(addon.id, catalog.apiType, catalog.id))
        }
    }
    val builtinAddon = addons.firstOrNull { it.baseUrl.contains(backendHost, ignoreCase = true) }

    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun addKey(key: String) {
        if (seen.add(key)) result.add(key)
    }

    saved.forEach { entry ->
        if (!entry.enabled) return@forEach
        val types = rowOrderTypes(entry.type)
        when (entry.kind.trim().lowercase()) {
            "reco" -> {
                // A reco category+type can map to MULTIPLE rows (e.g. 2× because_watched);
                // render ALL of them at this slot, in backend order.
                types.forEach { t ->
                    recoKeyByReasonAndType["${entry.id}|$t"]?.forEach { addKey(it) }
                }
            }
            "builtin" -> {
                types.forEach { t ->
                    val candidate = builtinAddon?.let { catalogKey(it.id, t, entry.id) }
                    if (candidate != null && candidate in availableCatalogKeys) {
                        addKey(candidate)
                    } else {
                        // Fallback: any addon exposing this catalog id + type.
                        addons.firstNotNullOfOrNull { addon ->
                            addon.catalogs.firstOrNull { it.id == entry.id && it.apiType == t }
                                ?.let { catalogKey(addon.id, t, it.id) }
                        }?.let { addKey(it) }
                    }
                }
            }
            "addon" -> {
                types.forEach { t ->
                    addons.firstNotNullOfOrNull { addon ->
                        addon.catalogs.firstOrNull { it.id == entry.id && it.apiType == t }
                            ?.let { catalogKey(addon.id, t, it.id) }
                    }?.let { addKey(it) }
                }
            }
        }
    }
    return result
}

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
    addons.forEach { addon ->
        addon.catalogs
            .filterNot {
                !it.shouldShowOnHome() || isCatalogDisabled(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    type = it.apiType,
                    catalogId = it.id,
                    catalogName = it.name
                )
            }
            .forEach { catalog ->
                val key = catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in orderedKeys) {
                    orderedKeys.add(key)
                }
            }
    }
    return orderedKeys
}

internal fun HomeViewModel.isCatalogDisabled(
    addonBaseUrl: String,
    addonId: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) {
        return true
    }
    // Backward compatibility with previously stored keys.
    return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
}

internal fun HomeViewModel.disableCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
}

internal fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
    return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
}

internal fun CatalogDescriptor.shouldShowOnHome(): Boolean {
    if (isSearchOnlyCatalog()) return false
    return !hasExplicitShowInHome || showInHome
}

internal fun MetaPreview.hasHeroArtwork(): Boolean {
    return !background.isNullOrBlank()
}

internal fun HomeViewModel.extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
}

private fun buildAddonKeyOwnerMap(addons: List<Addon>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    addons.forEach { addon ->
        addon.catalogs.forEach { catalog ->
            val key = "${addon.id}_${catalog.apiType}_${catalog.id}"
            map[key] = addon.id
        }
    }
    return map
}

private fun normalizeCollectionBoundaries(
    order: List<String>,
    addonKeyToOwner: Map<String, String>
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
            val prevOwner = findOwnerBefore(result, i, addonKeyToOwner)
            val nextOwner = findOwnerAfter(result, i, addonKeyToOwner)
            if (prevOwner != null && nextOwner != null && prevOwner == nextOwner) {
                // Collection is mid-block, push to end of this addon block
                result.removeAt(i)
                var insertPos = i
                while (insertPos < result.size &&
                    !result[insertPos].startsWith("collection_") &&
                    addonKeyToOwner[result[insertPos]] == prevOwner
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

private fun findOwnerBefore(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index - 1 downTo 0) {
        if (!order[j].startsWith("collection_")) return owners[order[j]]
    }
    return null
}

private fun findOwnerAfter(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index + 1 until order.size) {
        if (!order[j].startsWith("collection_")) return owners[order[j]]
    }
    return null
}
