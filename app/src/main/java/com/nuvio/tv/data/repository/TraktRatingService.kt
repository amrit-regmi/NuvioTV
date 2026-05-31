package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedEpisodeItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedMovieItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedShowItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingEpisodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingShowRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddRequestDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktRatingItem {
    data class Movie(val ids: TraktIdsDto, val title: String? = null) : TraktRatingItem
    data class Show(val ids: TraktIdsDto, val title: String? = null) : TraktRatingItem
    data class Episode(
        val ids: TraktIdsDto,
        val showTitle: String? = null,
        val season: Int,
        val number: Int,
        val episodeTitle: String? = null
    ) : TraktRatingItem
}

@Singleton
class TraktRatingService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val profileManager: ProfileManager,
    private val traktSettingsDataStore: TraktSettingsDataStore
) {
    suspend fun canPromptForRating(item: TraktRatingItem?): Boolean {
        if (item == null) return false
        if (profileManager.activeProfileId.value != 1) return false
        if (!traktAuthService.hasRequiredCredentials()) return false
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return false
        val promptsEnabled = when (item) {
            is TraktRatingItem.Movie -> traktSettingsDataStore.rateMoviesAfterWatching.first()
            is TraktRatingItem.Show -> traktSettingsDataStore.rateEpisodesAfterWatching.first()
            is TraktRatingItem.Episode -> traktSettingsDataStore.rateEpisodesAfterWatching.first()
        }
        if (!promptsEnabled) return false
        return when (item) {
            is TraktRatingItem.Movie -> item.ids.hasAnyId()
            is TraktRatingItem.Show -> item.ids.trakt != null || item.ids.tvdb != null || item.ids.hasAnyId()
            is TraktRatingItem.Episode -> item.ids.trakt != null || item.ids.tvdb != null
        }
    }

    suspend fun getDefaultRating(): Int =
        traktSettingsDataStore.defaultRatingPromptValue.first()
            .coerceIn(TraktSettingsDataStore.MIN_RATING_PROMPT_VALUE, TraktSettingsDataStore.MAX_RATING_PROMPT_VALUE)

    suspend fun getExistingRating(item: TraktRatingItem): Int? {
        if (!canPromptForRating(item)) return null
        return when (item) {
            is TraktRatingItem.Movie -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getRatedMovies(authorization = authHeader)
                } ?: return null
                if (!response.isSuccessful) return null
                findMovieRating(item.ids, response.body().orEmpty())
            }
            is TraktRatingItem.Show -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getRatedShows(authorization = authHeader)
                } ?: return null
                if (!response.isSuccessful) return null
                findShowRating(item.ids, response.body().orEmpty())
            }
            is TraktRatingItem.Episode -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getRatedEpisodes(authorization = authHeader)
                } ?: return null
                if (!response.isSuccessful) return null
                findEpisodeRating(item.ids, response.body().orEmpty())
            }
        }
    }

    suspend fun submitRating(item: TraktRatingItem, rating: Int): Result<Unit> {
        val normalizedRating = rating.coerceIn(1, 10)
        if (!canPromptForRating(item)) {
            return Result.failure(IllegalStateException("Trakt rating is unavailable"))
        }
        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addRatings(
                authorization = authHeader,
                body = buildRequestBody(item, normalizedRating)
            )
        } ?: return Result.failure(IllegalStateException("Unable to reach Trakt"))

        if (!response.isSuccessful) {
            return Result.failure(IllegalStateException("Failed to save Trakt rating (${response.code()})"))
        }

        return Result.success(Unit)
    }

    internal fun buildRequestBody(item: TraktRatingItem, rating: Int): TraktRatingsAddRequestDto {
        val r = rating.coerceIn(1, 10)
        return when (item) {
            is TraktRatingItem.Movie -> TraktRatingsAddRequestDto(
                movies = listOf(TraktRatingMovieRequestDto(rating = r, ids = item.ids))
            )
            is TraktRatingItem.Show -> TraktRatingsAddRequestDto(
                shows = listOf(TraktRatingShowRequestDto(rating = r, ids = item.ids))
            )
            is TraktRatingItem.Episode -> TraktRatingsAddRequestDto(
                episodes = listOf(TraktRatingEpisodeRequestDto(rating = r, ids = item.ids))
            )
        }
    }

    internal fun findMovieRating(targetIds: TraktIdsDto, ratings: List<TraktRatedMovieItemDto>): Int? =
        ratings.firstNotNullOfOrNull { ratedItem ->
            val r = ratedItem.rating?.coerceIn(1, 10) ?: return@firstNotNullOfOrNull null
            if (idsMatch(targetIds, ratedItem.movie?.ids)) r else null
        }

    internal fun findShowRating(targetIds: TraktIdsDto, ratings: List<TraktRatedShowItemDto>): Int? =
        ratings.firstNotNullOfOrNull { ratedItem ->
            val r = ratedItem.rating?.coerceIn(1, 10) ?: return@firstNotNullOfOrNull null
            if (idsMatch(targetIds, ratedItem.show?.ids)) r else null
        }

    internal fun findEpisodeRating(targetIds: TraktIdsDto, ratings: List<TraktRatedEpisodeItemDto>): Int? =
        ratings.firstNotNullOfOrNull { ratedItem ->
            val r = ratedItem.rating?.coerceIn(1, 10) ?: return@firstNotNullOfOrNull null
            if (idsMatch(targetIds, ratedItem.episode?.ids)) r else null
        }

    private fun idsMatch(target: TraktIdsDto, candidate: TraktIdsDto?): Boolean {
        candidate ?: return false
        return listOf(
            target.trakt != null && target.trakt == candidate.trakt,
            !target.imdb.isNullOrBlank() && target.imdb == candidate.imdb,
            target.tmdb != null && target.tmdb == candidate.tmdb,
            target.tvdb != null && target.tvdb == candidate.tvdb,
            !target.slug.isNullOrBlank() && target.slug == candidate.slug
        ).any { it }
    }
}
