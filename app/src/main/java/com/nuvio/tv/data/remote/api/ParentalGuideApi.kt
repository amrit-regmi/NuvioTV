package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface ParentalGuideApi {

    // Routed through OUR backend proxy (catalog-addon host) to avoid hitting
    // api.imdbapi.dev directly (which would leak the user's IP + what they view).
    // The shared OkHttpClient carries RecoAuthInterceptor, so the user Bearer is
    // auto-attached for /catalog-addon/* requests. Upstream shape is returned verbatim.
    @GET("parental-guide/{imdbId}.json")
    suspend fun getParentsGuide(
        @Path("imdbId") imdbId: String
    ): Response<ImdbApiParentsGuideResponse>
}

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideResponse(
    @Json(name = "parentsGuide") val parentsGuide: List<ImdbApiParentsGuideCategory>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideCategory(
    @Json(name = "category") val category: String,
    @Json(name = "severityBreakdowns") val severityBreakdowns: List<ImdbApiSeverityBreakdown>? = null,
    @Json(name = "reviews") val reviews: List<ImdbApiParentsGuideReview>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiSeverityBreakdown(
    @Json(name = "severityLevel") val severityLevel: String,
    @Json(name = "voteCount") val voteCount: Int
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideReview(
    @Json(name = "text") val text: String? = null,
    @Json(name = "isSpoiler") val isSpoiler: Boolean? = null
)
