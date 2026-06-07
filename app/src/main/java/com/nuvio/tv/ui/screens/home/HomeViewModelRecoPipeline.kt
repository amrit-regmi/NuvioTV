package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.reco.RecoItem
import com.nuvio.tv.core.reco.RecoRow
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
                _recoRows.value = rows.map { it.toCatalogRow() }
                scheduleUpdateCatalogRows()
                Log.d("RecoRows", "Fetched ${rows.size} reco rows for ${account.userId}")
            }
    }
}

private fun RecoRow.toCatalogRow(): CatalogRow {
    val dominant = items.groupBy { it.kind }.maxByOrNull { it.value.size }?.key ?: "movie"
    val type = if (dominant == "movie") ContentType.MOVIE else ContentType.SERIES
    return CatalogRow(
        addonId = "reco_engine",
        addonName = "For You",
        addonBaseUrl = BuildConfig.RECO_API_BASE_URL,
        catalogId = reason_type,
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
        if (it.startsWith("http")) it else "$TMDB_POSTER_BASE$it"
    }
    return MetaPreview(
        id = "tmdb:$tmdb_id",
        type = type,
        rawType = kind,
        name = title,
        poster = posterUrl,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = year?.toString(),
        imdbRating = score.takeIf { it > 0.0 }?.toFloat(),
        genres = emptyList(),
    )
}
