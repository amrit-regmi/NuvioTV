package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.reco.RecoItem
import com.nuvio.tv.core.reco.RecoRow
import com.nuvio.tv.data.local.RecoRowDescriptor
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"

internal fun HomeViewModel.observeRecoRows() {
    if (BuildConfig.RECO_MODE != "private") return

    viewModelScope.launch {
        authManager.authState
            .filterIsInstance<AuthState.FullAccount>()
            .distinctUntilChanged()
            .collectLatest { account ->
                val token = authManager.currentAccessToken() ?: return@collectLatest
                val rows = recommendationRepository.fetchRows(account.userId, token)
                val catalogRows = rows.mapIndexed { index, row -> row.toCatalogRow(index) }
                _recoRows.value = catalogRows

                // Pre-populate the enriched-previews map for reco items so that the hero
                // panel never enters the "enrichment pending" blank state for items that
                // already carry full metadata (title, description, backdrop) from the
                // recommendation engine.  This fixes blank hero panels (Bug 2) and missing
                // plot summaries (Bug 3) when focus moves across reco row items.
                val recoEnrichedEntries = catalogRows
                    .flatMap { it.items }
                    .filter { it.background != null || it.description != null || it.imdbRating != null }
                    .associateBy { it.id }
                if (recoEnrichedEntries.isNotEmpty()) {
                    _enrichedPreviews.value = _enrichedPreviews.value + recoEnrichedEntries
                    Log.d("RecoRows", "Pre-populated ${recoEnrichedEntries.size} reco items in enriched-previews cache")
                }

                // Also populate the singleton RecoMetadataService preview cache so that
                // MetaDetailsViewModel can build a fallback Meta without TMDB in private mode.
                val allItems = catalogRows.flatMap { it.items }
                if (allItems.isNotEmpty()) {
                    recoMetadataService.cacheRepoPreviews(allItems)
                }

                val descriptors = catalogRows.map { cr ->
                    RecoRowDescriptor(
                        key = "reco_engine_${cr.rawType}_${cr.catalogId}",
                        label = cr.catalogName
                    )
                }
                recoRowKeys = descriptors.map { it.key }
                layoutPreferenceDataStore.setRecoRowDescriptors(descriptors)
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
                Log.d("RecoRows", "Fetched ${rows.size} reco rows for ${account.userId}")
            }
    }
}

private fun RecoRow.toCatalogRow(index: Int = 0): CatalogRow {
    val dominant = items.groupBy { it.kind }.maxByOrNull { it.value.size }?.key ?: "movie"
    val type = if (dominant == "movie") ContentType.MOVIE else ContentType.SERIES
    return CatalogRow(
        addonId = "reco_engine",
        addonName = "For You",
        addonBaseUrl = BuildConfig.RECO_API_BASE_URL,
        catalogId = "${reason_type}_$index",
        catalogName = label,
        type = type,
        rawType = dominant,
        items = items.map { it.toMetaPreview() },
        hasMore = false,
    )
}

private fun RecoItem.toMetaPreview(): MetaPreview {
    val type = if (kind == "movie") ContentType.MOVIE else ContentType.SERIES
    val posterUrl = poster_path?.let {
        when {
            it.startsWith("http") -> it
            BuildConfig.RECO_MODE == "private" -> "${BuildConfig.RECO_API_BASE_URL}/image$it"
            else -> "$TMDB_POSTER_BASE$it"
        }
    }
    return MetaPreview(
        id = "tmdb:$tmdb_id",
        type = type,
        rawType = kind,
        name = title,
        poster = poster ?: posterUrl,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = null,
        description = overview,
        releaseInfo = year?.toString(),
        imdbRating = vote_average?.toFloat() ?: score.takeIf { it > 0.0 }?.toFloat(),
        genres = genres,
    )
}
