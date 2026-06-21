package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ratings now come EXCLUSIVELY from OUR backend
 * (`GET /catalog-addon/ratings/{imdbId}.json` → `{ratings:[{source,value,votes}]}`).
 * The app no longer calls `api.mdblist.com` directly. Requests go through the shared
 * OkHttpClient + RecoAuthInterceptor (same host = our backend), which attaches the user
 * Bearer token. The backend ratings table may be EMPTY (e.g. server-side MDBLIST_API_KEY
 * not yet set) → the app degrades gracefully (no extra ratings, never errors).
 *
 * The per-provider visibility toggles (settings) still apply locally, and TmdbService is
 * still used to resolve a tmdb/meta id to an imdb id (the backend key).
 */
@Singleton
class MDBListRepository @Inject constructor(
    private val api: CatalogAddonApi,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val result: MDBListRatingsResult?,
        val expiresAtMs: Long
    )

    private val tag = "MDBListRepository"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<MDBListRatingsResult?>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lightweight helper for home screen enrichment - fetches only the IMDb rating. */
    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null

        val mediaType = normalizeMediaType(itemType)
        val imdbId = resolveImdbId(
            meta = Meta(
                id = itemId,
                type = when (normalizeMediaType(itemType)) {
                    "show" -> com.nuvio.tv.domain.model.ContentType.SERIES
                    else -> com.nuvio.tv.domain.model.ContentType.MOVIE
                },
                name = itemId,
                poster = null,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            ),
            fallbackItemId = itemId,
            fallbackItemType = itemType,
            mediaType = mediaType
        ) ?: return null

        return getCachedOrFetch(imdbId)?.ratings?.imdb
    }

    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null

        val enabledProviders = enabledProviders(settings)
        if (enabledProviders.isEmpty()) return null

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val imdbId = resolveImdbId(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val full = getCachedOrFetch(imdbId) ?: return null
        // Apply per-provider visibility toggles locally.
        return filterToEnabled(full, enabledProviders)
    }

    /** Fetches the full rating set for an imdb id (all sources) and caches it. */
    private suspend fun getCachedOrFetch(imdbId: String): MDBListRatingsResult? {
        val cacheKey = imdbId
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.result
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchRatings(imdbId).also { result ->
                        cache[cacheKey] = CacheEntry(
                            result = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { inFlight[cacheKey] = it }
        }
        return deferred.await()
    }

    private suspend fun fetchRatings(imdbId: String): MDBListRatingsResult? {
        return try {
            val response = api.getRatings(imdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "ratings failed for $imdbId (${response.code()})")
                return null
            }
            val items = response.body()?.ratings ?: emptyList()
            if (items.isEmpty()) return null

            var trakt: Double? = null
            var imdb: Double? = null
            var tmdb: Double? = null
            var letterboxd: Double? = null
            var tomatoes: Double? = null
            var audience: Double? = null
            var metacritic: Double? = null

            for (item in items) {
                val value = item.value ?: continue
                when (item.source?.trim()?.lowercase()) {
                    "trakt" -> trakt = value
                    "imdb" -> imdb = value
                    "tmdb" -> tmdb = value
                    "letterboxd" -> letterboxd = value
                    "tomatoes", "rottentomatoes", "rotten_tomatoes", "tomatometer" -> tomatoes = value
                    "audience", "rt_audience", "tomatoesaudience" -> audience = value
                    "metacritic", "metascore" -> metacritic = value
                    else -> { /* unknown source — ignore */ }
                }
            }

            val ratings = MDBListRatings(
                trakt = trakt,
                imdb = imdb,
                tmdb = tmdb,
                letterboxd = letterboxd,
                tomatoes = tomatoes,
                audience = audience,
                metacritic = metacritic
            )
            if (ratings.isEmpty()) return null

            MDBListRatingsResult(ratings = ratings, hasImdbRating = ratings.imdb != null)
        } catch (e: Exception) {
            Log.w(tag, "Error fetching ratings for $imdbId", e)
            null
        }
    }

    /** Keeps only the rating sources whose visibility toggle is enabled. */
    private fun filterToEnabled(
        full: MDBListRatingsResult,
        enabled: Set<ProviderType>
    ): MDBListRatingsResult? {
        val r = full.ratings
        val filtered = MDBListRatings(
            trakt = r.trakt.takeIf { ProviderType.TRAKT in enabled },
            imdb = r.imdb.takeIf { ProviderType.IMDB in enabled },
            tmdb = r.tmdb.takeIf { ProviderType.TMDB in enabled },
            letterboxd = r.letterboxd.takeIf { ProviderType.LETTERBOXD in enabled },
            tomatoes = r.tomatoes.takeIf { ProviderType.TOMATOES in enabled },
            audience = r.audience.takeIf { ProviderType.AUDIENCE in enabled },
            metacritic = r.metacritic.takeIf { ProviderType.METACRITIC in enabled }
        )
        if (filtered.isEmpty()) return null
        return MDBListRatingsResult(ratings = filtered, hasImdbRating = filtered.imdb != null)
    }

    private enum class ProviderType { TRAKT, IMDB, TMDB, LETTERBOXD, TOMATOES, AUDIENCE, METACRITIC }

    private fun enabledProviders(settings: MDBListSettings): Set<ProviderType> = buildSet {
        if (settings.showTrakt) add(ProviderType.TRAKT)
        if (settings.showImdb) add(ProviderType.IMDB)
        if (settings.showTmdb) add(ProviderType.TMDB)
        if (settings.showLetterboxd) add(ProviderType.LETTERBOXD)
        if (settings.showTomatoes) add(ProviderType.TOMATOES)
        if (settings.showAudience) add(ProviderType.AUDIENCE)
        if (settings.showMetacritic) add(ProviderType.METACRITIC)
    }

    private suspend fun resolveImdbId(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): String? {
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbId = extractTmdbId(meta.id)
            ?: extractTmdbId(fallbackItemId)
            ?: meta.id.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: fallbackItemId.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()

        if (tmdbId != null) {
            val mapped = tmdbService.tmdbToImdb(tmdbId, fallbackItemType)
            if (!mapped.isNullOrBlank()) return mapped
        }

        val lookupType = if (fallbackItemType.isNotBlank()) fallbackItemType else mediaType
        val converted = tmdbService.ensureTmdbId(meta.id, lookupType)?.toIntOrNull()?.let { tmdbNumericId ->
            tmdbService.tmdbToImdb(tmdbNumericId, lookupType)
        }
        return converted?.takeIf { it.startsWith("tt") }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun extractTmdbId(rawId: String?): Int? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').toIntOrNull()
        }
        return null
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }
}
