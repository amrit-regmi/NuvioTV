package com.nuvio.tv.core.reco

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbPersonResponse
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecoMetadataService"

data class RecoCredits(
    val cast: List<MetaCastMember>,
    val directors: List<MetaCastMember>,
    val writers: List<MetaCastMember>
)

@Singleton
class RecoMetadataService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val moshi: Moshi,
) {
    private val base = BuildConfig.RECO_API_BASE_URL

    fun imageUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$base/image$it" }

    private inline fun <reified T> parseJson(body: String): T? =
        runCatching { moshi.adapter(T::class.java).fromJson(body) }.getOrNull()

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder().url(url).get().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) null else resp.body?.string()
    }.getOrNull()

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
        language: String = "en"
    ): PersonDetail? = withContext(Dispatchers.IO) {
        try {
            val (person, credits) = coroutineScope {
                val pd = async { get("$base/people/$personId")?.let { parseJson<TmdbPersonResponse>(it) } }
                val pc = async { get("$base/people/$personId/credits")?.let { parseJson<TmdbPersonCreditsResponse>(it) } }
                pd.await() to pc.await()
            }
            if (person == null) return@withContext null

            val preferCrew = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

            val castMovieCredits = mapMovieCreditsFromCast(credits?.cast.orEmpty())
            val crewMovieCredits = mapMovieCreditsFromCrew(credits?.crew.orEmpty())
            val movieCredits = when {
                preferCrew && crewMovieCredits.isNotEmpty() -> crewMovieCredits
                castMovieCredits.isNotEmpty() -> castMovieCredits
                else -> crewMovieCredits
            }

            val castTvCredits = mapTvCreditsFromCast(credits?.cast.orEmpty())
            val crewTvCredits = mapTvCreditsFromCrew(credits?.crew.orEmpty())
            val tvCredits = when {
                preferCrew && crewTvCredits.isNotEmpty() -> crewTvCredits
                castTvCredits.isNotEmpty() -> castTvCredits
                else -> crewTvCredits
            }

            PersonDetail(
                tmdbId = person.id,
                name = person.name ?: "Unknown",
                biography = null,
                birthday = null,
                deathday = null,
                placeOfBirth = null,
                profilePhoto = imageUrl(person.profilePath),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                movieCredits = movieCredits,
                tvCredits = tvCredits
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchPersonDetail failed for $personId", e)
            null
        }
    }

    suspend fun fetchMovieCredits(tmdbId: Int): RecoCredits? = withContext(Dispatchers.IO) {
        try {
            val body = get("$base/titles/movie/$tmdbId/credits") ?: return@withContext null
            val resp = parseJson<TmdbCreditsResponse>(body) ?: return@withContext null
            buildRecoCredits(resp)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMovieCredits failed for $tmdbId", e)
            null
        }
    }

    suspend fun fetchTvCredits(tmdbId: Int): RecoCredits? = withContext(Dispatchers.IO) {
        try {
            val body = get("$base/titles/tv/$tmdbId/credits") ?: return@withContext null
            val resp = parseJson<TmdbCreditsResponse>(body) ?: return@withContext null
            buildRecoCredits(resp)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTvCredits failed for $tmdbId", e)
            null
        }
    }

    private fun buildRecoCredits(resp: TmdbCreditsResponse): RecoCredits {
        val cast = resp.cast.orEmpty().mapNotNull { member ->
            val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaCastMember(
                name = name,
                character = member.character?.takeIf { it.isNotBlank() },
                photo = imageUrl(member.profilePath),
                tmdbId = member.id
            )
        }
        val directors = resp.crew.orEmpty()
            .filter { it.job.equals("Director", ignoreCase = true) || it.job.equals("Creator", ignoreCase = true) }
            .mapNotNull { member ->
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = member.job ?: "Director",
                    photo = imageUrl(member.profilePath),
                    tmdbId = member.id
                )
            }
            .distinctBy { it.tmdbId ?: it.name.lowercase() }
        val writers = resp.crew.orEmpty()
            .filter { crew ->
                val job = crew.job?.lowercase() ?: ""
                (job.contains("writer") || job.contains("screenplay")) &&
                        !job.equals("Director", ignoreCase = true)
            }
            .mapNotNull { member ->
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = "Writer",
                    photo = imageUrl(member.profilePath),
                    tmdbId = member.id
                )
            }
            .distinctBy { it.tmdbId ?: it.name.lowercase() }
        return RecoCredits(cast = cast, directors = directors, writers = writers)
    }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val dept = knownForDepartment?.trim()?.lowercase() ?: return false
        return dept.isNotBlank() && dept != "acting" && dept != "actors"
    }

    private fun mapMovieCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = imageUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = imageUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapMovieCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = imageUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = imageUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = imageUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = imageUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = imageUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = imageUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }
}
