package com.nuvio.tv.core.reco

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <supabase_access_token>` to every request whose
 * host matches the reco backend ([RecoBackend.host]).
 *
 * Why a host-scoped interceptor: the shared [okhttp3.OkHttpClient] is used by many
 * APIs (TMDB, Trakt, GitHub, …). After the F32 cutover the reco backend requires the
 * Nuvio bearer token in private mode on ALL metadata/data endpoints (`/reco/*`,
 * `/people/*`, `/titles/*`, `/companies/*`, `/metadata/*`, `/resolve/*`,
 * `/api/unique-contributions`, `/api/onboarding/candidates`, …). Doing this once here
 * covers every current and future reco call uniformly, instead of editing each call
 * site. See api_bridge.md "F32 — Private-mode metadata lock".
 *
 * Rules:
 * - Only touches requests to [RecoBackend.host]; all other hosts pass through untouched.
 * - Skips when a request already carries an `Authorization` header (so the call sites
 *   that already attach the bearer token — RecommendationRepository, RecoRatingService —
 *   or the catalog-addon client which sends its own `Bearer <CATALOG_SECRET>` are never
 *   double-headed).
 * - Skips the genuinely-public endpoints that must stay anonymous:
 *   `/health` and the catalog-addon Stremio routes (gated by CATALOG_SECRET, not the
 *   user token). `/image/*` and `/metadata/images/*` are NOT skipped here — they are an
 *   image proxy that the Coil loader authenticates separately; attaching the token when
 *   present is harmless for them.
 * - Skips when there is no token (signed out): the request goes out unauthenticated.
 */
class RecoAuthInterceptor(
    private val tokenProvider: RecoAuthTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Only the reco backend host; leave TMDB/Trakt/etc. alone.
        if (!request.url.host.equals(RecoBackend.host, ignoreCase = true)) {
            return chain.proceed(request)
        }

        // Don't clobber an Authorization header that a call site already set
        // (existing authed reco calls, or the catalog-addon CATALOG_SECRET client).
        if (request.header("Authorization") != null) {
            return chain.proceed(request)
        }

        val path = request.url.encodedPath
        if (isPublicPath(path)) {
            return chain.proceed(request)
        }

        val token = tokenProvider.currentToken()
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }

    private fun isPublicPath(path: String): Boolean =
        path == "/health" ||
            path.startsWith("/catalog-addon")
}
