package com.nuvio.tv.core.reco

import com.nuvio.tv.BuildConfig

/**
 * Single source of truth for the reco / taste-engine backend host.
 *
 * The host is defined by `RECO_API_BASE_URL` (debug + release) in
 * `app/build.gradle.kts`. Everything below derives from it, so that ONE value
 * sets the host everywhere in the app: all reco API calls (on
 * [BuildConfig.RECO_API_BASE_URL]), the built-in catalog-addon URL, and every
 * host-matching check.
 *
 * Do NOT hardcode any reco host anywhere else — reference these members instead.
 */
object RecoBackend {

    /** Full base URL of the reco backend. The single switchable value. */
    val baseUrl: String = BuildConfig.RECO_API_BASE_URL

    /**
     * Bare host of [baseUrl]. Used for host-matching the built-in catalog-addon
     * and ordering it first.
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
     * TmdbService/TmdbApi go through this rather than TMDB directly.
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

    /**
     * Re-homes a stored image URL that points at the reco `/image` (or
     * `/metadata/images`) proxy onto the CURRENT [baseUrl] host.
     *
     * Why: library/saved entries persist the poster URL at save time. A poster
     * persisted against a host other than the current [host] is dead — its host is
     * not [host], so [RecoAuthInterceptor] never attaches the bearer token AND that
     * host serves nothing. Freshly-fetched catalog/CW posters are fine because they
     * always carry the live host; only persisted entries can go stale.
     *
     * This rewrites ONLY reco image-proxy URLs (any scheme/host ending in the
     * `/image/...` or `/metadata/images/...` path) to the live [baseUrl], keeping
     * the path/query intact. Non-proxy absolute URLs (TMDB, third-party addon
     * images) and blank/null values are returned untouched. Idempotent: a URL
     * already on the live host is returned unchanged.
     */
    fun rehomeImageUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return url
        // Only touch our image-proxy URLs; leave TMDB / addon / other images alone.
        val marker = Regex("/(image|metadata/images)/").find(raw) ?: return raw
        val proxyPath = raw.substring(marker.range.first) // includes leading "/image/..."
        val rebuilt = "$baseUrl$proxyPath"
        return if (rebuilt == raw) raw else rebuilt
    }
}
