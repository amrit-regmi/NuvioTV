package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
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
 * Ratings are ALWAYS on. The imdb id is resolved EXCLUSIVELY from the ids OUR backend
 * already provides (meta.imdbId / meta.id / fallbackItemId) — NO external calls, no
 * client-side TMDB conversion, no per-provider visibility filtering (all sources shown).
 */
@Singleton
class MDBListRepository @Inject constructor(
    private val api: CatalogAddonApi
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

    /** Lightweight helper for home screen enrichment - fetches only the IMDb rating.
     *  Ratings are always on and resolved exclusively from OUR backend. */
    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
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

    /** Home-screen hero enrichment: fetches the FULL aggregated rating set (all sources) for a
     *  focused card, resolving the imdb id from ids OUR backend already provides (itemImdbId /
     *  itemId). Backed by the same 30-min cache + in-flight dedup as the detail screen. */
    suspend fun getRatingsForItem(
        itemId: String,
        itemType: String,
        itemImdbId: String? = null
    ): MDBListRatings? {
        val imdbId = extractImdbId(itemImdbId)
            ?: extractImdbId(itemId)
            ?: return null
        return getCachedOrFetch(imdbId)?.ratings
    }

    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? {
        // Ratings are ALWAYS on and resolved EXCLUSIVELY from OUR backend. The defunct
        // remote per-provider toggles no longer gate/filter anything — return all sources.
        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val imdbId = resolveImdbId(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        return getCachedOrFetch(imdbId)
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
                    // Only cache genuine outcomes (a valid response, incl. empty). Transient
                    // failures — 401 (token not yet ready / session-restore race), 5xx, network
                    // errors — throw out of fetchRatings so we do NOT negative-cache them for the
                    // 30-min TTL. Otherwise a single early 401 would blank ratings for half an hour
                    // even after the Supabase session is valid. On failure we return null WITHOUT
                    // writing the cache, so the next detail-open retries.
                    fetchRatings(imdbId).also { result ->
                        cache[cacheKey] = CacheEntry(
                            result = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } catch (e: Exception) {
                    Log.w(tag, "ratings fetch errored for $imdbId — not caching (will retry)", e)
                    null
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { inFlight[cacheKey] = it }
        }
        return deferred.await()
    }

    /**
     * Fetches the full rating set. Returns a result (possibly a valid-but-empty null when the
     * backend genuinely has no ratings) on success; THROWS on transient failures (non-2xx incl.
     * 401, or network exception) so the caller can avoid negative-caching them.
     */
    private suspend fun fetchRatings(imdbId: String): MDBListRatingsResult? {
        val response = api.getRatings(imdbId)
        if (!response.isSuccessful) {
            val code = response.code()
            // 401 here = the Supabase user Bearer wasn't attached/accepted for the
            // /catalog-addon/ratings call (RecoAuthInterceptor injects it host-scoped, same as
            // /reco + /image). Surface it loudly and treat as transient (throw → no cache).
            Log.w(tag, "ratings HTTP $code for $imdbId (transient; not cached)")
            throw java.io.IOException("ratings HTTP $code for $imdbId")
        }
        return parseRatings(response.body()?.ratings ?: emptyList())
    }

    /** Maps the backend ratings list into our model. Returns null when there is nothing to show. */
    private fun parseRatings(
        items: List<com.nuvio.tv.data.remote.api.CatalogRatingItemDto>
    ): MDBListRatingsResult? {
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

        return MDBListRatingsResult(ratings = ratings, hasImdbRating = ratings.imdb != null)
    }

    /**
     * Resolves an imdb id EXCLUSIVELY from ids OUR backend already provides
     * (meta.imdbId / meta.id / fallbackItemId — all verified to carry `tt...`).
     * Makes NO external calls (no client-side TMDB external_ids conversion). If no
     * imdb id is present, returns null and ratings degrade gracefully.
     */
    private fun resolveImdbId(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): String? {
        extractImdbId(meta.imdbId)?.let { return it }
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }
        return null
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }
}
