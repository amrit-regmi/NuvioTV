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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"

internal fun HomeViewModel.observeRecoRows() {
    if (BuildConfig.RECO_MODE != "private") return

    viewModelScope.launch {
        combine(
            authManager.authState.filterIsInstance<AuthState.FullAccount>(),
            profileManager.activeProfileId,
        ) { account, profileId -> account to profileId }
            .distinctUntilChanged()
            .collectLatest { (account, profileId) ->
                val token = authManager.currentAccessToken() ?: return@collectLatest
                val rows = recommendationRepository.fetchRows(account.userId, token, profileId.toString())
                val catalogRows = rows.mapIndexed { index, row -> row.toCatalogRow(index) }
                _recoRows.value = catalogRows

                // Pre-populate IMDB↔TMDB mapping cache so MetaDetailsViewModel can resolve
                // TMDB IDs from tt-format IDs without network calls in private mode.
                rows.forEach { row ->
                    row.items.forEach { item ->
                        val imdbId = item.imdb_id
                        if (imdbId != null) {
                            tmdbService.preCacheMapping(imdbId, item.tmdb_id)
                        }
                    }
                }

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
                // Expose a stable reason_type+content_type → reco keys map so the saved
                // rowOrder (which references reco rows by id/reason_type + type) can match
                // the rendered reco rows precisely (not via the dominant-kind heuristic).
                // A category may emit MULTIPLE rows of the same content_type (e.g. 2×
                // because_watched Movies); group them so EVERY row renders at that slot,
                // in backend order (groupBy preserves first-seen iteration order).
                recoKeyByReasonAndType = rows.mapIndexed { index, row ->
                    val ct = row.recoContentType()
                    val rawType = if (ct == "series") "series" else "movie"
                    val catalogId = "${row.reason_type}_$index"
                    "${row.reason_type}|$ct" to "reco_engine_${rawType}_$catalogId"
                }
                    .groupBy({ it.first }, { it.second })
                recoRowKeys = descriptors.map { it.key }
                layoutPreferenceDataStore.setRecoRowDescriptors(descriptors)
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
                Log.d("RecoRows", "Fetched ${rows.size} reco rows for ${account.userId}")
            }
    }
}

/**
 * Resolves the row's content type, preferring the backend-declared [RecoRow.content_type]
 * over the dominant-kind heuristic over [RecoRow.items]. Returns "movie" or "series".
 */
internal fun RecoRow.recoContentType(): String {
    content_type?.trim()?.lowercase()?.let { ct ->
        when (ct) {
            "movie", "movies" -> return "movie"
            "series", "tv", "show", "shows" -> return "series"
        }
    }
    val dominant = items.groupBy { it.kind }.maxByOrNull { it.value.size }?.key ?: "movie"
    return if (dominant == "series" || dominant == "tv") "series" else "movie"
}

private fun RecoRow.toCatalogRow(index: Int = 0): CatalogRow {
    val contentType = recoContentType()
    val type = if (contentType == "series") ContentType.SERIES else ContentType.MOVIE
    // Append a content-type suffix so the movie and series variants of the same reco
    // reason (e.g. "Top picks for you") are visually distinct on the home screen.
    val typeSuffix = if (contentType == "series") "Series" else "Movies"
    val displayLabel = if (label.contains(typeSuffix, ignoreCase = true)) label else "$label - $typeSuffix"
    return CatalogRow(
        addonId = "reco_engine",
        addonName = "For You",
        addonBaseUrl = BuildConfig.RECO_API_BASE_URL,
        catalogId = "${reason_type}_$index",
        catalogName = displayLabel,
        type = type,
        rawType = contentType,
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
    val logoUrl = logo_path?.let {
        when {
            it.startsWith("http") -> it
            BuildConfig.RECO_MODE == "private" -> "${BuildConfig.RECO_API_BASE_URL}/image$it"
            else -> "$TMDB_POSTER_BASE$it"
        }
    }
    return MetaPreview(
        id = imdb_id ?: "tmdb:$tmdb_id",
        type = type,
        rawType = kind,
        name = title,
        poster = poster ?: posterUrl,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = logoUrl,
        description = overview,
        releaseInfo = year?.toString(),
        imdbRating = vote_average?.toFloat() ?: score.takeIf { it > 0.0 }?.toFloat(),
        genres = genres,
        ageRating = certification?.trim()?.takeIf { it.isNotBlank() },
        status = status?.trim()?.takeIf { it.isNotBlank() },
    )
}
