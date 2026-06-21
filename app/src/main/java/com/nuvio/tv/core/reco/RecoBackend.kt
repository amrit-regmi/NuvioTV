package com.nuvio.tv.core.reco

import com.nuvio.tv.BuildConfig

/**
 * Single source of truth for the reco / taste-engine backend host.
 *
 * The platform is consolidating onto `hamrocinema.regmig.com` (dropping
 * `recoengine.regmig.com`). The actual cutover happens at deploy time.
 *
 * F32: switch to hamrocinema.regmig.com at deploy — change `RECO_API_BASE_URL`
 * (debug + release) in `app/build.gradle.kts`. Everything below derives from it,
 * so flipping that ONE value flips the host everywhere in the app: all reco API
 * calls (already on [BuildConfig.RECO_API_BASE_URL]), the built-in catalog-addon
 * URL, and every host-matching check.
 *
 * Do NOT hardcode `recoengine.regmig.com` (or any reco host) anywhere else —
 * reference these members instead.
 */
object RecoBackend {

    /** Full base URL, e.g. `https://recoengine.regmig.com`. The single switchable value. */
    val baseUrl: String = BuildConfig.RECO_API_BASE_URL

    /**
     * Bare host of [baseUrl], e.g. `recoengine.regmig.com`. Used for host-matching
     * the built-in catalog-addon and ordering it first.
     */
    val host: String = baseUrl
        .substringAfter("://")
        .substringBefore("/")
        .substringBefore(":")

    /** Canonical built-in catalog-addon URL served by the backend. */
    val catalogAddonUrl: String = "$baseUrl/catalog-addon"

    /**
     * Drop-in replacement base for `https://api.themoviedb.org/3/`. The backend TMDB
     * proxy is local-first + Tor-on-miss + cached, injects the server-side api_key, and
     * requires the user Bearer token (attached by RecoAuthInterceptor since same host).
     * FIX 1: TmdbService/TmdbApi go through this instead of TMDB directly.
     */
    val tmdbProxyBaseUrl: String = "$baseUrl/tmdb/3/"

    /**
     * Rewrites a TMDB image URL (or bare `/path.jpg`) to go through our `/image` proxy
     * so Coil never hits `image.tmdb.org` directly. Mirrors the reco pipeline's rewrite.
     * In non-private/open mode (RECO_MODE != "private") returns the original TMDB URL.
     *
     * Accepts either a full `https://image.tmdb.org/t/p/<size>/<path>` URL or a bare
     * `/<path>` and produces `${baseUrl}/image/t/p/<size>/<path>` (or `${baseUrl}/image/<path>`).
     */
    fun proxiedTmdbImageUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (com.nuvio.tv.BuildConfig.RECO_MODE != "private") return raw
        // Already proxied through our backend — leave as-is.
        if (raw.startsWith("$baseUrl/image")) return raw
        val path = when {
            raw.startsWith("https://image.tmdb.org") ->
                raw.substringAfter("image.tmdb.org")
            raw.startsWith("http") -> return raw // some non-TMDB absolute URL, leave it
            raw.startsWith("/") -> raw
            else -> "/$raw"
        }
        return "$baseUrl/image$path"
    }
}
