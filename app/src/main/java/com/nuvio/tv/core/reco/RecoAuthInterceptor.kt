package com.nuvio.tv.core.reco

import com.nuvio.tv.core.network.ServerHealthNotifier
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Attaches `Authorization: Bearer <supabase_access_token>` to every request whose
 * host matches the reco backend ([RecoBackend.host]).
 *
 * Why a host-scoped interceptor: the shared [okhttp3.OkHttpClient] is used by many
 * APIs (TMDB, Trakt, GitHub, …). The reco backend requires the
 * Nuvio bearer token in private mode on ALL metadata/data endpoints (`/reco/...`,
 * `/people/...`, `/titles/...`, `/companies/...`, `/metadata/...`, `/resolve/...`,
 * `/api/unique-contributions`, `/api/onboarding/candidates`, …). Doing this once here
 * covers every current and future reco call uniformly, instead of editing each call
 * site. See api_bridge.md "F32 — Private-mode metadata lock".
 *
 * Rules:
 * - Only touches requests to [RecoBackend.host]; all other hosts pass through untouched.
 * - Skips when a request already carries an `Authorization` header (so the call sites
 *   that already attach the bearer token — RecommendationRepository, RecoRatingService —
 *   are never double-headed).
 * - Skips the genuinely-public endpoints that must stay anonymous:
 *   `/health` and the catalog-addon `/manifest.json` (Stremio needs the manifest to
 *   install; it stays public). `/image/...` and `/metadata/images/...` are NOT skipped
 *   here — they are an image proxy that the Coil loader authenticates separately;
 *   attaching the token when present is harmless for them.
 * - F72 (api_bridge.md): catalog-addon DATA routes (`/catalog-addon/catalog`, `/meta`,
 *   `/stream`, `/subtitles`, `/device-profile`, `/torbox-key`, …) REQUIRE the user
 *   token in private mode and are NOT treated as public here — the user token is
 *   attached just like any other reco-host data call (never `Bearer <CATALOG_SECRET>`).
 *   Only `/catalog-addon/.../manifest.json` stays public.
 * - Skips when there is no token (signed out): the request goes out unauthenticated.
 */
class RecoAuthInterceptor(
    // Supplier (not the instance) so this interceptor can be wired into the shared
    // OkHttpClient without forcing eager construction of [RecoAuthTokenProvider] —
    // its dependency subgraph transitively needs OkHttpClient, which would otherwise
    // create a Hilt dependency cycle. Resolved lazily on first request.
    private val tokenProvider: () -> RecoAuthTokenProvider,
    // Supplier of the server-health notifier (lazy for the same cycle-avoidance reason).
    // Used purely to surface the non-blocking "server issues" notice when OUR backend is
    // unreachable — it NEVER touches auth state or clears any cache. Defaults to null for
    // the Coil image client, whose cached/lazy poster loads make a poor outage signal; only
    // the shared data OkHttpClient supplies a real notifier.
    private val serverHealthNotifier: (() -> ServerHealthNotifier)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Only the reco backend host; leave TMDB/Trakt/etc. alone. Third-party addon hosts
        // never reach this branch, so their reachability is independent of our backend.
        if (!request.url.host.equals(RecoBackend.host, ignoreCase = true)) {
            return chain.proceed(request)
        }

        // Don't clobber an Authorization header that a call site already set
        // (existing authed reco calls, or the catalog-addon CATALOG_SECRET client).
        if (request.header("Authorization") != null) {
            return proceedTrackingHealth(chain, request)
        }

        val path = request.url.encodedPath
        if (isPublicPath(path)) {
            return proceedTrackingHealth(chain, request)
        }

        // No token = unauthenticated. Our backend (built-in provider, reco, image/metadata
        // proxy, shared TorBox key, …) is gated behind a full Nuvio account, so we must NOT
        // communicate with it at all when signed out. Short-circuit with a local 401 instead
        // of letting the request leave the device. Public paths (handled above) still pass.
        // This is a deliberate local block, NOT a server outage, so it does not notify.
        val token = tokenProvider().currentToken()
            ?: return blockedUnauthenticated(request)

        return proceedTrackingHealth(
            chain,
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }

    /**
     * Proceeds with an OUR-host request and observes whether the backend looks down.
     *
     * - A connection failure / timeout / DNS error throws [IOException]: we report the
     *   outage (non-blocking notice) and RE-THROW so callers keep their existing graceful
     *   fallbacks (cached content, third-party addons) — we never swallow into a logout.
     * - A 5xx response is reported as an outage but passed through unchanged.
     * - Any other response (2xx/3xx/4xx) re-arms the notice; 4xx is an app-level condition
     *   (auth/not-found), not a server outage.
     */
    private fun proceedTrackingHealth(chain: Interceptor.Chain, request: okhttp3.Request): Response {
        val notifier = serverHealthNotifier?.invoke()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            notifier?.reportOurHostUnreachable()
            throw e
        }
        if (response.code in 500..599) {
            notifier?.reportOurHostUnreachable()
        } else {
            notifier?.reportOurHostHealthy()
        }
        return response
    }

    private fun blockedUnauthenticated(request: okhttp3.Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthenticated: Nuvio backend access requires sign-in")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()

    private fun isPublicPath(path: String): Boolean =
        path == "/health" ||
            // Only the catalog-addon MANIFEST stays public (Stremio install needs it).
            // All other /catalog-addon/* DATA routes require the user token (F72).
            (path.startsWith("/catalog-addon") && path.endsWith("/manifest.json"))
}

