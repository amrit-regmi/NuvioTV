package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.data.remote.api.CatalogSkipDto
import com.nuvio.tv.data.remote.api.CatalogSkipSegmentDto
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "recap", "outro"
    val provider: String   // source reported by our backend (e.g. "introdb"); "backend" if unknown
)

/**
 * Skip-intro timestamps now come EXCLUSIVELY from OUR backend
 * (`GET /catalog-addon/skip/{type}/{id}.json`, id `tt…:S:E`). The backend serves
 * DB-only data (Tor-scraped nightly) and does imdb→MAL resolution server-side, so the
 * app no longer talks to IntroDb / AniSkip / Anime-Skip / ARM directly. Requests go
 * through the shared OkHttpClient + RecoAuthInterceptor (same host = our backend), which
 * attaches the user Bearer token. An empty `{}` body → no skip intervals (graceful).
 */
@Singleton
class SkipIntroRepository @Inject constructor(
    private val catalogAddonApi: CatalogAddonApi
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()

    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> {
        if (imdbId.isNullOrBlank() || !imdbId.startsWith("tt")) return emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return it }
        val result = fetchFromBackend(type = "series", id = "$imdbId:$season:$episode")
        return result.also { cache[cacheKey] = it }
    }

    /**
     * MAL/Kitsu-keyed lookups: the backend skip endpoint is keyed on imdb (`tt…:S:E`) and
     * performs imdb→MAL resolution itself, so it has no MAL/Kitsu lookup. Without an imdb
     * id we cannot form the backend key; degrade gracefully to no intervals.
     */
    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> = emptyList()

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> = emptyList()

    private suspend fun fetchFromBackend(type: String, id: String): List<SkipInterval> {
        return try {
            val response = catalogAddonApi.getSkip(type = type, id = id)
            if (response.isSuccessful) {
                response.body()?.toSkipIntervals() ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "backend: no skip data for $type/$id (${e.message})")
            emptyList()
        }
    }

    private fun CatalogSkipDto.toSkipIntervals(): List<SkipInterval> {
        val src = source?.takeIf { it.isNotBlank() } ?: "backend"
        return listOfNotNull(
            intro.toSkipIntervalOrNull("intro", src),
            recap.toSkipIntervalOrNull("recap", src),
            outro.toSkipIntervalOrNull("outro", src)
        )
    }

    private fun CatalogSkipSegmentDto?.toSkipIntervalOrNull(type: String, source: String): SkipInterval? {
        if (this == null) return null
        val s = start ?: return null
        val e = end ?: return null
        if (e <= s) return null
        return SkipInterval(startTime = s, endTime = e, type = type, provider = source)
    }
}
